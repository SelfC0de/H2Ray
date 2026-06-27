package com.h2ray.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
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
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.URLSpan;
import android.text.method.LinkMovementMethod;
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
import com.h2ray.app.network.PublicIpResolver;
import com.h2ray.app.network.UpdateChecker;
import com.h2ray.app.update.UpdateDownloadManager;
import com.h2ray.app.vpn.H2RayVpnService;
import com.h2ray.app.xray.ServerEndpoint;
import com.h2ray.app.xray.ProfileImporter;
import com.h2ray.app.xray.XrayBridge;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 100;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;
    private static final int FILE_IMPORT_REQUEST = 102;
    private static final int MAX_IMPORT_BYTES = 2 * 1024 * 1024;

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
    private volatile UpdateChecker.Result latestUpdate;
    private volatile long lastDirectIpAttempt;
    private volatile long lastProxyIpAttempt;
    private boolean waitingForInstallPermission;
    private boolean installerLaunched;

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
            render();
            renderUpdateDownload(updateDownloadManager.state());
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_H2Ray);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profileStore = new ProfileStore(this);
        connectionStatusStore = new ConnectionStatusStore(this);
        if (!H2RayVpnService.isRunning()) {
            connectionStatusStore.setDirectIp("");
        }
        appSettings = new AppSettings(this);
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
        findViewById(R.id.nav_home).setOnClickListener(view -> showScreen("home"));
        findViewById(R.id.nav_profiles).setOnClickListener(view -> showScreen("profiles"));
        findViewById(R.id.nav_rules).setOnClickListener(view -> showScreen("rules"));
        findViewById(R.id.nav_settings).setOnClickListener(view -> showScreen("settings"));
        findViewById(R.id.add_profile).setOnClickListener(view -> showImportDialog());
        findViewById(R.id.ping_profiles).setOnClickListener(view -> pingProfiles());
        findViewById(R.id.profiles_menu_button).setOnClickListener(
            view -> showProfilesMenu()
        );
        findViewById(R.id.open_logs).setOnClickListener(
            view -> startActivity(new Intent(this, LogsActivity.class))
        );
        updateBadge.setOnClickListener(view -> openAvailableUpdate());
        updateButton.setOnClickListener(view -> handleUpdateButton());
        configureSettings();
        configureRules();
        configureNavigationLabels();
        applySystemBarInsets();
        requestNotificationPermissionIfNeeded();
        registerDownloadReceiver();
        render();
        renderUpdateDownload(updateDownloadManager.state());
        resumeVpnAfterInstalledUpdate();
        discardInstalledUpdate();
        if (updateDownloadManager.state().complete()) {
            handler.postDelayed(this::prepareUpdateInstallation, 600);
        }
        checkForUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(stateUpdater);
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
        new IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES)
            .setPrompt(getString(R.string.qr_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan();
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
        SpannableString content = new SpannableString(getString(R.string.app_information));
        addLink(content, "Dev by SelfCode", "https://github.com/SelfC0de");
        addLink(content, "vk.com/selfcode_dev", "https://vk.com/selfcode_dev");
        addLink(content, "t.me/selfcode_dev", "https://t.me/selfcode_dev");
        addLink(content, "Github:github.com/SelfC0de", "https://github.com/SelfC0de");

        TextView message = new TextView(this);
        message.setText(content);
        message.setMovementMethod(LinkMovementMethod.getInstance());
        message.setLinkTextColor(getColor(R.color.accent));
        message.setTextColor(getColor(R.color.text_primary));
        message.setTextSize(15);
        message.setLineSpacing(0, 1.25f);
        message.setPadding(dp(24), dp(12), dp(24), dp(4));

        new AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setView(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void addLink(SpannableString content, String label, String url) {
        int start = content.toString().indexOf(label);
        if (start >= 0) {
            content.setSpan(
                new URLSpan(url),
                start,
                start + label.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
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
        if (userInitiated) {
            Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            UpdateChecker.Result result = UpdateChecker.check(this);
            latestUpdate = result;
            updateCheckRunning.set(false);
            runOnUiThread(() -> {
                updateBadge.setVisibility(result.updateAvailable ? View.VISIBLE : View.GONE);
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
        homeScreen.setVisibility("home".equals(target) ? View.VISIBLE : View.GONE);
        profilesScreen.setVisibility("profiles".equals(target) ? View.VISIBLE : View.GONE);
        rulesScreen.setVisibility("rules".equals(target) ? View.VISIBLE : View.GONE);
        settingsScreen.setVisibility("settings".equals(target) ? View.VISIBLE : View.GONE);
        setNavColor(R.id.nav_home, "home".equals(target));
        setNavColor(R.id.nav_profiles, "profiles".equals(target));
        setNavColor(R.id.nav_rules, "rules".equals(target));
        setNavColor(R.id.nav_settings, "settings".equals(target));
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

    private void setNavColor(int id, boolean active) {
        ((TextView) findViewById(id)).setTextColor(
            getColor(active ? R.color.accent : R.color.text_secondary)
        );
    }

    private void renderProfiles() {
        profilesList.removeAllViews();
        List<ProfileStore.Profile> profiles = profileStore.getProfiles();
        ((TextView) findViewById(R.id.profiles_title)).setText(
            getString(R.string.profiles_count, profiles.size())
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
            String ping = profile.ping < 0
                ? profile.ping == -2 ? getString(R.string.ping_checking) : "—"
                : profile.ping == Long.MAX_VALUE ? getString(R.string.ping_failed) : profile.ping + " ms";
            ServerEndpoint endpoint = ServerEndpoint.fromConfig(profile.config);
            String server = endpoint == null ? profile.protocol : endpoint.displayName();
            row.setText(
                marker + profile.name + "\n     "
                    + profile.protocol + "  ·  " + server + "  ·  " + ping
            );
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
        ProfileStore.Profile profile = profileStore.getActiveProfile();
        if (profile == null) {
            return;
        }
        profileStore.updatePing(profile.id, -2);
        renderProfiles();
        executor.execute(() -> {
            profileStore.updatePing(profile.id, measurePing(profile));
            runOnUiThread(this::renderProfiles);
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
        ipv6.setOnCheckedChangeListener((button, value) -> {
            appSettings.setIpv6(value);
            settingsChanged();
        });
        findViewById(R.id.setting_dns).setOnClickListener(view -> chooseDns());
        findViewById(R.id.setting_mtu).setOnClickListener(view -> chooseMtu());
        renderSettings();
    }

    private void renderSettings() {
        ((Switch) findViewById(R.id.setting_ipv6)).setChecked(appSettings.ipv6());
        ((TextView) findViewById(R.id.setting_dns)).setText(
            getString(R.string.dns_label, appSettings.dns())
        );
        ((TextView) findViewById(R.id.setting_mtu)).setText(
            getString(R.string.mtu_label, appSettings.mtu())
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
        findViewById(R.id.rule_mode).setOnClickListener(view -> chooseRoutingMode());
        renderRules();
    }

    private void renderRules() {
        ((Switch) findViewById(R.id.rule_bypass_ru)).setChecked(appSettings.bypassRu());
        ((Switch) findViewById(R.id.rule_bypass_private)).setChecked(appSettings.bypassPrivate());
        ((Switch) findViewById(R.id.rule_block_ads)).setChecked(appSettings.blockAds());
        ((Switch) findViewById(R.id.rule_block_quic)).setChecked(appSettings.blockQuic());
        ((Switch) findViewById(R.id.rule_sniffing)).setChecked(appSettings.sniffing());
        String mode = appSettings.routingMode();
        int label = "global".equals(mode)
            ? R.string.routing_global
            : "direct".equals(mode) ? R.string.routing_direct : R.string.routing_rules;
        ((TextView) findViewById(R.id.rule_mode)).setText(
            getString(R.string.routing_mode_label, getString(label))
        );
    }

    private void chooseRoutingMode() {
        String[] labels = {
            getString(R.string.routing_global),
            getString(R.string.routing_rules),
            getString(R.string.routing_direct)
        };
        String[] values = {"global", "rules", "direct"};
        new AlertDialog.Builder(this)
            .setTitle(R.string.rules_title)
            .setItems(labels, (dialog, which) -> {
                appSettings.setRoutingMode(values[which]);
                renderRules();
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
