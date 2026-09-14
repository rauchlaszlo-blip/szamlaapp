package hu.rauch.szamlakezelo;

import android.os.Bundle;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DriveBackupPlugin.class);
        registerPlugin(InvoiceAttachmentPlugin.class);
        super.onCreate(savedInstanceState);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                bridge.getWebView().evaluateJavascript(
                    "window.handleAndroidBack ? window.handleAndroidBack() : false",
                    handled -> {
                        if (!"true".equals(handled)) {
                            finish();
                        }
                    }
                );
            }
        });

        View contentView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(contentView, (view, windowInsets) -> {
            Insets statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(
                view.getPaddingLeft(),
                statusBars.top,
                view.getPaddingRight(),
                view.getPaddingBottom()
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(contentView);
    }
}
