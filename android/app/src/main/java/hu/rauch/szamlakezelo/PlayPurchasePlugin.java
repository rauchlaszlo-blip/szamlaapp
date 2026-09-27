package hu.rauch.szamlakezelo;

import android.content.Context;
import android.content.SharedPreferences;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@CapacitorPlugin(name = "PlayPurchase")
public class PlayPurchasePlugin extends Plugin {
    private static final String PRODUCT_ID = "szamlakezelo_full_unlock";
    private static final long TRIAL_MS = 30L * 24L * 60L * 60L * 1000L;
    private BillingClient billing;
    private SharedPreferences prefs;

    @Override
    public void load() {
        prefs = getContext().getSharedPreferences("play_purchase", Context.MODE_PRIVATE);
        billing = BillingClient.newBuilder(getContext())
            .setListener(this::onPurchasesUpdated)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build();
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        long started = prefs.getLong("trial_started", 0);
        String prior = call.getString("priorTrialStart");
        if (prior != null) {
            try {
                long previous = Instant.parse(prior).toEpochMilli();
                if (previous > 1577836800000L && previous <= System.currentTimeMillis()
                    && (started == 0 || previous < started)) started = previous;
            } catch (Exception ignored) { }
        }
        if (started == 0) started = System.currentTimeMillis();
        prefs.edit().putLong("trial_started", started).apply();
        connect(() -> queryPurchases(call), () -> call.resolve(status(false)));
    }

    @PluginMethod
    public void purchase(PluginCall call) {
        connect(() -> {
            QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build();
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product)).build();
            billing.queryProductDetailsAsync(params, (result, detailsResult) -> {
                List<ProductDetails> products = detailsResult.getProductDetailsList();
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || products.isEmpty()) {
                    call.reject("A vásárlás még nem érhető el a Google Playen.");
                    return;
                }
                ProductDetails details = products.get(0);
                List<ProductDetails.OneTimePurchaseOfferDetails> offers = details.getOneTimePurchaseOfferDetailsList();
                if (offers == null || offers.isEmpty()) {
                    call.reject("Nincs elérhető vásárlási ajánlat.");
                    return;
                }
                BillingFlowParams.ProductDetailsParams detailParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details).setOfferToken(offers.get(0).getOfferToken()).build();
                BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(detailParams)).build();
                getActivity().runOnUiThread(() -> {
                    BillingResult launched = billing.launchBillingFlow(getActivity(), flow);
                    if (launched.getResponseCode() == BillingClient.BillingResponseCode.OK) call.resolve();
                    else call.reject("A vásárlás nem indítható: " + launched.getDebugMessage());
                });
            });
        }, () -> call.reject("A Google Play most nem érhető el."));
    }

    private void connect(Runnable ready, Runnable unavailable) {
        if (billing.isReady()) { ready.run(); return; }
        billing.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) ready.run();
                else unavailable.run();
            }
            @Override public void onBillingServiceDisconnected() { /* The next call reconnects. */ }
        });
    }

    private void queryPurchases(PluginCall call) {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build();
        billing.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                boolean owned = false;
                for (Purchase purchase : purchases) {
                    if (validPurchase(purchase)) { owned = true; acknowledge(purchase); }
                }
                boolean changed = prefs.getBoolean("unlocked", false) != owned;
                prefs.edit().putBoolean("unlocked", owned).apply();
                if (changed) notifyListeners("entitlementChanged", status(true));
            }
            call.resolve(status(result.getResponseCode() == BillingClient.BillingResponseCode.OK));
        });
    }

    private boolean validPurchase(Purchase purchase) {
        return purchase.getProducts().contains(PRODUCT_ID)
            && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED;
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || purchases == null) return;
        for (Purchase purchase : purchases) {
            if (!validPurchase(purchase)) continue;
            prefs.edit().putBoolean("unlocked", true).apply();
            acknowledge(purchase);
            notifyListeners("entitlementChanged", status(true));
        }
    }

    private void acknowledge(Purchase purchase) {
        if (purchase.isAcknowledged()) return;
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken()).build();
        billing.acknowledgePurchase(params, result -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                // A next launch's purchase query will retry acknowledgment.
            }
        });
    }

    private JSObject status(boolean verified) {
        long start = prefs.getLong("trial_started", System.currentTimeMillis());
        JSObject value = new JSObject();
        value.put("purchased", prefs.getBoolean("unlocked", false));
        value.put("trialEndsAt", start + TRIAL_MS);
        value.put("verified", verified);
        return value;
    }

    @Override protected void handleOnDestroy() {
        if (billing != null) billing.endConnection();
    }
}
