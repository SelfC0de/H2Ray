package com.h2ray.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.h2ray.app.data.LogStore;

public final class LogsActivity extends Activity {
    private TextView logText;
    private LogStore logStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        logStore = new LogStore(this);
        logText = findViewById(R.id.log_text);
        findViewById(R.id.logs_back).setOnClickListener(view -> finish());
        findViewById(R.id.copy_logs).setOnClickListener(view -> copyLogs());
        findViewById(R.id.clear_logs).setOnClickListener(view -> {
            logStore.clear();
            render();
        });
        applyInsets();
        render();
    }

    private void render() {
        String logs = logStore.get();
        logText.setText(logs.trim().isEmpty() ? getString(R.string.logs_empty) : logs);
    }

    private void copyLogs() {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("H2Ray logs", logStore.get()));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void applyInsets() {
        findViewById(R.id.logs_root).setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
    }
}
