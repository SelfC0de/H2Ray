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
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.h2ray.app.data.ProfileStore;
import com.h2ray.app.data.ConnectionStatusStore;
import com.h2ray.app.data.AppSettings;
import com.h2ray.app.vpn.H2RayVpnService;
import com.h2ray.app.xray.XrayBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private AppSettings appSettings;
    private LinearLayout profilesList;
    private View homeScreen;
    private View profilesScreen;
    private View settingsScreen;

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
        appSettings = new AppSettings(this);
        connectionStatus = findViewById(R.id.connection_status);
        connectionError = findViewById(R.id.connection_error);
        profileName = findViewById(R.id.profile_name);
        profileDetails = findViewById(R.id.profile_details);
        connectButton = findViewById(R.id.connect_button);
        importButton = findViewById(R.id.import_button);
        profilesList = findViewById(R.id.profiles_list);
        homeScreen = findViewById(R.id.home_screen);
        profilesScreen = findViewById(R.id.profiles_screen);
        settingsScreen = findViewById(R.id.settings_screen);

        connectButton.setOnClickListener(view -> toggleConnection());
        importButton.setOnClickListener(view -> showImportDialog());
        connectionError.setOnClickListener(view -> showConnectionError());
        findViewById(R.id.profile_card).setOnClickListener(view -> showProfileActions());
        findViewById(R.id.header_menu_button).setOnClickListener(view -> showAppMenu());
        findViewById(R.id.nav_home).setOnClickListener(view -> showScreen("home"));
        findViewById(R.id.nav_profiles).setOnClickListener(view -> showScreen("profiles"));
        findViewById(R.id.nav_rules).setOnClickListener(
            view -> Toast.makeText(this, R.string.rules_next_stage, Toast.LENGTH_SHORT).show()
        );
        findViewById(R.id.nav_settings).setOnClickListener(view -> showScreen("settings"));
        findViewById(R.id.add_profile).setOnClickListener(view -> showImportDialog());
        findViewById(R.id.ping_profiles).setOnClickListener(view -> pingProfiles());
        findViewById(R.id.open_logs).setOnClickListener(
            view -> startActivity(new Intent(this, LogsActivity.class))
        );
        configureSettings();
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
                    renderProfiles();
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

    private void showScreen(String target) {
        homeScreen.setVisibility("home".equals(target) ? View.VISIBLE : View.GONE);
        profilesScreen.setVisibility("profiles".equals(target) ? View.VISIBLE : View.GONE);
        settingsScreen.setVisibility("settings".equals(target) ? View.VISIBLE : View.GONE);
        setNavColor(R.id.nav_home, "home".equals(target));
        setNavColor(R.id.nav_profiles, "profiles".equals(target));
        setNavColor(R.id.nav_rules, false);
        setNavColor(R.id.nav_settings, "settings".equals(target));
        if ("profiles".equals(target)) {
            renderProfiles();
        }
        if ("settings".equals(target)) {
            renderSettings();
        }
    }

    private void setNavColor(int id, boolean active) {
        ((TextView) findViewById(id)).setTextColor(
            getColor(active ? R.color.accent : R.color.text_secondary)
        );
    }

    private void renderProfiles() {
        profilesList.removeAllViews();
        List<ProfileStore.Profile> profiles = profileStore.getProfiles();
        ProfileStore.Profile active = profileStore.getActiveProfile();
        if (profiles.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_profiles);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(14);
            empty.setPadding(16, 48, 16, 48);
            profilesList.addView(empty);
            return;
        }
        for (ProfileStore.Profile profile : profiles) {
            TextView row = new TextView(this);
            String marker = active != null && active.id.equals(profile.id) ? "●  " : "○  ";
            String ping = profile.ping < 0
                ? "—"
                : profile.ping == Long.MAX_VALUE ? getString(R.string.ping_failed) : profile.ping + " ms";
            row.setText(marker + profile.name + "\n     " + profile.protocol + "  ·  " + ping);
            row.setTextColor(getColor(R.color.text_primary));
            row.setTextSize(14);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(16, 10, 16, 10);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
            );
            params.bottomMargin = dp(10);
            row.setLayoutParams(params);
            row.setOnClickListener(view -> {
                profileStore.select(profile.id);
                render();
                renderProfiles();
            });
            row.setOnLongClickListener(view -> {
                confirmDeleteProfile(profile);
                return true;
            });
            profilesList.addView(row);
        }
    }

    private void confirmDeleteProfile(ProfileStore.Profile profile) {
        new AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setMessage(R.string.delete_profile)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_profile, (dialog, which) -> {
                profileStore.delete(profile.id);
                render();
                renderProfiles();
            })
            .show();
    }

    private void pingProfiles() {
        if (H2RayVpnService.isRunning()) {
            Toast.makeText(this, R.string.ping_requires_disconnect, Toast.LENGTH_LONG).show();
            return;
        }
        executor.execute(() -> {
            for (ProfileStore.Profile profile : profileStore.getProfiles()) {
                profileStore.updatePing(profile.id, measurePing(profile));
                runOnUiThread(this::renderProfiles);
            }
        });
    }

    private long measurePing(ProfileStore.Profile profile) {
        try {
            JSONObject config = new JSONObject(profile.config);
            JSONArray outbounds = config.getJSONArray("outbounds");
            JSONObject settings = outbounds.getJSONObject(0).getJSONObject("settings");
            String address = settings.getString("address");
            int port = settings.getInt("port");
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), 3000);
            }
            return (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception error) {
            return Long.MAX_VALUE;
        }
    }

    private void configureSettings() {
        Switch bypassRu = findViewById(R.id.setting_bypass_ru);
        Switch bypassPrivate = findViewById(R.id.setting_bypass_private);
        Switch ipv6 = findViewById(R.id.setting_ipv6);
        bypassRu.setOnCheckedChangeListener((button, value) -> {
            appSettings.setBypassRu(value);
            settingsChanged();
        });
        bypassPrivate.setOnCheckedChangeListener((button, value) -> {
            appSettings.setBypassPrivate(value);
            settingsChanged();
        });
        ipv6.setOnCheckedChangeListener((button, value) -> {
            appSettings.setIpv6(value);
            settingsChanged();
        });
        findViewById(R.id.setting_dns).setOnClickListener(view -> chooseDns());
        renderSettings();
    }

    private void renderSettings() {
        ((Switch) findViewById(R.id.setting_bypass_ru)).setChecked(appSettings.bypassRu());
        ((Switch) findViewById(R.id.setting_bypass_private)).setChecked(appSettings.bypassPrivate());
        ((Switch) findViewById(R.id.setting_ipv6)).setChecked(appSettings.ipv6());
        ((TextView) findViewById(R.id.setting_dns)).setText(
            getString(R.string.dns_label, appSettings.dns())
        );
    }

    private void chooseDns() {
        String[] values = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};
        new AlertDialog.Builder(this)
            .setTitle("DNS")
            .setItems(values, (dialog, which) -> {
                appSettings.setDns(values[which]);
                renderSettings();
                settingsChanged();
            })
            .show();
    }

    private void settingsChanged() {
        if (H2RayVpnService.isRunning()) {
            Toast.makeText(this, R.string.reconnect_settings, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        importButton.setText(R.string.add_profile);
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
