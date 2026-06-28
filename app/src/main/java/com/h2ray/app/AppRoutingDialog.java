package com.h2ray.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.h2ray.app.data.AppSettings;
import com.h2ray.app.vpn.H2RayVpnService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppRoutingDialog {
    private AppRoutingDialog() {
    }

    public static void show(Activity activity) {
        Toast.makeText(activity, R.string.scanning_apps, Toast.LENGTH_SHORT).show();
        new AppScanner(activity).start();
    }

    private static final class AppScanner extends Thread {
        private final Activity activity;

        AppScanner(Activity activity) {
            super("H2Ray-AppScanner");
            this.activity = activity;
        }

        @Override
        public void run() {
            try {
                PackageManager manager = activity.getPackageManager();
                List<ApplicationInfo> apps = new ArrayList<>();
                for (ApplicationInfo app :
                    manager.getInstalledApplications(0)) {
                    boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                    if (!system
                        && app.enabled
                        && !activity.getPackageName().equals(app.packageName)
                        && manager.getLaunchIntentForPackage(app.packageName) != null) {
                        apps.add(app);
                    }
                }
                apps.sort(new AppLabelComparator(manager));
                new Handler(Looper.getMainLooper()).post(
                    new ScanResult(activity, apps, null)
                );
            } catch (RuntimeException error) {
                new Handler(Looper.getMainLooper()).post(
                    new ScanResult(activity, null, error)
                );
            }
        }
    }

    private static final class AppLabelComparator
        implements Comparator<ApplicationInfo> {
        private final PackageManager manager;

        AppLabelComparator(PackageManager manager) {
            this.manager = manager;
        }

        @Override
        public int compare(ApplicationInfo left, ApplicationInfo right) {
            return safeLabel(manager, left).compareToIgnoreCase(
                safeLabel(manager, right)
            );
        }
    }

    private static final class ScanResult implements Runnable {
        private final Activity activity;
        private final List<ApplicationInfo> apps;
        private final RuntimeException error;

        ScanResult(
            Activity activity,
            List<ApplicationInfo> apps,
            RuntimeException error
        ) {
            this.activity = activity;
            this.apps = apps;
            this.error = error;
        }

        @Override
        public void run() {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (error != null) {
                showScanError(activity, error);
                return;
            }
            try {
                build(activity, apps);
            } catch (RuntimeException buildError) {
                showScanError(activity, buildError);
            }
        }
    }

    private static void build(Activity activity, List<ApplicationInfo> apps) {
        AppSettings settings = new AppSettings(activity);
        Set<String> selected = new LinkedHashSet<>(settings.bypassApps());
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 14));
        root.setBackgroundResource(R.drawable.bg_card);

        TextView handle = new TextView(activity);
        handle.setText("━");
        handle.setGravity(Gravity.CENTER);
        handle.setTextColor(activity.getColor(R.color.text_secondary));
        handle.setTextSize(22);
        root.addView(handle, match(dp(activity, 28)));

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(R.string.apps_panel_title);
        title.setTextColor(activity.getColor(R.color.text_primary));
        title.setTextSize(23);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
            0,
            dp(activity, 48),
            1f
        ));
        TextView close = new TextView(activity);
        close.setText("×");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(activity.getColor(R.color.text_secondary));
        close.setTextSize(30);
        titleRow.addView(close, new LinearLayout.LayoutParams(
            dp(activity, 48),
            dp(activity, 48)
        ));
        root.addView(titleRow, match(dp(activity, 48)));

        EditText search = new EditText(activity);
        search.setHint(R.string.search_apps);
        search.setSingleLine(true);
        search.setTextColor(activity.getColor(R.color.text_primary));
        search.setHintTextColor(activity.getColor(R.color.text_secondary));
        search.setBackgroundResource(R.drawable.bg_stat);
        search.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        LinearLayout.LayoutParams searchParams = match(dp(activity, 52));
        searchParams.bottomMargin = dp(activity, 10);
        root.addView(search, searchParams);

        LinearLayout modes = new LinearLayout(activity);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        Button only = modeButton(activity, R.string.apps_only_vpn);
        Button bypass = modeButton(activity, R.string.apps_outside_vpn);
        modes.addView(only, weighted(dp(activity, 48)));
        modes.addView(bypass, weighted(dp(activity, 48)));
        root.addView(modes, match(dp(activity, 48)));

        LinearLayout modeInfo = new LinearLayout(activity);
        modeInfo.setGravity(Gravity.CENTER_VERTICAL);
        TextView modeHint = new TextView(activity);
        modeHint.setTextColor(activity.getColor(R.color.text_secondary));
        modeHint.setTextSize(12);
        modeInfo.addView(modeHint, new LinearLayout.LayoutParams(
            0,
            dp(activity, 42),
            1f
        ));
        TextView selectedCount = new TextView(activity);
        selectedCount.setTextColor(activity.getColor(R.color.accent));
        selectedCount.setTextSize(12);
        selectedCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        modeInfo.addView(selectedCount, new LinearLayout.LayoutParams(
            dp(activity, 86),
            dp(activity, 42)
        ));
        root.addView(modeInfo, match(dp(activity, 42)));

        ListView list = new ListView(activity);
        list.setDivider(new ColorDrawable(activity.getColor(R.color.border)));
        list.setDividerHeight(1);
        list.setCacheColorHint(Color.TRANSPARENT);
        Runnable renderCount = () -> selectedCount.setText(
            activity.getString(R.string.apps_selected_count, selected.size())
        );
        AppAdapter adapter = new AppAdapter(activity, apps, selected, renderCount);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        );
        listParams.topMargin = dp(activity, 8);
        root.addView(list, listParams);

        Button apply = new Button(activity);
        apply.setText(R.string.apply);
        apply.setAllCaps(false);
        apply.setTextSize(16);
        apply.setTextColor(Color.BLACK);
        apply.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent))
        );
        root.addView(apply, match(dp(activity, 52)));

        final String[] mode = {settings.appRoutingMode()};
        Runnable renderModes = () -> {
            boolean onlyMode = "only".equals(mode[0]);
            renderModeButton(activity, only, onlyMode);
            renderModeButton(activity, bypass, !onlyMode);
            modeHint.setText(
                onlyMode
                    ? R.string.apps_only_vpn_hint
                    : R.string.apps_outside_vpn_hint
            );
        };
        only.setOnClickListener(view -> {
            mode[0] = "only";
            renderModes.run();
        });
        bypass.setOnClickListener(view -> {
            mode[0] = "bypass";
            renderModes.run();
        });
        renderModes.run();
        renderCount.run();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(
                CharSequence value, int start, int count, int after
            ) {
            }
            @Override public void onTextChanged(
                CharSequence value, int start, int before, int count
            ) {
                adapter.filter(value == null ? "" : value.toString());
            }
            @Override public void afterTextChanged(Editable value) {
            }
        });
        apply.setOnClickListener(view -> {
            Set<String> installed = new LinkedHashSet<>();
            for (ApplicationInfo app : apps) {
                installed.add(app.packageName);
            }
            selected.retainAll(installed);
            settings.setBypassApps(selected);
            settings.setAppRoutingMode(mode[0]);
            if (H2RayVpnService.isRunning()) {
                activity.startService(H2RayVpnService.restartIntent(activity));
            }
            Toast.makeText(activity, R.string.apps_saved, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        close.setOnClickListener(view -> dialog.dismiss());

        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.88f)
            );
            window.setDimAmount(0.62f);
        }
    }

    private static Button modeButton(Context context, int text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(context.getColor(R.color.text_primary));
        button.setTextSize(13);
        button.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(context.getColor(R.color.surface_elevated))
        );
        return button;
    }

    private static void renderModeButton(Context context, Button button, boolean active) {
        button.setAlpha(1f);
        button.setTextColor(context.getColor(active ? R.color.background : R.color.text_primary));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            context.getColor(active ? R.color.accent : R.color.surface_elevated)
        ));
    }

    private static LinearLayout.LayoutParams match(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private static LinearLayout.LayoutParams weighted(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1f);
        params.setMarginStart(3);
        params.setMarginEnd(3);
        return params;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class AppAdapter extends BaseAdapter {
        private final Activity activity;
        private final List<ApplicationInfo> all;
        private final List<ApplicationInfo> visible = new ArrayList<>();
        private final Set<String> selected;
        private final Runnable selectionChanged;

        AppAdapter(
            Activity activity,
            List<ApplicationInfo> apps,
            Set<String> selected,
            Runnable selectionChanged
        ) {
            this.activity = activity;
            this.all = apps;
            this.selected = selected;
            this.selectionChanged = selectionChanged;
            visible.addAll(apps);
        }

        void filter(String query) {
            String value = query.trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (ApplicationInfo app : all) {
                String label = safeLabel(activity.getPackageManager(), app);
                if (value.isEmpty()
                    || label.toLowerCase(Locale.ROOT).contains(value)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(value)) {
                    visible.add(app);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() {
            return visible.size();
        }

        @Override public Object getItem(int position) {
            return visible.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof Row) {
                row = (Row) recycled.getTag();
            } else {
                row = new Row(activity);
                recycled = row.root;
                recycled.setTag(row);
            }
            ApplicationInfo app = visible.get(position);
            try {
                row.icon.setImageDrawable(app.loadIcon(activity.getPackageManager()));
            } catch (RuntimeException error) {
                row.icon.setImageDrawable(
                    activity.getPackageManager().getDefaultActivityIcon()
                );
            }
            row.name.setText(safeLabel(activity.getPackageManager(), app));
            row.packageName.setText(app.packageName);
            row.check.setOnCheckedChangeListener(null);
            row.check.setChecked(selected.contains(app.packageName));
            row.check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selected.add(app.packageName);
                } else {
                    selected.remove(app.packageName);
                }
                selectionChanged.run();
            });
            row.root.setOnClickListener(view -> row.check.setChecked(!row.check.isChecked()));
            return recycled;
        }
    }

    private static String safeLabel(PackageManager manager, ApplicationInfo app) {
        try {
            CharSequence label = app.loadLabel(manager);
            return label == null || label.toString().trim().isEmpty()
                ? app.packageName
                : label.toString();
        } catch (RuntimeException error) {
            return app.packageName;
        }
    }

    private static void showScanError(Activity activity, Throwable error) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        String message = error.getMessage();
        Toast.makeText(
            activity,
            activity.getString(
                R.string.apps_scan_failed,
                message == null || message.trim().isEmpty()
                    ? error.getClass().getSimpleName()
                    : message
            ),
            Toast.LENGTH_LONG
        ).show();
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final CheckBox check;

        Row(Context context) {
            root = new LinearLayout(context);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(context, 10), dp(context, 7), dp(context, 8), dp(context, 7));
            root.setMinimumHeight(dp(context, 66));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)));
            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(context, 12), 0, dp(context, 6), 0);
            name = new TextView(context);
            name.setTextColor(context.getColor(R.color.text_primary));
            name.setTextSize(14);
            name.setSingleLine(true);
            packageName = new TextView(context);
            packageName.setTextColor(context.getColor(R.color.text_secondary));
            packageName.setTextSize(11);
            packageName.setSingleLine(true);
            labels.addView(name);
            labels.addView(packageName);
            root.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            check = new CheckBox(context);
            check.setButtonTintList(context.getColorStateList(R.color.switch_thumb));
            root.addView(check, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        }
    }
}
