package com.h2ray.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.h2ray.app.data.ProfileStore;
import com.h2ray.app.data.ConnectionStatusStore;
import com.h2ray.app.vpn.H2RayVpnService;
import com.h2ray.app.xray.XrayBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 100;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView connectionStatus;
    private TextView profileName;
    private TextView profileDetails;
    private TextView connectionError;
    private Button connectButton;
    private Button importButton;
    private ProfileStore profileStore;
    private ConnectionStatusStore connectionStatusStore;

    private final Runnable stateUpdater = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profileStore = new ProfileStore(this);
        connectionStatusStore = new ConnectionStatusStore(this);
        connectionStatus = findViewById(R.id.connection_status);
        connectionError = findViewById(R.id.connection_error);
        profileName = findViewById(R.id.profile_name);
        profileDetails = findViewById(R.id.profile_details);
        connectButton = findViewById(R.id.connect_button);
        importButton = findViewById(R.id.import_button);

        connectButton.setOnClickListener(view -> toggleConnection());
        importButton.setOnClickListener(view -> showImportDialog());
        connectionError.setOnClickListener(view -> showConnectionError());
        findViewById(R.id.profile_card).setOnClickListener(view -> showProfileActions());
        findViewById(R.id.header_menu_button).setOnClickListener(view -> showAppMenu());
        findViewById(R.id.nav_home).setOnClickListener(
            view -> Toast.makeText(this, R.string.home_opened, Toast.LENGTH_SHORT).show()
        );
        findViewById(R.id.nav_profiles).setOnClickListener(view -> showProfileActions());
        findViewById(R.id.nav_rules).setOnClickListener(
            view -> Toast.makeText(this, R.string.rules_next_stage, Toast.LENGTH_SHORT).show()
        );
        findViewById(R.id.nav_settings).setOnClickListener(
            view -> Toast.makeText(this, R.string.settings_next_stage, Toast.LENGTH_SHORT).show()
        );
        applySystemBarInsets();
        requestNotificationPermissionIfNeeded();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(stateUpdater);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(stateUpdater);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toggleConnection() {
        if (H2RayVpnService.isRunning()) {
            startService(H2RayVpnService.stopIntent(this));
            render();
            return;
        }

        if (!profileStore.hasActiveProfile()) {
            showImportDialog();
            return;
        }

        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void startVpn() {
        Intent intent = H2RayVpnService.startIntent(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        connectionStatus.setText(R.string.connecting);
    }

    private void showImportDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setHint(R.string.import_hint);
        input.setPadding(40, 24, 40, 24);

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text != null && isSupportedInput(text.toString())) {
                    input.setText(text);
                }
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.import_profile)
            .setMessage(R.string.import_description)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_action, null)
            .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> importProfile(dialog, input.getText().toString())));
        dialog.show();
    }

    private void importProfile(AlertDialog dialog, String source) {
        String trimmed = source.trim();
        if (!isSupportedInput(trimmed)) {
            inputError(getString(R.string.unsupported_profile));
            return;
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        executor.execute(() -> {
            try {
                String config = XrayBridge.parseShareText(trimmed);
                String name = XrayBridge.detectName(trimmed);
                String protocol = XrayBridge.detectProtocol(config);
                profileStore.saveActiveProfile(name, protocol, config, trimmed);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    render();
                    Toast.makeText(this, R.string.profile_imported, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    inputError(error.getMessage());
                });
            }
        });
    }

    private void showProfileActions() {
        if (!profileStore.hasActiveProfile()) {
            showImportDialog();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(profileStore.getName())
            .setItems(new String[] {
                getString(R.string.replace_profile),
                getString(R.string.delete_profile)
            }, (dialog, which) -> {
                if (which == 0) {
                    showImportDialog();
                } else {
                    if (H2RayVpnService.isRunning()) {
                        startService(H2RayVpnService.stopIntent(this));
                    }
                    profileStore.clear();
                    render();
                }
            })
            .show();
    }

    private void showAppMenu() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.app_information)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showConnectionError() {
        String error = connectionStatusStore.getError();
        if (error.trim().isEmpty()) {
            return;
        }
        TextView message = new TextView(this);
        message.setText(error);
        message.setTextIsSelectable(true);
        message.setTextColor(getColor(R.color.text_primary));
        message.setTextSize(13);
        message.setPadding(40, 24, 40, 24);
        new AlertDialog.Builder(this)
            .setTitle(R.string.connection_error_details)
            .setView(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.app_root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()
                );
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void render() {
        boolean running = H2RayVpnService.isRunning();
        boolean hasProfile = profileStore.hasActiveProfile();
        String state = connectionStatusStore.getState();
        if (running || ConnectionStatusStore.RUNNING.equals(state)) {
            connectionStatus.setText(R.string.connected);
        } else if (ConnectionStatusStore.CONNECTING.equals(state)) {
            connectionStatus.setText(R.string.connecting);
        } else if (ConnectionStatusStore.ERROR.equals(state)) {
            connectionStatus.setText(R.string.connection_failed);
        } else {
            connectionStatus.setText(R.string.disconnected);
        }
        String error = connectionStatusStore.getError();
        connectionError.setText(error);
        connectionError.setVisibility(error.trim().isEmpty() ? View.GONE : View.VISIBLE);
        connectButton.setText(running ? R.string.disconnect : R.string.connect);
        profileName.setText(hasProfile ? profileStore.getName() : getString(R.string.no_profile));
        profileDetails.setText(hasProfile ? profileStore.getProtocol() : getString(R.string.import_required));
        importButton.setText(hasProfile ? R.string.replace_profile : R.string.import_profile);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private boolean isSupportedInput(String value) {
        String text = value.trim().toLowerCase();
        return text.startsWith("vless://")
            || text.startsWith("vmess://")
            || text.startsWith("trojan://")
            || text.startsWith("ss://")
            || text.startsWith("{")
            || text.contains("\nvless://")
            || text.contains("\nvmess://")
            || text.contains("\ntrojan://")
            || text.contains("\nss://");
    }

    private void inputError(String message) {
        Toast.makeText(
            this,
            message == null || message.trim().isEmpty() ? getString(R.string.import_failed) : message,
            Toast.LENGTH_LONG
        ).show();
    }
}
