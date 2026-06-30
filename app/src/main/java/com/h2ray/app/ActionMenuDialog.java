package com.h2ray.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ActionMenuDialog {
    public interface Listener {
        void onSelected(int index);
    }

    private ActionMenuDialog() {
    }

    public static void show(
        Activity activity,
        String titleText,
        String[] actions,
        Listener listener
    ) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 18));
        panel.setBackgroundResource(R.drawable.bg_card);

        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(activity.getColor(R.color.text_primary));
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 48), 1f));
        TextView close = new TextView(activity);
        close.setText("×");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(activity.getColor(R.color.text_secondary));
        close.setTextSize(28);
        heading.addView(close, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        panel.addView(heading);

        for (int index = 0; index < actions.length; index++) {
            TextView item = new TextView(activity);
            item.setText(actions[index] + "   ›");
            item.setTextColor(activity.getColor(R.color.text_primary));
            item.setTextSize(15);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(activity, 16), 0, dp(activity, 14), 0);
            item.setBackgroundResource(R.drawable.bg_stat);
            int selected = index;
            item.setOnClickListener(view -> {
                dialog.dismiss();
                listener.onSelected(selected);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 56)
            );
            params.topMargin = dp(activity, 7);
            panel.addView(item, params);
        }
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(panel);
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
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            window.setDimAmount(0.62f);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
