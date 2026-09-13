package hu.rauch.szamlakezelo;

import android.os.Bundle;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DriveBackupPlugin.class);
        super.onCreate(savedInstanceState);

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
