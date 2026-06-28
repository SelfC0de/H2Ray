package com.h2ray.app.server;

import android.util.Base64;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

public final class ThreeXuiSshClient {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int COMMAND_TIMEOUT_MS = 12 * 60_000;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    public static final class Target {
        public final String host;
        public final int port;
        public final String username;
        public final String password;

        public Target(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }

    public static final class HostIdentity {
        public final String type;
        public final String key;
        public final String fingerprint;

        HostIdentity(String type, String key, String fingerprint) {
            this.type = type;
            this.key = key;
            this.fingerprint = fingerprint;
        }
    }

    public static final class CommandResult {
        public final int exitCode;
        public final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public void requireSuccess() {
            if (exitCode != 0) {
                throw new IllegalStateException(
                    output.trim().isEmpty()
                        ? "Команда завершилась с кодом " + exitCode
                        : output.trim()
                );
            }
        }
    }

    public HostIdentity inspectHost(Target target) throws Exception {
        Session session = createSession(target, null);
        session.setConfig("StrictHostKeyChecking", "no");
        try {
            session.connect(CONNECT_TIMEOUT_MS);
            HostKey hostKey = session.getHostKey();
            byte[] decoded = Base64.decode(hostKey.getKey(), Base64.DEFAULT);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(decoded);
            String fingerprint = Base64.encodeToString(digest, Base64.NO_WRAP)
                .replaceAll("=+$", "");
            return new HostIdentity(
                hostKey.getType(),
                hostKey.getKey(),
                "SHA256:" + fingerprint
            );
        } finally {
            session.disconnect();
        }
    }

    public void trustHost(File knownHosts, Target target, HostIdentity identity)
        throws Exception {
        File parent = knownHosts.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Не удалось создать хранилище host key");
        }
        String name = target.port == 22
            ? target.host
            : "[" + target.host + "]:" + target.port;
        try (FileOutputStream output = new FileOutputStream(knownHosts, false)) {
            output.write(
                (name + " " + identity.type + " " + identity.key + "\n")
                    .getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    public CommandResult execute(
        File knownHosts,
        Target target,
        String command,
        boolean rootRequired
    ) throws Exception {
        Session session = createSession(target, knownHosts);
        JSch.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig("StrictHostKeyChecking", "yes");
        try {
            session.connect(CONNECT_TIMEOUT_MS);
            String wrapped = "bash -lc " + shellQuote(command);
            boolean useSudo = rootRequired && !"root".equals(target.username);
            if (useSudo) {
                wrapped = "sudo -S -p '' " + wrapped;
            }
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(wrapped);
            channel.setInputStream(null);
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            channel.setErrStream(errors);
            InputStream stdout = channel.getInputStream();
            OutputStream stdin = channel.getOutputStream();
            channel.connect(CONNECT_TIMEOUT_MS);
            if (useSudo) {
                stdin.write((target.password + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
            while (!channel.isClosed()) {
                while (stdout.available() > 0) {
                    int read = stdout.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    appendLimited(result, buffer, read);
                }
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect();
                    throw new IllegalStateException("Превышен тайм-аут выполнения команды");
                }
                Thread.sleep(80);
            }
            while (stdout.available() > 0) {
                int read = stdout.read(buffer);
                if (read < 0) {
                    break;
                }
                appendLimited(result, buffer, read);
            }
            if (errors.size() > 0) {
                appendLimited(result, errors.toByteArray(), errors.size());
            }
            int exitCode = channel.getExitStatus();
            channel.disconnect();
            return new CommandResult(
                exitCode,
                result.toString(StandardCharsets.UTF_8.name())
            );
        } finally {
            session.disconnect();
        }
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private Session createSession(Target target, File knownHosts) throws Exception {
        JSch jsch = new JSch();
        if (knownHosts != null) {
            jsch.setKnownHosts(knownHosts.getAbsolutePath());
        }
        Session session = jsch.getSession(target.username, target.host, target.port);
        session.setPassword(target.password);
        Properties config = new Properties();
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(config);
        return session;
    }

    private void appendLimited(ByteArrayOutputStream destination, byte[] data, int length) {
        int remaining = MAX_OUTPUT_BYTES - destination.size();
        if (remaining <= 0) {
            return;
        }
        destination.write(data, 0, Math.min(length, remaining));
    }
}
