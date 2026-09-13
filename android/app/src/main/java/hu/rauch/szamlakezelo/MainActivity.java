package hu.rauch.szamlakezelo;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DriveBackupPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
