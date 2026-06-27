package com.h2ray.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 100;
    private TextView connectionStatus;
    private Button connectButton;
    private boolean connected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectionStatus = findViewById(R.id.connection_status);
        connectButton = findViewById(R.id.connect_button);
        connectButton.setOnClickListener(view -> toggleConnection());

        findViewById(R.id.profile_card).setOnClickListener(
            view -> Toast.makeText(this, R.string.profiles_next_stage, Toast.LENGTH_SHORT).show()
        );
    }

    private void toggleConnection() {
        if (connected) {
            connected = false;
            renderConnectionState();
            return;
        }

        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST);
        } else {
            onVpnPermissionGranted();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            onVpnPermissionGranted();
        }
    }

    private void onVpnPermissionGranted() {
        Toast.makeText(this, R.string.core_not_integrated, Toast.LENGTH_LONG).show();
    }

    private void renderConnectionState() {
        connectionStatus.setText(connected ? R.string.connected : R.string.disconnected);
        connectButton.setText(connected ? R.string.disconnect : R.string.connect);
    }
}
