package com.h2ray.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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
    private static final Pattern SAFE_DOMAIN = Pattern.compile(
        "(?i)(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"
    );

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ThreeXuiSshClient ssh = new ThreeXuiSshClient();
    private EditText hostInput;
    private EditText portInput;
    private EditText sshUserInput;
    private EditText sshPasswordInput;
    private EditText panelUserInput;
    private EditText panelPasswordInput;
    private EditText tlsDomainInput;
    private TextView status;
    private TextView panelUrl;
    private TextView panelLogin;
    private View panelResult;
    private Button testButton;
    private Button installButton;
    private Button readButton;
    private Button resetButton;
    private Button configureTlsButton;
    private Button rollbackTlsButton;
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
        tlsDomainInput = findViewById(R.id.panel_tls_domain);
        status = findViewById(R.id.server_setup_status);
        panelUrl = findViewById(R.id.panel_url);
        panelLogin = findViewById(R.id.panel_login);
        panelResult = findViewById(R.id.panel_result);
        testButton = findViewById(R.id.test_ssh);
        testButton.setTextSize(11);
        installButton = findViewById(R.id.install_panel);
        readButton = findViewById(R.id.read_panel);
        resetButton = findViewById(R.id.reset_panel_credentials);
        configureTlsButton = findViewById(R.id.configure_panel_tls);
        rollbackTlsButton = findViewById(R.id.rollback_panel_tls);

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
        findViewById(R.id.generate_panel_username).setOnClickListener(
            view -> panelUserInput.setText("h2ray_" + randomToken(8))
        );
        findViewById(R.id.generate_panel_password).setOnClickListener(
            view -> panelPasswordInput.setText(randomToken(20))
        );
        ImageButton sshPasswordToggle = findViewById(R.id.toggle_ssh_password);
        ImageButton panelPasswordToggle = findViewById(R.id.toggle_panel_password);
        sshPasswordToggle.setOnClickListener(
            view -> togglePassword(sshPasswordInput, sshPasswordToggle)
        );
        panelPasswordToggle.setOnClickListener(
            view -> togglePassword(panelPasswordInput, panelPasswordToggle)
        );
        testButton.setOnClickListener(view -> inspectHost());
        installButton.setOnClickListener(view -> confirmInstall());
        readButton.setOnClickListener(view -> readPanel());
        resetButton.setOnClickListener(view -> resetCredentials());
        configureTlsButton.setOnClickListener(view -> confirmConfigureTls());
        rollbackTlsButton.setOnClickListener(view -> confirmRollbackTls());
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

    private void confirmConfigureTls() {
        if (!requireTrusted()) {
            return;
        }
        String domain = normalizedDomain();
        if (domain == null) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.tls_master_title)
            .setMessage(R.string.tls_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) ->
                configureTls(domain))
            .show();
    }

    private void configureTls(String domain) {
        ThreeXuiSshClient.Target target = target();
        if (target == null || !begin("Проверка DNS и выпуск сертификата…")) {
            return;
        }
        String quotedDomain = ThreeXuiSshClient.shellQuote(domain);
        String command =
            "set -Eeuo pipefail; DOMAIN=" + quotedDomain + "; "
                + "test -x /usr/local/x-ui/x-ui; test -f /etc/x-ui/x-ui.db; "
                + "SERVER_IP=$(curl -4fsS --max-time 12 https://api.ipify.org); "
                + "getent ahostsv4 \"$DOMAIN\" | awk '{print $1}' "
                + "| grep -Fxq \"$SERVER_IP\" || { "
                + "echo \"A-запись домена не указывает на $SERVER_IP\"; exit 24; }; "
                + "BACKUP=/var/backups/h2ray-3xui/$(date -u +%Y%m%dT%H%M%SZ); "
                + "mkdir -p \"$BACKUP\"; chmod 700 /var/backups/h2ray-3xui \"$BACKUP\"; "
                + "SETTINGS=$(/usr/local/x-ui/x-ui setting -show true 2>/dev/null || true); "
                + "OLD_CERT=$(printf '%s\\n' \"$SETTINGS\" "
                + "| awk -F: 'tolower($1) ~ /^[[:space:]]*cert[[:space:]]*$/ "
                + "{sub(/^[^:]*:[[:space:]]*/,\"\"); print; exit}'); "
                + "OLD_KEY=$(printf '%s\\n' \"$SETTINGS\" "
                + "| awk -F: 'tolower($1) ~ /^[[:space:]]*key[[:space:]]*$/ "
                + "{sub(/^[^:]*:[[:space:]]*/,\"\"); print; exit}'); "
                + "if command -v sqlite3 >/dev/null 2>&1; then "
                + "sqlite3 /etc/x-ui/x-ui.db \".backup '$BACKUP/x-ui.db'\"; "
                + "else cp -a /etc/x-ui/x-ui.db \"$BACKUP/x-ui.db\"; fi; "
                + "printf 'OLD_CERT_B64=%s\\nOLD_KEY_B64=%s\\nDOMAIN=%s\\n' "
                + "\"$(printf %s \"$OLD_CERT\" | base64 | tr -d '\\n')\" "
                + "\"$(printf %s \"$OLD_KEY\" | base64 | tr -d '\\n')\" "
                + "\"$DOMAIN\" > \"$BACKUP/tls.env\"; "
                + "ln -sfn \"$BACKUP\" /var/backups/h2ray-3xui/latest-tls; "
                + "rollback(){ systemctl stop x-ui || true; "
                + "cp -a \"$BACKUP/x-ui.db\" /etc/x-ui/x-ui.db; "
                + "systemctl start x-ui || true; }; trap rollback ERR; "
                + "if ss -H -ltn 'sport = :80' | grep -q .; then "
                + "echo 'Порт 80 занят. TLS-мастер ничего не изменил.'; exit 25; fi; "
                + "if ! command -v certbot >/dev/null 2>&1; then "
                + "if command -v apt-get >/dev/null 2>&1; then "
                + "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y certbot; "
                + "elif command -v dnf >/dev/null 2>&1; then dnf install -y certbot; "
                + "else echo 'Не удалось установить certbot'; exit 26; fi; fi; "
                + "certbot certonly --standalone --non-interactive --agree-tos "
                + "--register-unsafely-without-email --keep-until-expiring -d \"$DOMAIN\"; "
                + "CERT=/etc/letsencrypt/live/$DOMAIN/fullchain.pem; "
                + "KEY=/etc/letsencrypt/live/$DOMAIN/privkey.pem; "
                + "test -s \"$CERT\"; test -s \"$KEY\"; "
                + "/usr/local/x-ui/x-ui cert -webCert \"$CERT\" -webCertKey \"$KEY\"; "
                + "install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy; "
                + "printf '#!/bin/sh\\nsystemctl restart x-ui\\n' "
                + "> /etc/letsencrypt/renewal-hooks/deploy/h2ray-xui-restart; "
                + "chmod 755 /etc/letsencrypt/renewal-hooks/deploy/h2ray-xui-restart; "
                + "systemctl restart x-ui; systemctl is-active --quiet x-ui; "
                + "trap - ERR; echo \"TLS_OK=$DOMAIN\"";
        executor.execute(() -> {
            try {
                ThreeXuiSshClient.CommandResult result =
                    ssh.execute(knownHosts(), target, command, true);
                result.requireSuccess();
                PanelData data = readPanelData(target);
                data.url = replaceUrlHost(data.url, domain);
                store.savePanel(data.url, store.panelUsername(), store.panelPassword());
                runOnUiThread(() -> {
                    end();
                    showPanel(data.url, store.panelUsername(), store.panelPassword());
                    status.setText("TLS настроен: " + domain);
                    status.setTextColor(getColor(R.color.success));
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private void confirmRollbackTls() {
        if (!requireTrusted()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.rollback_panel_tls)
            .setMessage("Будут восстановлены только прежние пути сертификата панели.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> rollbackTls())
            .show();
    }

    private void rollbackTls() {
        ThreeXuiSshClient.Target target = target();
        if (target == null || !begin("Откат TLS…")) {
            return;
        }
        String command =
            "set -euo pipefail; L=/var/backups/h2ray-3xui/latest-tls; "
                + "test -r \"$L/tls.env\"; . \"$L/tls.env\"; "
                + "OLD_CERT=$(printf %s \"$OLD_CERT_B64\" | base64 -d); "
                + "OLD_KEY=$(printf %s \"$OLD_KEY_B64\" | base64 -d); "
                + "/usr/local/x-ui/x-ui cert -webCert \"$OLD_CERT\" "
                + "-webCertKey \"$OLD_KEY\"; systemctl restart x-ui; "
                + "systemctl is-active --quiet x-ui";
        executor.execute(() -> {
            try {
                ThreeXuiSshClient.CommandResult result =
                    ssh.execute(knownHosts(), target, command, true);
                result.requireSuccess();
                runOnUiThread(() -> {
                    end();
                    status.setText("Предыдущие настройки TLS восстановлены");
                    status.setTextColor(getColor(R.color.success));
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private String normalizedDomain() {
        String domain = tlsDomainInput.getText().toString()
            .trim()
            .toLowerCase(Locale.ROOT);
        if (!SAFE_DOMAIN.matcher(domain).matches()) {
            tlsDomainInput.setError("Укажите корректный домен без http:// и пути");
            return null;
        }
        return domain;
    }

    private String replaceUrlHost(String source, String domain) {
        if (source == null || source.isEmpty()) {
            return "https://" + domain + "/";
        }
        try {
            Uri value = Uri.parse(source);
            return value.buildUpon()
                .scheme("https")
                .encodedAuthority(domain + (value.getPort() > 0 ? ":" + value.getPort() : ""))
                .build()
                .toString();
        } catch (RuntimeException error) {
            return "https://" + domain + "/";
        }
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
            status.setVisibility(View.VISIBLE);
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
        status.setVisibility(View.VISIBLE);
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
            status.setVisibility(View.VISIBLE);
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
        configureTlsButton.setEnabled(enabled);
        rollbackTlsButton.setEnabled(enabled);
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

    private void togglePassword(EditText input, ImageButton button) {
        boolean hidden = input.getTransformationMethod() instanceof
            PasswordTransformationMethod;
        input.setTransformationMethod(
            hidden ? null : PasswordTransformationMethod.getInstance()
        );
        button.setImageResource(
            hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility
        );
        button.setContentDescription(getString(
            hidden ? R.string.hide_password : R.string.show_password
        ));
        input.setSelection(input.length());
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
