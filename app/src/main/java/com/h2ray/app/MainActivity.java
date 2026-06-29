package com.h2ray.app;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.VpnService;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.h2ray.app.data.ProfileStore;
import com.h2ray.app.data.ConnectionStatusStore;
import com.h2ray.app.data.AppSettings;
import com.h2ray.app.data.SubscriptionStore;
import com.h2ray.app.network.PublicIpResolver;
import com.h2ray.app.network.UpdateChecker;
import com.h2ray.app.update.UpdateDownloadManager;
import com.h2ray.app.vpn.H2RayVpnService;
import com.h2ray.app.xray.ServerEndpoint;
import com.h2ray.app.xray.GeoDataManager;
import com.h2ray.app.xray.ProfileImporter;
import com.h2ray.app.xray.XrayBridge;
import com.h2ray.app.xray.XrayConfigFactory;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 100;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;
    private static final int FILE_IMPORT_REQUEST = 102;
    private static final int CAMERA_PERMISSION_REQUEST = 103;
    private static final int FILE_EXPORT_REQUEST = 104;
    private static final int APP_UNLOCK_REQUEST = 105;
    private static final int MAX_IMPORT_BYTES = 2 * 1024 * 1024;
    private static final String UPDATE_NOTIFICATION_CHANNEL = "h2ray_updates";
    private static final int UPDATE_NOTIFICATION_ID = 1002;
    private static final long UPDATE_CHECK_INTERVAL_MS = 30L * 60L * 1000L;

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
    private View rulesScreen;
    private View settingsScreen;
    private TextView statDownload;
    private TextView statUpload;
    private TextView statIp;
    private TextView statIpLabel;
    private TextView updateBadge;
    private Button updateButton;
    private UpdateDownloadManager updateDownloadManager;
    private final AtomicBoolean ipLookupRunning = new AtomicBoolean(false);
    private final AtomicBoolean updateCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean geoUpdateRunning = new AtomicBoolean(false);
    private volatile UpdateChecker.Result latestUpdate;
    private volatile long lastDirectIpAttempt;
    private volatile long lastProxyIpAttempt;
    private volatile long lastAutomaticUpdateCheckAt;
    private boolean waitingForInstallPermission;
    private boolean installerLaunched;
    private boolean profilesExpanded;
    private boolean profileAutomationExpanded;
    private boolean routingExpanded;
    private boolean connectionSettingsExpanded;
    private boolean diagnosticsExpanded;
    private boolean updatesExpanded;
    private boolean securityExpanded;
    private boolean appUnlocked;
    private boolean unlockRequested;
    private long lastPausedAt;
    private AnimatorSet homeAnimator;
    private String profileSearch = "";
    private boolean profileSorting;
    private String pendingProfileExport;
    private SubscriptionStore subscriptionStore;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (completedId != updateDownloadManager.downloadId()) {
                return;
            }
            UpdateDownloadManager.State state = updateDownloadManager.state();
            renderUpdateDownload(state);
            if (state.complete()) {
                prepareUpdateInstallation();
            } else if (state.failed()) {
                updateDownloadManager.clearFailedDownload();
                Toast.makeText(
                    MainActivity.this,
                    R.string.update_download_failed,
                    Toast.LENGTH_LONG
                ).show();
            }
        }
    };

    private final Runnable stateUpdater = new Runnable() {
        @Override
        public void run() {
            if (homeScreen != null && homeScreen.getVisibility() == View.VISIBLE) {
                render();
            }
            renderUpdateDownload(updateDownloadManager.state());
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable automaticUpdateChecker = new Runnable() {
        @Override
        public void run() {
            if (appSettings != null && appSettings.autoCheckUpdates()) {
                checkForUpdates(false);
            }
            handler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_H2Ray);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profileStore = new ProfileStore(this);
        connectionStatusStore = new ConnectionStatusStore(this);
        appSettings = new AppSettings(this);
        reconcileEmptyProfileState();
        if (!H2RayVpnService.isRunning()) {
            connectionStatusStore.setDirectIp("");
        }
        subscriptionStore = new SubscriptionStore(this);
        updateDownloadManager = new UpdateDownloadManager(this);
        connectionStatus = findViewById(R.id.connection_status);
        connectionError = findViewById(R.id.connection_error);
        profileName = findViewById(R.id.profile_name);
        profileDetails = findViewById(R.id.profile_details);
        connectButton = findViewById(R.id.connect_button);
        importButton = findViewById(R.id.import_button);
        profilesList = findViewById(R.id.profiles_list);
        homeScreen = findViewById(R.id.home_screen);
        profilesScreen = findViewById(R.id.profiles_screen);
        rulesScreen = findViewById(R.id.rules_screen);
        settingsScreen = findViewById(R.id.settings_screen);
        statDownload = findViewById(R.id.stat_download);
        statUpload = findViewById(R.id.stat_upload);
        statIp = findViewById(R.id.stat_ip);
        statIpLabel = findViewById(R.id.stat_ip_label);
        updateBadge = findViewById(R.id.update_badge);
        updateButton = findViewById(R.id.update_app);

        connectButton.setOnClickListener(view -> toggleConnection());
        importButton.setOnClickListener(view -> showImportDialog());
        connectionError.setOnClickListener(view -> showConnectionError());
        findViewById(R.id.profile_card).setOnClickListener(view -> showProfileActions());
        findViewById(R.id.header_menu_button).setOnClickListener(view -> showAppMenu());
        findViewById(R.id.three_xui_button).setOnClickListener(
            view -> startActivity(new Intent(this, ServerSetupActivity.class))
        );
        findViewById(R.id.nav_home).setOnClickListener(view -> showScreen("home"));
        findViewById(R.id.nav_profiles).setOnClickListener(view -> showScreen("profiles"));
        findViewById(R.id.nav_rules).setOnClickListener(view -> showScreen("rules"));
        findViewById(R.id.nav_settings).setOnClickListener(view -> showScreen("settings"));
        findViewById(R.id.add_profile).setOnClickListener(view -> showImportDialog());
        findViewById(R.id.ping_profiles).setOnClickListener(view -> pingProfiles(false));
        findViewById(R.id.profile_servers_tile).setOnClickListener(
            view -> toggleProfilesSection()
        );
        findViewById(R.id.profile_tools_tile).setOnClickListener(
            view -> toggleProfileAutomation()
        );
        findViewById(R.id.select_fastest_profile).setOnClickListener(
            view -> pingProfiles(true)
        );
        findViewById(R.id.check_profile_compatibility).setOnClickListener(
            view -> checkProfileCompatibility()
        );
        findViewById(R.id.sort_profiles).setOnClickListener(view -> {
            profileSorting = !profileSorting;
            renderProfiles();
        });
        ((EditText) findViewById(R.id.profile_search)).addTextChangedListener(
            new TextWatcher() {
                @Override public void beforeTextChanged(
                    CharSequence value, int start, int count, int after
                ) {
                }
                @Override public void onTextChanged(
                    CharSequence value, int start, int before, int count
                ) {
                    profileSearch = value == null ? "" : value.toString().trim();
                    renderProfiles();
                }
                @Override public void afterTextChanged(Editable value) {
                }
            }
        );
        findViewById(R.id.add_subscription).setOnClickListener(
            view -> showAddSubscriptionDialog()
        );
        findViewById(R.id.update_subscriptions).setOnClickListener(
            view -> updateSubscriptions(true)
        );
        findViewById(R.id.profiles_menu_button).setOnClickListener(
            view -> showProfilesMenu()
        );
        findViewById(R.id.open_logs).setOnClickListener(
            view -> startActivity(new Intent(this, LogsActivity.class))
        );
        findViewById(R.id.copy_diagnostic_report).setOnClickListener(
            view -> copyDiagnosticReport()
        );
        updateBadge.setOnClickListener(view -> openAvailableUpdate());
        updateButton.setOnClickListener(view -> handleUpdateButton());
        configureSettings();
        configureRules();
        configureNavigationLabels();
        applySystemBarInsets();
        createUpdateNotificationChannel();
        requestNotificationPermissionIfNeeded();
        registerDownloadReceiver();
        render();
        renderUpdateDownload(updateDownloadManager.state());
        resumeVpnAfterInstalledUpdate();
        discardInstalledUpdate();
        if (updateDownloadManager.state().complete()) {
            handler.postDelayed(this::prepareUpdateInstallation, 600);
        }
        if (appSettings.autoCheckUpdates()) {
            checkForUpdates(false);
        }
        updateSubscriptions(false);
        checkGeoDataUpdate();
        startHomeAnimations();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastPausedAt > 0 && SystemClock.elapsedRealtime() - lastPausedAt > 30_000) {
            appUnlocked = false;
        }
        requestAppUnlockIfNeeded();
        handler.post(stateUpdater);
        handler.removeCallbacks(automaticUpdateChecker);
        handler.post(automaticUpdateChecker);
        if (homeScreen.getVisibility() == View.VISIBLE) {
            startHomeAnimations();
        }
        renderSettings();
        if (waitingForInstallPermission
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = false;
            prepareUpdateInstallation();
        } else if (installerLaunched) {
            installerLaunched = false;
            handler.postDelayed(this::resumeVpnAfterCancelledInstall, 800);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(stateUpdater);
        handler.removeCallbacks(automaticUpdateChecker);
        lastPausedAt = SystemClock.elapsedRealtime();
        stopHomeAnimations();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toggleConnection() {
        if (H2RayVpnService.isBusy()) {
            return;
        }
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
        IntentResult qrResult = IntentIntegrator.parseActivityResult(
            requestCode,
            resultCode,
            data
        );
        if (qrResult != null) {
            if (qrResult.getContents() == null) {
                Toast.makeText(this, R.string.qr_cancelled, Toast.LENGTH_SHORT).show();
            } else {
                importExternalContent(qrResult.getContents(), "QR");
            }
            return;
        }
        if (requestCode == FILE_IMPORT_REQUEST && resultCode == RESULT_OK
            && data != null && data.getData() != null) {
            importDocument(data.getData());
            return;
        }
        if (requestCode == FILE_EXPORT_REQUEST && resultCode == RESULT_OK
            && data != null && data.getData() != null && pendingProfileExport != null) {
            writeProfileExport(data.getData(), pendingProfileExport);
            pendingProfileExport = null;
            return;
        }
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            startVpn();
            return;
        }
        if (requestCode == APP_UNLOCK_REQUEST) {
            unlockRequested = false;
            if (resultCode == RESULT_OK) {
                appUnlocked = true;
            } else {
                finish();
            }
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
                List<ProfileImporter.ProfileData> profiles =
                    ProfileImporter.importContent(trimmed, "JSON profile");
                saveImportedProfiles(profiles);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    render();
                    renderProfiles();
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_imported, profiles.size()),
                        Toast.LENGTH_SHORT
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    inputError(error.getMessage());
                });
            }
        });
    }

    private void showProfilesMenu() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.profiles_menu)
            .setItems(new String[] {
                getString(R.string.import_file),
                getString(R.string.scan_qr)
            }, (dialog, which) -> {
                if (which == 0) {
                    openConfigFile();
                } else {
                    scanQrCode();
                }
            })
            .show();
    }

    private void openConfigFile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[] {"text/plain", "application/json", "text/json"}
            );
        startActivityForResult(
            Intent.createChooser(picker, getString(R.string.select_config_file)),
            FILE_IMPORT_REQUEST
        );
    }

    private void scanQrCode() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] {Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST
            );
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner() {
        try {
            new IntentIntegrator(this)
                .setCaptureActivity(QrCaptureActivity.class)
                .setDesiredBarcodeFormats(Collections.singleton("QR_CODE"))
                .setPrompt(getString(R.string.qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .addExtra("SCAN_WIDTH", dp(280))
                .addExtra("SCAN_HEIGHT", dp(280))
                .initiateScan();
        } catch (RuntimeException error) {
            Toast.makeText(
                this,
                R.string.qr_scanner_unavailable,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            renderSettings();
            if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && latestUpdate != null
                && latestUpdate.updateAvailable) {
                showUpdateNotification(latestUpdate);
            }
            return;
        }
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner();
        } else {
            Toast.makeText(
                this,
                R.string.camera_permission_required,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void importDocument(Uri uri) {
        executor.execute(() -> {
            try {
                String fileName = documentName(uri);
                String content = readDocument(uri);
                List<ProfileImporter.ProfileData> profiles =
                    ProfileImporter.importContent(content, fileName);
                saveImportedProfiles(profiles);
                runOnUiThread(() -> {
                    render();
                    renderProfiles();
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_imported, profiles.size()),
                        Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> inputError(
                    error.getMessage() == null
                        ? getString(R.string.file_read_failed)
                        : error.getMessage()
                ));
            }
        });
    }

    private void importExternalContent(String content, String fallbackName) {
        executor.execute(() -> {
            try {
                List<ProfileImporter.ProfileData> profiles =
                    ProfileImporter.importContent(content, fallbackName);
                saveImportedProfiles(profiles);
                runOnUiThread(() -> {
                    render();
                    renderProfiles();
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_imported, profiles.size()),
                        Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> inputError(error.getMessage()));
            }
        });
    }

    private void saveImportedProfiles(List<ProfileImporter.ProfileData> profiles) {
        for (ProfileImporter.ProfileData profile : profiles) {
            profileStore.saveActiveProfile(
                profile.name,
                profile.protocol,
                profile.config,
                profile.source
            );
        }
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.replaceFirst("(?i)\\.json$", "");
                }
            }
        } catch (Exception ignored) {
        }
        return "JSON profile";
    }

    private String readDocument(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalArgumentException(getString(R.string.file_read_failed));
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMPORT_BYTES) {
                    throw new IllegalArgumentException("Файл превышает допустимые 2 МБ");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
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
                    reconcileEmptyProfileState();
                    render();
                }
            })
            .show();
    }

    private void showAppMenu() {
        String[] actions = {
            getString(R.string.menu_reconnect),
            getString(R.string.menu_refresh_ip),
            getString(R.string.menu_logs),
            getString(R.string.menu_about)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    reconnect();
                } else if (which == 1) {
                    resolvePublicIp(true);
                } else if (which == 2) {
                    startActivity(new Intent(this, LogsActivity.class));
                } else {
                    showAboutDialog();
                }
            })
            .show();
    }

    private void showAboutDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(22), dp(24), dp(22), dp(14));
        panel.setBackgroundResource(R.drawable.bg_card);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.h2ray_logo);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextView title = new TextView(this);
        title.setText("H2Ray Client · v" + currentVersion());
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(10);
        titleParams.bottomMargin = dp(14);
        panel.addView(title, titleParams);

        panel.addView(aboutLink(
            R.string.about_developer,
            "https://github.com/SelfC0de"
        ));
        panel.addView(aboutLink(
            R.string.about_vk,
            "https://vk.com/selfcode_dev"
        ));
        panel.addView(aboutLink(
            R.string.about_telegram,
            "https://t.me/selfcode_dev"
        ));
        panel.addView(aboutLink(
            R.string.about_github,
            "https://github.com/SelfC0de"
        ));

        new AlertDialog.Builder(this)
            .setView(panel)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private TextView aboutLink(int label, String url) {
        TextView link = new TextView(this);
        link.setText(label);
        link.setTextColor(getColor(R.color.accent));
        link.setTextSize(14);
        link.setGravity(android.view.Gravity.CENTER_VERTICAL);
        link.setPadding(dp(16), 0, dp(16), 0);
        link.setBackgroundResource(R.drawable.bg_stat);
        link.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_view, 0);
        link.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException error) {
                Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        );
        params.bottomMargin = dp(8);
        link.setLayoutParams(params);
        return link;
    }

    private void checkForUpdates(boolean userInitiated) {
        UpdateChecker.Result cached = latestUpdate;
        if (userInitiated && cached != null && cached.updateAvailable) {
            openAvailableUpdate();
            return;
        }
        if (!updateCheckRunning.compareAndSet(false, true)) {
            return;
        }
        if (!userInitiated) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastAutomaticUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) {
                updateCheckRunning.set(false);
                return;
            }
            lastAutomaticUpdateCheckAt = now;
        }
        if (userInitiated) {
            Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            UpdateChecker.Result result = UpdateChecker.check(this);
            latestUpdate = result;
            updateCheckRunning.set(false);
            runOnUiThread(() -> {
                updateBadge.setVisibility(result.updateAvailable ? View.VISIBLE : View.GONE);
                if (result.updateAvailable) {
                    showUpdateNotification(result);
                } else if (!result.failed) {
                    NotificationManager manager =
                        getSystemService(NotificationManager.class);
                    if (manager != null) {
                        manager.cancel(UPDATE_NOTIFICATION_ID);
                    }
                }
                if (!userInitiated) {
                    return;
                }
                if (result.updateAvailable) {
                    openAvailableUpdate();
                } else {
                    Toast.makeText(
                        this,
                        result.failed ? R.string.update_check_failed : R.string.no_update,
                        Toast.LENGTH_SHORT
                    ).show();
                }
            });
        });
    }

    private void createUpdateNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL,
            "Обновления H2Ray",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Уведомления о новых обязательных версиях H2Ray");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void showUpdateNotification(UpdateChecker.Result update) {
        if (!canDeliverUpdateNotification()) {
            return;
        }
        android.content.SharedPreferences notifications = getSharedPreferences(
            "h2ray_update_notifications",
            MODE_PRIVATE
        );
        if (update.latestVersion.equals(
            notifications.getString("notified_version", "")
        )) {
            return;
        }
        PendingIntent openApp = PendingIntent.getActivity(
            this,
            20,
            new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, UPDATE_NOTIFICATION_CHANNEL)
            : new Notification.Builder(this);
        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Доступно обновление H2Ray")
            .setContentText("Версия " + update.latestVersion + " готова к установке")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(UPDATE_NOTIFICATION_ID, builder.build());
        } catch (RuntimeException error) {
            return;
        }
        notifications.edit()
            .putString("notified_version", update.latestVersion)
            .apply();
    }

    private boolean canDeliverUpdateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                manager.getNotificationChannel(UPDATE_NOTIFICATION_CHANNEL);
            return channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    private void openUpdateNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }
        Intent settingsIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settingsIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, UPDATE_NOTIFICATION_CHANNEL);
        } else {
            settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        }
        try {
            startActivity(settingsIntent);
        } catch (RuntimeException error) {
            startActivity(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
                )
            );
        }
    }

    private void openAvailableUpdate() {
        UpdateChecker.Result update = latestUpdate;
        if (update == null || !update.updateAvailable || update.apkUrl.isEmpty()) {
            checkForUpdates(true);
            return;
        }
        try {
            updateDownloadManager.start(update);
            renderUpdateDownload(updateDownloadManager.state());
        } catch (Exception error) {
            Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleUpdateButton() {
        UpdateDownloadManager.State state = updateDownloadManager.state();
        if (state.complete()) {
            prepareUpdateInstallation();
        } else if (!state.downloading()) {
            checkForUpdates(true);
        }
    }

    private void renderUpdateDownload(UpdateDownloadManager.State state) {
        if (updateButton == null) {
            return;
        }
        if (state.complete()) {
            updateButton.setText(R.string.update_install);
            updateButton.setEnabled(true);
        } else if (state.downloading()) {
            updateButton.setText(
                state.percent >= 0
                    ? getString(R.string.update_downloading, state.percent)
                    : getString(R.string.update_waiting_network)
            );
            updateButton.setEnabled(false);
        } else {
            updateButton.setText(R.string.update_app);
            updateButton.setEnabled(true);
        }
    }

    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private void prepareUpdateInstallation() {
        if (!updateDownloadManager.state().complete()) {
            return;
        }
        if (!verifyDownloadedUpdate()) {
            updateDownloadManager.discardInstalledDownload();
            renderUpdateDownload(updateDownloadManager.state());
            Toast.makeText(
                this,
                R.string.update_verification_failed,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = true;
            Toast.makeText(this, R.string.update_permission, Toast.LENGTH_LONG).show();
            startActivity(new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())
            ));
            return;
        }

        boolean reconnect = (H2RayVpnService.isRunning() || H2RayVpnService.isBusy())
            && profileStore.hasActiveProfile();
        updateDownloadManager.markReconnectRequired(reconnect);
        if (H2RayVpnService.isRunning() || H2RayVpnService.isBusy()) {
            startService(H2RayVpnService.stopIntent(this));
            waitForVpnStopAndInstall(0);
        } else {
            launchSystemInstaller();
        }
    }

    private boolean verifyDownloadedUpdate() {
        File apk = updateDownloadManager.downloadedFile();
        if (apk == null || !apk.isFile() || apk.length() == 0) {
            return false;
        }
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(
                getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            PackageInfo archive = getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            if (archive == null
                || !getPackageName().equals(archive.packageName)
                || archive.versionName == null
                || !archive.versionName.equals(updateDownloadManager.targetVersion())
                || installed.signingInfo == null
                || archive.signingInfo == null) {
                return false;
            }
            Signature[] installedSigners = installed.signingInfo.getApkContentsSigners();
            Signature[] archiveSigners = archive.signingInfo.getApkContentsSigners();
            if (installedSigners.length != archiveSigners.length) {
                return false;
            }
            for (Signature installedSigner : installedSigners) {
                boolean found = false;
                for (Signature archiveSigner : archiveSigners) {
                    if (installedSigner.equals(archiveSigner)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void waitForVpnStopAndInstall(int attempt) {
        if ((!H2RayVpnService.isRunning() && !H2RayVpnService.isBusy()) || attempt >= 12) {
            launchSystemInstaller();
            return;
        }
        handler.postDelayed(() -> waitForVpnStopAndInstall(attempt + 1), 250);
    }

    private void launchSystemInstaller() {
        Uri apkUri = updateDownloadManager.downloadedUri();
        if (apkUri == null) {
            Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installerLaunched = true;
            startActivity(install);
        } catch (Exception error) {
            installerLaunched = false;
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void resumeVpnAfterInstalledUpdate() {
        if (!updateDownloadManager.reconnectRequired()) {
            return;
        }
        if (!currentVersion().equals(updateDownloadManager.targetVersion())) {
            return;
        }
        updateDownloadManager.clearReconnectRequired();
        handler.postDelayed(this::startVpnAfterUpdate, 1000);
    }

    private void discardInstalledUpdate() {
        String target = updateDownloadManager.targetVersion();
        if (!target.isEmpty() && currentVersion().equals(target)) {
            updateDownloadManager.discardInstalledDownload();
            renderUpdateDownload(updateDownloadManager.state());
        }
    }

    private void resumeVpnAfterCancelledInstall() {
        if (!updateDownloadManager.reconnectRequired()
            || currentVersion().equals(updateDownloadManager.targetVersion())) {
            return;
        }
        updateDownloadManager.clearReconnectRequired();
        startVpnAfterUpdate();
    }

    private void startVpnAfterUpdate() {
        if (!profileStore.hasActiveProfile() || H2RayVpnService.isRunning()
            || H2RayVpnService.isBusy()) {
            return;
        }
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent == null) {
            startVpn();
        }
    }

    @SuppressWarnings("deprecation")
    private String currentVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "" : version;
        } catch (Exception error) {
            return "";
        }
    }

    private void reconnect() {
        if (!profileStore.hasActiveProfile()) {
            showImportDialog();
            return;
        }
        if (H2RayVpnService.isRunning() || H2RayVpnService.isBusy()) {
            startService(H2RayVpnService.stopIntent(this));
            handler.postDelayed(this::startVpn, 1200);
        } else {
            toggleConnection();
        }
    }

    private void showScreen(String target) {
        if (!"profiles".equals(target)) {
            if (profilesExpanded) {
                toggleProfilesSection();
            }
            if (profileAutomationExpanded) {
                toggleProfileAutomation();
            }
        }
        if (!"rules".equals(target) && routingExpanded) {
            findViewById(R.id.routing_header).performClick();
        }
        if (!"settings".equals(target)) {
            closeOtherSettingsPanels("");
        }
        homeScreen.setVisibility("home".equals(target) ? View.VISIBLE : View.GONE);
        profilesScreen.setVisibility("profiles".equals(target) ? View.VISIBLE : View.GONE);
        rulesScreen.setVisibility("rules".equals(target) ? View.VISIBLE : View.GONE);
        settingsScreen.setVisibility("settings".equals(target) ? View.VISIBLE : View.GONE);
        setNavColor(R.id.nav_home, "home".equals(target));
        setNavColor(R.id.nav_profiles, "profiles".equals(target));
        setNavColor(R.id.nav_rules, "rules".equals(target));
        setNavColor(R.id.nav_settings, "settings".equals(target));
        if ("home".equals(target)) {
            startHomeAnimations();
        } else {
            stopHomeAnimations();
            View targetView = "profiles".equals(target)
                ? profilesScreen
                : "rules".equals(target) ? rulesScreen : settingsScreen;
            targetView.setAlpha(0f);
            targetView.setTranslationX(dp(10));
            targetView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(240)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        }
        if ("profiles".equals(target)) {
            renderProfiles();
        }
        if ("settings".equals(target)) {
            renderSettings();
        }
        if ("rules".equals(target)) {
            renderRules();
        }
    }

    private void startHomeAnimations() {
        if (homeAnimator != null && homeAnimator.isRunning()) {
            return;
        }
        homeScreen.setAlpha(0f);
        homeScreen.setTranslationY(dp(8));
        homeScreen.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .start();

        View logo = findViewById(R.id.home_logo);
        ObjectAnimator pulseX = ObjectAnimator.ofFloat(
            connectButton,
            View.SCALE_X,
            1f,
            1.025f
        );
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(
            connectButton,
            View.SCALE_Y,
            1f,
            1.025f
        );
        ObjectAnimator logoGlow = ObjectAnimator.ofFloat(logo, View.ALPHA, 0.72f, 1f);
        for (ObjectAnimator animator : new ObjectAnimator[] {pulseX, pulseY, logoGlow}) {
            animator.setDuration(1800);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        homeAnimator = new AnimatorSet();
        homeAnimator.playTogether(pulseX, pulseY, logoGlow);
        homeAnimator.start();

        View profile = findViewById(R.id.profile_card);
        profile.setAlpha(0f);
        profile.setTranslationY(dp(10));
        profile.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(100)
            .setDuration(360)
            .start();
    }

    private void stopHomeAnimations() {
        homeScreen.animate().cancel();
        findViewById(R.id.profile_card).animate().cancel();
        if (homeAnimator != null) {
            homeAnimator.cancel();
            homeAnimator = null;
        }
        connectButton.setScaleX(1f);
        connectButton.setScaleY(1f);
        findViewById(R.id.home_logo).setAlpha(1f);
    }

    private void setNavColor(int id, boolean active) {
        ((TextView) findViewById(id)).setTextColor(
            getColor(active ? R.color.accent : R.color.text_secondary)
        );
    }

    private void renderProfiles() {
        profilesList.removeAllViews();
        List<ProfileStore.Profile> allProfiles = profileStore.getProfiles();
        List<ProfileStore.Profile> profiles = new ArrayList<>();
        String query = profileSearch.toLowerCase(Locale.ROOT);
        for (ProfileStore.Profile profile : allProfiles) {
            String searchable =
                (profile.name + " " + profile.protocol + " " + profile.group)
                    .toLowerCase(Locale.ROOT);
            if (query.isEmpty() || searchable.contains(query)) {
                profiles.add(profile);
            }
        }
        if (profileSorting) {
            profiles.sort(
                Comparator.comparing((ProfileStore.Profile item) -> !item.favorite)
                    .thenComparingLong(item ->
                        item.ping < 0 || item.ping == Long.MAX_VALUE
                            ? Long.MAX_VALUE
                            : item.ping)
                    .thenComparing(item -> item.name.toLowerCase(Locale.ROOT))
            );
        }
        ((TextView) findViewById(R.id.profile_servers_tile)).setText(
            getString(R.string.category_servers, allProfiles.size())
        );
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
            String favorite = profile.favorite ? "★ " : "";
            String group = profile.group.trim().isEmpty() ? "" : "\n     " + profile.group;
            String ping = profile.ping < 0
                ? profile.ping == -2 ? getString(R.string.ping_checking) : "—"
                : profile.ping == Long.MAX_VALUE ? getString(R.string.ping_failed) : profile.ping + " ms";
            ServerEndpoint endpoint = ServerEndpoint.fromConfig(profile.config);
            String server = endpoint == null ? profile.protocol : endpoint.displayName();
            row.setText(
                marker + favorite + profile.name + "\n     "
                    + profile.protocol + "  ·  " + server + "  ·  " + ping + group
            );
            row.setTextColor(getColor(R.color.text_primary));
            row.setTextSize(14);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(16, 10, 16, 10);
            row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(profile.group.trim().isEmpty() ? 72 : 88)
            );
            params.bottomMargin = dp(10);
            row.setLayoutParams(params);
            row.setOnClickListener(view -> {
                profileStore.select(profile.id);
                render();
                renderProfiles();
            });
            row.setOnLongClickListener(view -> {
                showProfileManagement(profile);
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
                reconcileEmptyProfileState();
                render();
                renderProfiles();
            })
            .show();
    }

    private void showProfileManagement(ProfileStore.Profile profile) {
        String[] actions = {
            getString(R.string.edit_profile),
            getString(R.string.toggle_favorite),
            getString(R.string.export_profile_file),
            getString(R.string.export_profile_qr),
            getString(R.string.delete_profile)
        };
        new AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    showEditProfileDialog(profile);
                } else if (which == 1) {
                    profileStore.toggleFavorite(profile.id);
                    renderProfiles();
                } else if (which == 2) {
                    exportProfileFile(profile);
                } else if (which == 3) {
                    showProfileQr(profile);
                } else {
                    confirmDeleteProfile(profile);
                }
            })
            .show();
    }

    private void showEditProfileDialog(ProfileStore.Profile profile) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint(R.string.profile_name_label);
        name.setText(profile.name);
        EditText group = new EditText(this);
        group.setHint(R.string.profile_group);
        group.setText(profile.group);
        EditText config = new EditText(this);
        config.setHint(R.string.import_hint);
        config.setMinLines(6);
        config.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        config.setText(profile.source.trim().isEmpty() ? profile.config : profile.source);
        content.addView(name);
        content.addView(group);
        content.addView(config);
        new AlertDialog.Builder(this)
            .setTitle(R.string.edit_profile)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    List<ProfileImporter.ProfileData> parsed = ProfileImporter.importContent(
                        config.getText().toString(),
                        name.getText().toString()
                    );
                    if (parsed.size() != 1) {
                        throw new IllegalArgumentException("Для редактирования нужен один профиль");
                    }
                    ProfileImporter.ProfileData data = parsed.get(0);
                    profileStore.update(
                        profile.id,
                        name.getText().toString().trim().isEmpty()
                            ? data.name
                            : name.getText().toString().trim(),
                        data.config,
                        data.source,
                        group.getText().toString().trim()
                    );
                    render();
                    renderProfiles();
                    Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    inputError(error.getMessage());
                }
            })
            .show();
    }

    private String exportContent(ProfileStore.Profile profile) {
        return profile.source == null || profile.source.trim().isEmpty()
            ? profile.config
            : profile.source;
    }

    private void exportProfileFile(ProfileStore.Profile profile) {
        pendingProfileExport = exportContent(profile);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, profile.name.replaceAll("[^\\p{L}\\p{N}._-]", "_") + ".txt");
        startActivityForResult(intent, FILE_EXPORT_REQUEST);
    }

    private void writeProfileExport(Uri uri, String content) {
        executor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) {
                    throw new IllegalStateException("Не удалось открыть файл");
                }
                output.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                runOnUiThread(() -> inputError(error.getMessage()));
            }
        });
    }

    private void showProfileQr(ProfileStore.Profile profile) {
        try {
            Bitmap bitmap = new BarcodeEncoder().encodeBitmap(
                exportContent(profile),
                BarcodeFormat.QR_CODE,
                dp(300),
                dp(300)
            );
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setPadding(dp(12), dp(12), dp(12), dp(12));
            new AlertDialog.Builder(this)
                .setTitle(profile.name)
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Exception error) {
            inputError(error.getMessage());
        }
    }

    private void showAddSubscriptionDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.subscription_url);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        new AlertDialog.Builder(this)
            .setTitle(R.string.add_subscription)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    subscriptionStore.add(input.getText().toString(), 24);
                    Toast.makeText(this, R.string.subscription_added, Toast.LENGTH_SHORT).show();
                    updateSubscriptions(true);
                } catch (Exception error) {
                    inputError(error.getMessage());
                }
            })
            .show();
    }

    private void updateSubscriptions(boolean force) {
        if (subscriptionStore.urls().isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                int count = subscriptionStore.updateDue(profileStore, force);
                runOnUiThread(() -> {
                    render();
                    renderProfiles();
                    if (force) {
                        Toast.makeText(
                            this,
                            getString(R.string.subscription_updated, count),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
            } catch (Exception error) {
                if (force) {
                    runOnUiThread(() -> inputError(error.getMessage()));
                }
            }
        });
    }

    private void pingProfiles(boolean selectFastest) {
        if (H2RayVpnService.isRunning()) {
            Toast.makeText(this, R.string.ping_requires_disconnect, Toast.LENGTH_LONG).show();
            return;
        }
        List<ProfileStore.Profile> profiles = profileStore.getProfiles();
        if (profiles.isEmpty()) {
            return;
        }
        for (ProfileStore.Profile profile : profiles) {
            profileStore.updatePing(profile.id, -2);
        }
        renderProfiles();
        executor.execute(() -> {
            ProfileStore.Profile fastest = null;
            long fastestPing = Long.MAX_VALUE;
            for (ProfileStore.Profile profile : profiles) {
                long ping = measurePing(profile);
                profileStore.updatePing(profile.id, ping);
                if (ping < fastestPing) {
                    fastest = profile;
                    fastestPing = ping;
                }
                runOnUiThread(this::renderProfiles);
            }
            ProfileStore.Profile selected = fastest;
            long selectedPing = fastestPing;
            runOnUiThread(() -> {
                if (!selectFastest) {
                    return;
                }
                if (selected == null || selectedPing == Long.MAX_VALUE) {
                    Toast.makeText(
                        this,
                        R.string.no_available_servers,
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }
                profileStore.select(selected.id);
                render();
                renderProfiles();
                Toast.makeText(
                    this,
                    getString(R.string.fastest_selected, selected.name, selectedPing),
                    Toast.LENGTH_LONG
                ).show();
            });
        });
    }

    private void toggleProfilesSection() {
        if (!profilesExpanded && profileAutomationExpanded) {
            toggleProfileAutomation();
        }
        profilesExpanded = !profilesExpanded;
        setTileActive(R.id.profile_servers_tile, profilesExpanded);
        findViewById(R.id.profiles_list_scroll).setVisibility(
            profilesExpanded ? View.VISIBLE : View.GONE
        );
        findViewById(R.id.add_profile).setVisibility(
            profilesExpanded ? View.VISIBLE : View.GONE
        );
        findViewById(R.id.ping_profiles).setVisibility(
            profilesExpanded ? View.VISIBLE : View.GONE
        );
        renderProfiles();
    }

    private void toggleProfileAutomation() {
        if (!profileAutomationExpanded && profilesExpanded) {
            toggleProfilesSection();
        }
        profileAutomationExpanded = !profileAutomationExpanded;
        setTileActive(R.id.profile_tools_tile, profileAutomationExpanded);
        findViewById(R.id.profile_automation_panel).setVisibility(
            profileAutomationExpanded ? View.VISIBLE : View.GONE
        );
        ((TextView) findViewById(R.id.profile_automation_header)).setText(
            profileAutomationExpanded
                ? R.string.profile_automation_expanded
                : R.string.profile_automation_collapsed
        );
    }

    private void checkProfileCompatibility() {
        ProfileStore.Profile profile = profileStore.getActiveProfile();
        if (profile == null) {
            Toast.makeText(this, R.string.no_profile, Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            try {
                XrayConfigFactory.createRuntimeConfig(profile.config, appSettings);
                runOnUiThread(() -> Toast.makeText(
                    this,
                    getString(R.string.compatibility_ok, XrayBridge.version()),
                    Toast.LENGTH_LONG
                ).show());
            } catch (Exception error) {
                runOnUiThread(() -> inputError(error.getMessage()));
            }
        });
    }

    private long measurePing(ProfileStore.Profile profile) {
        try {
            ServerEndpoint endpoint = ServerEndpoint.fromConfig(profile.config);
            if (endpoint == null) {
                return Long.MAX_VALUE;
            }
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.address, endpoint.port), 3500);
            }
            return (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception error) {
            return Long.MAX_VALUE;
        }
    }

    private void configureSettings() {
        Switch ipv6 = findViewById(R.id.setting_ipv6);
        Switch parallelDns = findViewById(R.id.setting_parallel_dns);
        Switch fakeDns = findViewById(R.id.setting_fake_dns);
        Switch happyEyeballs = findViewById(R.id.setting_happy_eyeballs);
        Switch autoReconnect = findViewById(R.id.setting_auto_reconnect);
        Switch restoreBoot = findViewById(R.id.setting_restore_boot);
        Switch autoCheckUpdates = findViewById(R.id.setting_auto_check_updates);
        Switch appLock = findViewById(R.id.setting_app_lock);
        ipv6.setOnCheckedChangeListener((button, value) -> {
            appSettings.setIpv6(value);
            happyEyeballs.setEnabled(value);
            settingsChanged();
        });
        parallelDns.setOnCheckedChangeListener((button, value) -> {
            appSettings.setParallelDns(value);
            settingsChanged();
        });
        fakeDns.setOnCheckedChangeListener((button, value) -> {
            appSettings.setFakeDns(value);
            settingsChanged();
        });
        happyEyeballs.setOnCheckedChangeListener((button, value) -> {
            appSettings.setHappyEyeballs(value);
            settingsChanged();
        });
        autoReconnect.setOnCheckedChangeListener((button, value) ->
            appSettings.setAutoReconnect(value));
        restoreBoot.setOnCheckedChangeListener((button, value) ->
            appSettings.setRestoreAfterBoot(value));
        autoCheckUpdates.setOnCheckedChangeListener((button, value) -> {
            appSettings.setAutoCheckUpdates(value);
            if (value) {
                lastAutomaticUpdateCheckAt = 0;
                checkForUpdates(false);
            } else {
                NotificationManager manager =
                    getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.cancel(UPDATE_NOTIFICATION_ID);
                }
            }
        });
        appLock.setOnCheckedChangeListener((button, value) -> {
            if (value == appSettings.appLock()) {
                return;
            }
            if (!value) {
                appSettings.setAppLock(false);
                return;
            }
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard == null || !keyguard.isDeviceSecure()) {
                button.setChecked(false);
                Toast.makeText(this, R.string.device_lock_required, Toast.LENGTH_LONG).show();
                return;
            }
            appSettings.setAppLock(true);
            appUnlocked = true;
        });
        findViewById(R.id.setting_dns).setOnClickListener(view -> chooseDns());
        findViewById(R.id.setting_mtu).setOnClickListener(view -> chooseMtu());
        findViewById(R.id.setting_retry_policy).setOnClickListener(
            view -> chooseRetryPolicy()
        );
        findViewById(R.id.connection_settings_header).setOnClickListener(
            view -> toggleConnectionSettings()
        );
        findViewById(R.id.connection_tile).setOnClickListener(
            view -> toggleConnectionSettings()
        );
        findViewById(R.id.diagnostics_header).setOnClickListener(
            view -> toggleDiagnostics()
        );
        findViewById(R.id.diagnostics_tile).setOnClickListener(
            view -> toggleDiagnostics()
        );
        findViewById(R.id.updates_header).setOnClickListener(
            view -> toggleUpdates()
        );
        findViewById(R.id.updates_tile).setOnClickListener(
            view -> toggleUpdates()
        );
        findViewById(R.id.update_notification_status).setOnClickListener(
            view -> openUpdateNotificationSettings()
        );
        findViewById(R.id.open_developer_site).setOnClickListener(view -> {
            try {
                startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://selfc0de.github.io/")
                ));
            } catch (RuntimeException error) {
                Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.security_header).setOnClickListener(
            view -> toggleSecurity()
        );
        findViewById(R.id.security_tile).setOnClickListener(
            view -> toggleSecurity()
        );
        renderSettings();
    }

    private void renderSettings() {
        ((Switch) findViewById(R.id.setting_ipv6)).setChecked(appSettings.ipv6());
        ((Switch) findViewById(R.id.setting_parallel_dns)).setChecked(
            appSettings.parallelDns()
        );
        ((Switch) findViewById(R.id.setting_fake_dns)).setChecked(appSettings.fakeDns());
        Switch happyEyeballs = findViewById(R.id.setting_happy_eyeballs);
        happyEyeballs.setChecked(appSettings.happyEyeballs());
        happyEyeballs.setEnabled(appSettings.ipv6());
        ((Switch) findViewById(R.id.setting_auto_reconnect)).setChecked(
            appSettings.autoReconnect()
        );
        ((Switch) findViewById(R.id.setting_restore_boot)).setChecked(
            appSettings.restoreAfterBoot()
        );
        ((Switch) findViewById(R.id.setting_auto_check_updates)).setChecked(
            appSettings.autoCheckUpdates()
        );
        ((Switch) findViewById(R.id.setting_app_lock)).setChecked(appSettings.appLock());
        TextView notificationStatus = findViewById(R.id.update_notification_status);
        notificationStatus.setText(
            canDeliverUpdateNotification()
                ? R.string.notification_delivery_ready
                : R.string.notification_delivery_blocked
        );
        notificationStatus.setTextColor(getColor(
            canDeliverUpdateNotification() ? R.color.success : R.color.update_border
        ));
        ((TextView) findViewById(R.id.setting_dns)).setText(
            getString(R.string.dns_label, appSettings.xrayDns())
        );
        ((TextView) findViewById(R.id.setting_mtu)).setText(
            getString(R.string.mtu_label, appSettings.mtu())
        );
        ((TextView) findViewById(R.id.setting_retry_policy)).setText(
            getString(
                R.string.retry_policy,
                appSettings.retryCount(),
                appSettings.connectionTimeoutSeconds()
            )
        );
    }

    private void chooseRetryPolicy() {
        String[] labels = {
            "Быстро: 2 попытки, 5 сек.",
            "Обычно: 3 попытки, 8 сек.",
            "Медленная сеть: 5 попыток, 15 сек."
        };
        int[] attempts = {2, 3, 5};
        int[] timeouts = {5, 8, 15};
        new AlertDialog.Builder(this)
            .setTitle(R.string.retry_policy_title)
            .setItems(labels, (dialog, which) -> {
                appSettings.setRetryCount(attempts[which]);
                appSettings.setConnectionTimeoutSeconds(timeouts[which]);
                renderSettings();
            })
            .show();
    }

    private void toggleConnectionSettings() {
        if (!connectionSettingsExpanded) {
            closeOtherSettingsPanels("connection");
        }
        connectionSettingsExpanded = !connectionSettingsExpanded;
        setTileActive(R.id.connection_tile, connectionSettingsExpanded);
        setViewsVisible(connectionSettingsExpanded,
            R.id.setting_ipv6,
            R.id.setting_dns,
            R.id.setting_mtu,
            R.id.setting_parallel_dns,
            R.id.setting_fake_dns,
            R.id.setting_happy_eyeballs,
            R.id.setting_auto_reconnect,
            R.id.setting_restore_boot,
            R.id.setting_retry_policy);
        ((TextView) findViewById(R.id.connection_settings_header)).setText(
            connectionSettingsExpanded
                ? R.string.connection_expanded
                : R.string.connection_collapsed
        );
    }

    private void toggleDiagnostics() {
        if (!diagnosticsExpanded) {
            closeOtherSettingsPanels("diagnostics");
        }
        diagnosticsExpanded = !diagnosticsExpanded;
        setTileActive(R.id.diagnostics_tile, diagnosticsExpanded);
        setViewsVisible(
            diagnosticsExpanded,
            R.id.open_logs,
            R.id.copy_diagnostic_report
        );
        ((TextView) findViewById(R.id.diagnostics_header)).setText(
            diagnosticsExpanded
                ? R.string.diagnostics_expanded
                : R.string.diagnostics_collapsed
        );
    }

    private void toggleUpdates() {
        if (!updatesExpanded) {
            closeOtherSettingsPanels("updates");
        }
        updatesExpanded = !updatesExpanded;
        setTileActive(R.id.updates_tile, updatesExpanded);
        setViewsVisible(
            updatesExpanded,
            R.id.update_app,
            R.id.setting_auto_check_updates,
            R.id.update_notification_status,
            R.id.open_developer_site
        );
        ((TextView) findViewById(R.id.updates_header)).setText(
            updatesExpanded ? R.string.updates_expanded : R.string.updates_collapsed
        );
    }

    private void toggleSecurity() {
        if (!securityExpanded) {
            closeOtherSettingsPanels("security");
        }
        securityExpanded = !securityExpanded;
        setTileActive(R.id.security_tile, securityExpanded);
        setViewsVisible(securityExpanded, R.id.setting_app_lock);
        ((TextView) findViewById(R.id.security_header)).setText(
            securityExpanded ? R.string.security_expanded : R.string.security_collapsed
        );
    }

    private void requestAppUnlockIfNeeded() {
        if (!appSettings.appLock() || appUnlocked || unlockRequested) {
            return;
        }
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            appSettings.setAppLock(false);
            return;
        }
        Intent unlock = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.app_name),
            getString(R.string.unlock_h2ray)
        );
        if (unlock == null) {
            appSettings.setAppLock(false);
            return;
        }
        unlockRequested = true;
        startActivityForResult(unlock, APP_UNLOCK_REQUEST);
    }

    private void closeOtherSettingsPanels(String keep) {
        if (!"connection".equals(keep) && connectionSettingsExpanded) {
            toggleConnectionSettings();
        }
        if (!"diagnostics".equals(keep) && diagnosticsExpanded) {
            toggleDiagnostics();
        }
        if (!"updates".equals(keep) && updatesExpanded) {
            toggleUpdates();
        }
        if (!"security".equals(keep) && securityExpanded) {
            toggleSecurity();
        }
    }

    private void chooseDns() {
        String[] labels = {
            "Cloudflare UDP — 1.1.1.1",
            "Cloudflare DoH — через туннель",
            "Google DoH — через туннель",
            "Quad9 DoH — через туннель"
        };
        String[] androidDns = {"1.1.1.1", "1.1.1.1", "8.8.8.8", "9.9.9.9"};
        String[] xrayDns = {
            "1.1.1.1",
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "https://dns.quad9.net/dns-query"
        };
        new AlertDialog.Builder(this)
            .setTitle("DNS")
            .setItems(labels, (dialog, which) -> {
                appSettings.setDns(androidDns[which], xrayDns[which]);
                renderSettings();
                settingsChanged();
            })
            .show();
    }

    private void chooseMtu() {
        int[] values = {1280, 1400, 1500};
        String[] labels = {"1280", "1400", "1500"};
        new AlertDialog.Builder(this)
            .setTitle("MTU")
            .setItems(labels, (dialog, which) -> {
                appSettings.setMtu(values[which]);
                renderSettings();
                settingsChanged();
            })
            .show();
    }

    private void configureRules() {
        Switch bypassRu = findViewById(R.id.rule_bypass_ru);
        Switch bypassPrivate = findViewById(R.id.rule_bypass_private);
        Switch blockAds = findViewById(R.id.rule_block_ads);
        Switch blockQuic = findViewById(R.id.rule_block_quic);
        Switch sniffing = findViewById(R.id.rule_sniffing);
        bypassRu.setOnCheckedChangeListener((button, value) -> {
            appSettings.setBypassRu(value);
            settingsChanged();
        });
        bypassPrivate.setOnCheckedChangeListener((button, value) -> {
            appSettings.setBypassPrivate(value);
            settingsChanged();
        });
        blockAds.setOnCheckedChangeListener((button, value) -> {
            appSettings.setBlockAds(value);
            settingsChanged();
        });
        blockQuic.setOnCheckedChangeListener((button, value) -> {
            appSettings.setBlockQuic(value);
            settingsChanged();
        });
        sniffing.setOnCheckedChangeListener((button, value) -> {
            appSettings.setSniffing(value);
            settingsChanged();
        });
        ((Switch) findViewById(R.id.rule_geo_auto_update))
            .setOnCheckedChangeListener((button, value) ->
                appSettings.setAutoUpdateGeoData(value));
        findViewById(R.id.rule_mode).setOnClickListener(view -> chooseRoutingMode());
        findViewById(R.id.rule_routing_preset).setOnClickListener(
            view -> chooseRoutingPreset()
        );
        findViewById(R.id.rule_geo_update).setOnClickListener(
            view -> updateGeoData(null, true)
        );
        findViewById(R.id.rule_geo_rollback).setOnClickListener(
            view -> rollbackGeoData()
        );
        findViewById(R.id.rule_custom_domains).setOnClickListener(
            view -> editCustomRoutingRules()
        );
        findViewById(R.id.rule_apps).setOnClickListener(view -> chooseBypassApps());
        findViewById(R.id.routing_header).setOnClickListener(view -> {
            routingExpanded = !routingExpanded;
            setTileActive(R.id.routing_tile, routingExpanded);
            setViewsVisible(routingExpanded,
                R.id.rule_mode,
                R.id.rule_routing_preset,
                R.id.rule_geo_update,
                R.id.rule_geo_auto_update,
                R.id.rule_geo_status,
                R.id.rule_geo_rollback,
                R.id.rule_bypass_ru,
                R.id.rule_bypass_private,
                R.id.rule_block_ads,
                R.id.rule_block_quic,
                R.id.rule_sniffing,
                R.id.rule_custom_domains,
                R.id.rule_apps,
                R.id.rules_restart_note);
            ((TextView) findViewById(R.id.routing_header)).setText(
                routingExpanded ? R.string.routing_expanded : R.string.routing_collapsed
            );
        });
        findViewById(R.id.routing_tile).setOnClickListener(
            view -> findViewById(R.id.routing_header).performClick()
        );
        findViewById(R.id.apps_tile).setOnClickListener(
            view -> AppRoutingDialog.show(this)
        );
        renderRules();
    }

    private void renderRules() {
        ((Switch) findViewById(R.id.rule_bypass_ru)).setChecked(appSettings.bypassRu());
        ((Switch) findViewById(R.id.rule_bypass_private)).setChecked(appSettings.bypassPrivate());
        ((Switch) findViewById(R.id.rule_block_ads)).setChecked(appSettings.blockAds());
        ((Switch) findViewById(R.id.rule_block_quic)).setChecked(appSettings.blockQuic());
        ((Switch) findViewById(R.id.rule_sniffing)).setChecked(appSettings.sniffing());
        ((Switch) findViewById(R.id.rule_geo_auto_update)).setChecked(
            appSettings.autoUpdateGeoData()
        );
        String preset = appSettings.routingPreset();
        int presetLabel = GeoDataManager.DEFAULT_RU.equals(preset)
            ? R.string.routing_preset_default_ru
            : GeoDataManager.WHITELIST_RU.equals(preset)
                ? R.string.routing_preset_whitelist_ru
                : R.string.routing_preset_standard;
        ((TextView) findViewById(R.id.rule_routing_preset)).setText(
            getString(R.string.routing_preset_label, getString(presetLabel))
        );
        ((TextView) findViewById(R.id.rule_geo_status)).setText(
            GeoDataManager.status(this)
        );
        String mode = appSettings.routingMode();
        int label = "global".equals(mode)
            ? R.string.routing_global
            : "direct".equals(mode)
                ? R.string.routing_direct
                : "proxy_only".equals(mode)
                    ? R.string.routing_proxy_only
                    : "exclusions".equals(mode)
                        ? R.string.routing_exclusions
                        : R.string.routing_rules;
        ((TextView) findViewById(R.id.rule_mode)).setText(
            getString(R.string.routing_mode_label, getString(label))
        );
    }

    private void chooseRoutingMode() {
        String[] labels = {
            getString(R.string.routing_global),
            getString(R.string.routing_rules),
            getString(R.string.routing_proxy_only),
            getString(R.string.routing_exclusions),
            getString(R.string.routing_direct)
        };
        String[] values = {"global", "rules", "proxy_only", "exclusions", "direct"};
        new AlertDialog.Builder(this)
            .setTitle(R.string.rules_title)
            .setItems(labels, (dialog, which) -> {
                appSettings.setRoutingMode(values[which]);
                renderRules();
                settingsChanged();
            })
            .show();
    }

    private void chooseRoutingPreset() {
        String[] labels = {
            getString(R.string.routing_preset_standard),
            getString(R.string.routing_preset_default_ru),
            getString(R.string.routing_preset_whitelist_ru)
        };
        String[] values = {
            GeoDataManager.STANDARD,
            GeoDataManager.DEFAULT_RU,
            GeoDataManager.WHITELIST_RU
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.category_routing)
            .setItems(labels, (dialog, which) -> {
                String selected = values[which];
                if (GeoDataManager.STANDARD.equals(selected)
                    || GeoDataManager.isCustomReady(this)) {
                    applyRoutingPreset(selected);
                    return;
                }
                new AlertDialog.Builder(this)
                    .setTitle(labels[which])
                    .setMessage(
                        "Будут загружены внешние GeoIP/GeoSite из hydraponique. "
                            + "SHA-256 берётся из GitHub Release, затем файлы "
                            + "проверяются установленным Xray-core."
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.update_geo_data, (confirm, ignored) ->
                        updateGeoData(selected, true))
                    .show();
            })
            .show();
    }

    private void applyRoutingPreset(String preset) {
        appSettings.setRoutingPreset(preset);
        if (!GeoDataManager.STANDARD.equals(preset)) {
            appSettings.setRoutingMode("global");
        }
        renderRules();
        settingsChanged();
    }

    private void checkGeoDataUpdate() {
        if (GeoDataManager.STANDARD.equals(appSettings.routingPreset())
            || !appSettings.autoUpdateGeoData()
            || !GeoDataManager.updateDue(this)) {
            return;
        }
        updateGeoData(null, false);
    }

    private void updateGeoData(String selectAfter, boolean notify) {
        if (!geoUpdateRunning.compareAndSet(false, true)) {
            return;
        }
        TextView statusView = findViewById(R.id.rule_geo_status);
        statusView.setText("Загрузка и проверка geo-баз…");
        executor.execute(() -> {
            try {
                GeoDataManager.update(this);
                runOnUiThread(() -> {
                    geoUpdateRunning.set(false);
                    if (selectAfter != null) {
                        applyRoutingPreset(selectAfter);
                    }
                    renderRules();
                    if (notify) {
                        Toast.makeText(
                            this,
                            R.string.geo_update_complete,
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    geoUpdateRunning.set(false);
                    renderRules();
                    Toast.makeText(
                        this,
                        "Geo-базы не обновлены: " + safeMessage(error),
                        Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void rollbackGeoData() {
        try {
            GeoDataManager.rollback(this);
            renderRules();
            settingsChanged();
            Toast.makeText(this, "Предыдущие geo-базы восстановлены", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(
                this,
                "Откат невозможен: " + safeMessage(error),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    private void editCustomRoutingRules() {
        EditText input = new EditText(this);
        input.setHint(R.string.custom_domains_hint);
        input.setMinLines(7);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(appSettings.customDomains());
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        new AlertDialog.Builder(this)
            .setTitle(R.string.custom_domains)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                appSettings.setCustomDomains(input.getText().toString().trim());
                settingsChanged();
            })
            .show();
    }

    private void chooseBypassApps() {
        AppRoutingDialog.show(this);
    }

    private void settingsChanged() {
        if (H2RayVpnService.isRunning()) {
            Toast.makeText(this, R.string.reconnect_settings, Toast.LENGTH_SHORT).show();
        }
    }

    private void setViewsVisible(boolean visible, int... ids) {
        for (int index = 0; index < ids.length; index++) {
            View item = findViewById(ids[index]);
            item.animate().cancel();
            if (!visible) {
                item.setVisibility(View.GONE);
                continue;
            }
            item.setVisibility(View.VISIBLE);
            item.setAlpha(0f);
            item.setTranslationY(-dp(4));
            item.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(index * 22L, 110L))
                .setDuration(180)
                .start();
        }
    }

    private void setTileActive(int id, boolean active) {
        TextView tile = findViewById(id);
        tile.setTextColor(getColor(active ? R.color.accent : R.color.text_primary));
        tile.setAlpha(active ? 1f : 0.92f);
    }

    private void copyDiagnosticReport() {
        ProfileStore.Profile profile = profileStore.getActiveProfile();
        String report = "H2Ray " + currentVersion()
            + "\nAndroid API: " + Build.VERSION.SDK_INT
            + "\nXray-core: " + XrayBridge.version()
            + "\nСостояние: " + connectionStatusStore.getState()
            + "\nПротокол: " + (profile == null ? "—" : profile.protocol)
            + "\nIPv6: " + appSettings.ipv6()
            + "\nDNS: " + appSettings.dns()
            + "\nПараллельный DNS: " + appSettings.parallelDns()
            + "\nFakeDNS: " + appSettings.fakeDns()
            + "\nHappy Eyeballs: " + appSettings.happyEyeballs()
            + "\nМаршрутизация: " + appSettings.routingMode()
            + "\nАвтопереподключение: " + appSettings.autoReconnect()
            + "\nОшибка: " + redactDiagnostic(connectionStatusStore.getError());
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("H2Ray diagnostics", report));
        }
        Toast.makeText(
            this,
            R.string.diagnostic_report_copied,
            Toast.LENGTH_SHORT
        ).show();
    }

    private String redactDiagnostic(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "—";
        }
        return value
            .replaceAll(
                "(?i)(uuid|id|password|publicKey|shortId|email)[\"'=:\\\\s]+[^,\\\\s}\"]+",
                "$1=<скрыто>"
            )
            .replaceAll(
                "[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}",
                "<UUID скрыт>"
            );
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

    private void configureNavigationLabels() {
        enlargeNavigationIcon(R.id.nav_home);
        enlargeNavigationIcon(R.id.nav_profiles);
        enlargeNavigationIcon(R.id.nav_rules);
        enlargeNavigationIcon(R.id.nav_settings);
    }

    private void enlargeNavigationIcon(int viewId) {
        TextView view = findViewById(viewId);
        SpannableString text = new SpannableString(view.getText());
        int lineBreak = text.toString().indexOf('\n');
        int end = lineBreak < 0 ? Math.min(1, text.length()) : lineBreak;
        text.setSpan(
            new RelativeSizeSpan(1.45f),
            0,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        view.setText(text);
    }

    private void renderStats(boolean running) {
        if (!running) {
            statDownload.setText("—");
            statUpload.setText("—");
            statIpLabel.setText(R.string.direct_ip);
            statIp.setTextColor(getColor(R.color.text_primary));
            String directIp = connectionStatusStore.getDirectIp();
            statIp.setText(directIp.trim().isEmpty() ? "…" : directIp);
            if (directIp.trim().isEmpty()) {
                resolveDirectIp();
            }
            return;
        }

        statIpLabel.setText(R.string.proxy_ip);
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        long download = rx == TrafficStats.UNSUPPORTED
            ? 0 : Math.max(0, rx - connectionStatusStore.getRxBase());
        long upload = tx == TrafficStats.UNSUPPORTED
            ? 0 : Math.max(0, tx - connectionStatusStore.getTxBase());
        statDownload.setText(formatBytes(download));
        statUpload.setText(formatBytes(upload));
        String publicIp = connectionStatusStore.getPublicIp();
        statIp.setText(publicIp.trim().isEmpty() ? "…" : publicIp);
        boolean sameAsDirect = !publicIp.trim().isEmpty()
            && publicIp.equals(connectionStatusStore.getDirectIp());
        statIp.setTextColor(getColor(sameAsDirect ? R.color.error : R.color.text_primary));
        if (publicIp.trim().isEmpty()) {
            resolvePublicIp(false);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", value);
        }
        value /= 1024.0;
        if (value < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", value);
        }
        return String.format(Locale.ROOT, "%.2f GB", value / 1024.0);
    }

    private void resolvePublicIp(boolean force) {
        if (!H2RayVpnService.isRunning()) {
            connectionStatusStore.setDirectIp("");
            lastDirectIpAttempt = 0;
            resolveDirectIp();
            return;
        }
        if (!force && !connectionStatusStore.getPublicIp().trim().isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastProxyIpAttempt < 10000) {
            return;
        }
        if (!ipLookupRunning.compareAndSet(false, true)) {
            return;
        }
        lastProxyIpAttempt = now;
        executor.execute(() -> {
            String ip = PublicIpResolver.resolve();
            final String result = ip;
            if (!result.isEmpty()) {
                connectionStatusStore.setPublicIp(result);
            }
            ipLookupRunning.set(false);
            runOnUiThread(() -> {
                renderStats(H2RayVpnService.isRunning());
                if (force && result.isEmpty()) {
                    Toast.makeText(this, R.string.ip_check_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void resolveDirectIp() {
        if (H2RayVpnService.isRunning() || H2RayVpnService.isBusy()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastDirectIpAttempt < 30000 || !ipLookupRunning.compareAndSet(false, true)) {
            return;
        }
        lastDirectIpAttempt = now;
        executor.execute(() -> {
            String result = PublicIpResolver.resolve();
            if (!result.isEmpty() && !H2RayVpnService.isRunning()) {
                connectionStatusStore.setDirectIp(result);
            }
            ipLookupRunning.set(false);
            runOnUiThread(() -> renderStats(H2RayVpnService.isRunning()));
        });
    }

    private void render() {
        boolean running = H2RayVpnService.isRunning();
        boolean hasProfile = profileStore.hasActiveProfile();
        if (!hasProfile && !running) {
            reconcileEmptyProfileState();
        }
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
        renderStats(running);
    }

    private void reconcileEmptyProfileState() {
        if (profileStore.hasActiveProfile()) {
            return;
        }
        if (appSettings.desiredVpnRunning()) {
            appSettings.setDesiredVpnRunning(false);
        }
        if (!ConnectionStatusStore.STOPPED.equals(connectionStatusStore.getState())
            || !connectionStatusStore.getError().isEmpty()) {
            connectionStatusStore.setStopped();
        }
        if (H2RayVpnService.isRunning() || H2RayVpnService.isBusy()) {
            startService(H2RayVpnService.stopIntent(this));
        }
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
