package com.h2ray.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.h2ray.app.data.ThreeXuiStore;
import com.h2ray.app.server.ThreeXuiSshClient;

import java.io.File;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerSetupActivity extends Activity {
    private static final Pattern SAFE_USERNAME =
        Pattern.compile("[A-Za-z0-9_.@-]{3,64}");
    private static final Pattern ENV_LINE =
        Pattern.compile("(?m)^([A-Z0-9_]+)=(.*)$");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ThreeXuiSshClient ssh = new ThreeXuiSshClient();
    private EditText hostInput;
    private EditText portInput;
    private EditText sshUserInput;
    private EditText sshPasswordInput;
    private EditText panelUserInput;
    private EditText panelPasswordInput;
    private TextView status;
    private TextView panelUrl;
    private TextView panelLogin;
    private View panelResult;
    private Button testButton;
    private Button installButton;
    private Button readButton;
    private Button resetButton;
    private ThreeXuiStore store;
    private volatile boolean trusted;
    private volatile boolean busy;
    private String currentPanelUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_setup);
        applyInsets();
        store = new ThreeXuiStore(this);
        hostInput = findViewById(R.id.ssh_host);
        portInput = findViewById(R.id.ssh_port);
        sshUserInput = findViewById(R.id.ssh_user);
        sshPasswordInput = findViewById(R.id.ssh_password);
        panelUserInput = findViewById(R.id.panel_username);
        panelPasswordInput = findViewById(R.id.panel_password);
        status = findViewById(R.id.server_setup_status);
        panelUrl = findViewById(R.id.panel_url);
        panelLogin = findViewById(R.id.panel_login);
        panelResult = findViewById(R.id.panel_result);
        testButton = findViewById(R.id.test_ssh);
        installButton = findViewById(R.id.install_panel);
        readButton = findViewById(R.id.read_panel);
        resetButton = findViewById(R.id.reset_panel_credentials);

        hostInput.setText(store.host());
        portInput.setText(String.valueOf(store.port()));
        sshUserInput.setText(store.sshUsername());
        String savedPanelUser = store.panelUsername();
        String savedPanelPassword = store.panelPassword();
        panelUserInput.setText(
            savedPanelUser.isEmpty() ? "h2ray_" + randomToken(6) : savedPanelUser
        );
        panelPasswordInput.setText(
            savedPanelPassword.isEmpty() ? randomToken(16) : savedPanelPassword
        );
        if (!store.panelUrl().isEmpty()) {
            showPanel(store.panelUrl(), savedPanelUser, savedPanelPassword);
        }

        findViewById(R.id.server_setup_back).setOnClickListener(view -> finish());
        testButton.setOnClickListener(view -> inspectHost());
        installButton.setOnClickListener(view -> confirmInstall());
        readButton.setOnClickListener(view -> readPanel());
        resetButton.setOnClickListener(view -> resetCredentials());
        findViewById(R.id.open_panel).setOnClickListener(view -> openPanel());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        sshPasswordInput.setText("");
        super.onDestroy();
    }

    private void inspectHost() {
        ThreeXuiSshClient.Target target = target();
        if (target == null || !begin("Получение SSH host key…")) {
            return;
        }
        executor.execute(() -> {
            try {
                ThreeXuiSshClient.HostIdentity identity = ssh.inspectHost(target);
                runOnUiThread(() -> {
                    end();
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.ssh_host_key_title)
                        .setMessage(getString(
                            R.string.ssh_host_key_message,
                            identity.fingerprint,
                            identity.type
                        ))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Доверять", (dialog, which) ->
                            trustAndVerify(target, identity))
                        .show();
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private void trustAndVerify(
        ThreeXuiSshClient.Target target,
        ThreeXuiSshClient.HostIdentity identity
    ) {
        if (!begin("Проверка SSH-доступа…")) {
            return;
        }
        executor.execute(() -> {
            try {
                ssh.trustHost(knownHosts(), target, identity);
                ThreeXuiSshClient.CommandResult result = ssh.execute(
                    knownHosts(),
                    target,
                    "printf 'uid=%s\\n' \"$(id -u)\"; "
                        + "printf 'os='; (. /etc/os-release && printf '%s %s' \"$ID\" "
                        + "\"$VERSION_ID\") 2>/dev/null || uname -s; printf '\\narch='; "
                        + "uname -m; printf '\\nxui='; command -v x-ui || true",
                    false
                );
                result.requireSuccess();
                trusted = true;
                store.saveServer(target.host, target.port, target.username);
                runOnUiThread(() -> {
                    end();
                    status.setText(getString(R.string.ssh_verified) + "\n" + result.output.trim());
                    status.setTextColor(getColor(R.color.success));
                });
            } catch (Exception error) {
                trusted = false;
                fail(error);
            }
        });
    }

    private void confirmInstall() {
        if (!trusted) {
            Toast.makeText(this, "Сначала проверьте SSH host key", Toast.LENGTH_LONG).show();
            return;
        }
        if (!validPanelCredentials()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Установка 3x-ui")
            .setMessage(
                "Будет выполнен официальный unattended-установщик 3x-ui с SQLite. "
                    + "Панель первоначально будет работать по HTTP. После установки "
                    + "настройте TLS в панели или ограничьте доступ firewall."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Установить", (dialog, which) -> installPanel())
            .show();
    }

    private void installPanel() {
        ThreeXuiSshClient.Target target = target();
        if (target == null || !begin(getString(R.string.panel_installing))) {
            return;
        }
        String panelUser = panelUserInput.getText().toString().trim();
        String panelPassword = panelPasswordInput.getText().toString();
        int panelPort = 10_000 + new SecureRandom().nextInt(50_000);
        String webPath = randomToken(18);
        String command =
            "export XUI_NONINTERACTIVE=1 XUI_DB_TYPE=sqlite XUI_SSL_MODE=none "
                + "XUI_ENABLE_FAIL2BAN=true "
                + "XUI_USERNAME=" + ThreeXuiSshClient.shellQuote(panelUser) + " "
                + "XUI_PASSWORD=" + ThreeXuiSshClient.shellQuote(panelPassword) + " "
                + "XUI_PANEL_PORT=" + panelPort + " "
                + "XUI_WEB_BASE_PATH=" + ThreeXuiSshClient.shellQuote(webPath) + "; "
                + "bash <(curl -Ls "
                + "https://raw.githubusercontent.com/mhsanaei/3x-ui/master/install.sh)";
        executor.execute(() -> {
            try {
                ThreeXuiSshClient.CommandResult result =
                    ssh.execute(knownHosts(), target, command, true);
                result.requireSuccess();
                PanelData data = readPanelData(target);
                if (data.url.isEmpty()) {
                    data.url = "http://" + target.host + ":" + panelPort + "/" + webPath + "/";
                }
                data.username = panelUser;
                data.password = panelPassword;
                store.savePanel(data.url, data.username, data.password);
                PanelData finalData = data;
                runOnUiThread(() -> {
                    end();
                    showPanel(finalData.url, finalData.username, finalData.password);
                    status.setText("3x-ui установлен и запущен");
                    status.setTextColor(getColor(R.color.success));
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private void readPanel() {
        ThreeXuiSshClient.Target target = target();
        if (target == null || !requireTrusted() || !begin(getString(R.string.panel_reading))) {
            return;
        }
        executor.execute(() -> {
            try {
                PanelData data = readPanelData(target);
                if (data.username.isEmpty()) {
                    data.username = store.panelUsername();
                }
                if (data.password.isEmpty()) {
                    data.password = store.panelPassword();
                }
                if (data.url.isEmpty()) {
                    throw new IllegalStateException(
                        "Access URL не найден. Проверьте установку и настройки панели."
                    );
                }
                store.savePanel(data.url, data.username, data.password);
                PanelData finalData = data;
                runOnUiThread(() -> {
                    end();
                    showPanel(finalData.url, finalData.username, finalData.password);
                    status.setText("Настройки 3x-ui получены");
                    status.setTextColor(getColor(R.color.success));
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private void resetCredentials() {
        ThreeXuiSshClient.Target target = target();
        if (target == null || !requireTrusted() || !validPanelCredentials()
            || !begin(getString(R.string.panel_resetting))) {
            return;
        }
        String username = panelUserInput.getText().toString().trim();
        String password = panelPasswordInput.getText().toString();
        String command = "/usr/local/x-ui/x-ui setting -username "
            + ThreeXuiSshClient.shellQuote(username)
            + " -password " + ThreeXuiSshClient.shellQuote(password)
            + " && x-ui restart";
        executor.execute(() -> {
            try {
                ThreeXuiSshClient.CommandResult result =
                    ssh.execute(knownHosts(), target, command, true);
                result.requireSuccess();
                PanelData data = readPanelData(target);
                data.username = username;
                data.password = password;
                store.savePanel(data.url, username, password);
                runOnUiThread(() -> {
                    end();
                    showPanel(data.url, username, password);
                    status.setText("Username и password обновлены");
                    status.setTextColor(getColor(R.color.success));
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private PanelData readPanelData(ThreeXuiSshClient.Target target) throws Exception {
        String command =
            "if [ -r /etc/x-ui/install-result.env ]; then "
                + "cat /etc/x-ui/install-result.env; fi; "
                + "printf '\\n__SETTINGS__\\n'; "
                + "(/usr/local/x-ui/x-ui setting -show true 2>/dev/null || "
                + "x-ui settings 2>/dev/null); printf '\\n__STATUS__\\n'; "
                + "x-ui status 2>/dev/null || true";
        ThreeXuiSshClient.CommandResult result =
            ssh.execute(knownHosts(), target, command, true);
        result.requireSuccess();
        return parsePanelData(result.output, target.host);
    }

    private PanelData parsePanelData(String output, String host) {
        PanelData data = new PanelData();
        Matcher matcher = ENV_LINE.matcher(output);
        while (matcher.find()) {
            String value = cleanEnvValue(matcher.group(2));
            switch (matcher.group(1)) {
                case "XUI_ACCESS_URL":
                    data.url = value;
                    break;
                case "XUI_USERNAME":
                    data.username = value;
                    break;
                case "XUI_PASSWORD":
                    data.password = value;
                    break;
                default:
                    break;
            }
        }
        if (data.url.isEmpty()) {
            String port = findSetting(output, "port");
            String path = findSetting(output, "webBasePath").replaceFirst("^/+", "");
            String cert = findSetting(output, "cert");
            if (!port.isEmpty() && !path.isEmpty()) {
                data.url = (cert.isEmpty() ? "http" : "https")
                    + "://" + host + ":" + port + "/" + path + "/";
            }
        }
        data.url = data.url.replace("SERVER_IP_UNKNOWN", host);
        if (!data.url.isEmpty() && !data.url.endsWith("/")) {
            data.url += "/";
        }
        return data;
    }

    private String findSetting(String output, String name) {
        Matcher matcher = Pattern.compile(
            "(?mi)^\\s*" + Pattern.quote(name) + "\\s*:\\s*(.*?)\\s*$"
        ).matcher(output);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String cleanEnvValue(String value) {
        String result = value.trim();
        if ((result.startsWith("'") && result.endsWith("'"))
            || (result.startsWith("\"") && result.endsWith("\""))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("\\ ", " ").replace("\\/", "/");
    }

    private boolean validPanelCredentials() {
        String username = panelUserInput.getText().toString().trim();
        String password = panelPasswordInput.getText().toString();
        if (!SAFE_USERNAME.matcher(username).matches()) {
            panelUserInput.setError("3–64 символа: буквы, цифры, . _ @ -");
            return false;
        }
        if (password.length() < 10 || password.length() > 128
            || password.contains("\n") || password.contains("\r")) {
            panelPasswordInput.setError("Пароль должен содержать 10–128 символов");
            return false;
        }
        return true;
    }

    private ThreeXuiSshClient.Target target() {
        String host = hostInput.getText().toString().trim();
        String username = sshUserInput.getText().toString().trim();
        String password = sshPasswordInput.getText().toString();
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException error) {
            portInput.setError("Некорректный порт");
            return null;
        }
        if (host.isEmpty() || host.contains("/") || host.contains(" ")
            || port < 1 || port > 65535 || username.isEmpty() || password.isEmpty()) {
            status.setText("Проверьте IP, порт, username и password");
            status.setTextColor(getColor(R.color.error));
            return null;
        }
        return new ThreeXuiSshClient.Target(host, port, username, password);
    }

    private boolean requireTrusted() {
        if (trusted) {
            return true;
        }
        Toast.makeText(this, "Сначала проверьте SSH host key", Toast.LENGTH_LONG).show();
        return false;
    }

    private boolean begin(String message) {
        if (busy) {
            return false;
        }
        busy = true;
        setButtonsEnabled(false);
        status.setText(message);
        status.setTextColor(getColor(R.color.text_secondary));
        return true;
    }

    private void end() {
        busy = false;
        setButtonsEnabled(true);
    }

    private void fail(Exception error) {
        runOnUiThread(() -> {
            end();
            String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
            status.setText(getString(R.string.ssh_error, message));
            status.setTextColor(getColor(R.color.error));
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        testButton.setEnabled(enabled);
        installButton.setEnabled(enabled);
        readButton.setEnabled(enabled);
        resetButton.setEnabled(enabled);
    }

    private void showPanel(String url, String username, String password) {
        currentPanelUrl = url;
        panelUrl.setText(url);
        panelLogin.setText(getString(
            R.string.panel_login_format,
            username.isEmpty() ? "неизвестен" : username,
            password.isEmpty() ? "не сохранён — задайте новый" : password
        ));
        panelResult.setVisibility(View.VISIBLE);
    }

    private void openPanel() {
        if (!currentPanelUrl.startsWith("http://")
            && !currentPanelUrl.startsWith("https://")) {
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentPanelUrl)));
    }

    private File knownHosts() {
        return new File(getFilesDir(), "ssh/known_hosts");
    }

    private String randomToken(int length) {
        final String alphabet =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private void applyInsets() {
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0;
            int bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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

    private static final class PanelData {
        String url = "";
        String username = "";
        String password = "";
    }
}
