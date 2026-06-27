package com.h2ray.app.update;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import com.h2ray.app.network.UpdateChecker;

import java.io.File;

public final class UpdateDownloadManager {
    private static final String PREFERENCES = "h2ray_updates";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_VERSION = "download_version";
    private static final String KEY_RECONNECT = "reconnect_after_update";

    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences preferences;

    public UpdateDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public long start(UpdateChecker.Result update) {
        removePreviousDownload();
        String fileName = "H2Ray-" + update.latestVersion + ".apk";
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory != null) {
            File target = new File(directory, fileName);
            if (target.exists()) {
                target.delete();
            }
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("H2Ray " + update.latestVersion)
            .setDescription("Скачивание обновления")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            );
        long id = manager.enqueue(request);
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_VERSION, update.latestVersion)
            .apply();
        return id;
    }

    public State state() {
        long id = downloadId();
        if (id < 0) {
            return State.none();
        }
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return State.none();
            }
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            );
            long downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            );
            long total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            );
            int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : -1;
            return new State(status, percent);
        } catch (Exception error) {
            return State.none();
        }
    }

    public Uri downloadedUri() {
        long id = downloadId();
        return id < 0 ? null : manager.getUriForDownloadedFile(id);
    }

    public File downloadedFile() {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        String version = targetVersion();
        return directory == null || version.isEmpty()
            ? null
            : new File(directory, "H2Ray-" + version + ".apk");
    }

    public long downloadId() {
        return preferences.getLong(KEY_DOWNLOAD_ID, -1);
    }

    public String targetVersion() {
        return preferences.getString(KEY_VERSION, "");
    }

    public void markReconnectRequired(boolean required) {
        preferences.edit().putBoolean(KEY_RECONNECT, required).apply();
    }

    public boolean reconnectRequired() {
        return preferences.getBoolean(KEY_RECONNECT, false);
    }

    public void clearReconnectRequired() {
        preferences.edit().remove(KEY_RECONNECT).apply();
    }

    public void clearFailedDownload() {
        State state = state();
        if (state.status == DownloadManager.STATUS_FAILED) {
            preferences.edit().remove(KEY_DOWNLOAD_ID).apply();
        }
    }

    public void discardInstalledDownload() {
        long id = downloadId();
        if (id >= 0) {
            manager.remove(id);
        }
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_VERSION)
            .apply();
    }

    private void removePreviousDownload() {
        long previous = downloadId();
        if (previous >= 0) {
            manager.remove(previous);
        }
        preferences.edit().remove(KEY_DOWNLOAD_ID).apply();
    }

    public static final class State {
        public final int status;
        public final int percent;

        private State(int status, int percent) {
            this.status = status;
            this.percent = percent;
        }

        private static State none() {
            return new State(-1, -1);
        }

        public boolean downloading() {
            return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED;
        }

        public boolean complete() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }

        public boolean failed() {
            return status == DownloadManager.STATUS_FAILED;
        }
    }
}
