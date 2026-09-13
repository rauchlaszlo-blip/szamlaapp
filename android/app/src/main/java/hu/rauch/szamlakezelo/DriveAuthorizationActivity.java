package hu.rauch.szamlakezelo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;

public class DriveAuthorizationActivity extends Activity {
    public static final String EXTRA_PENDING_INTENT = "pendingIntent";
    private static final int REQUEST_AUTHORIZATION = 3001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;

        PendingIntent pendingIntent = getIntent().getParcelableExtra(EXTRA_PENDING_INTENT);
        if (pendingIntent == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        try {
            startIntentSenderForResult(
                pendingIntent.getIntentSender(),
                REQUEST_AUTHORIZATION,
                null,
                0,
                0,
                0
            );
        } catch (IntentSender.SendIntentException exception) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AUTHORIZATION) {
            setResult(resultCode, data);
            finish();
        }
    }
}
