package com.h2ray.app.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import com.h2ray.app.data.AppSettings;
import com.h2ray.app.data.ProfileStore;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        AppSettings settings = new AppSettings(context);
        if (!settings.restoreAfterBoot() || !settings.desiredVpnRunning()
            || !new ProfileStore(context).hasActiveProfile()
            || VpnService.prepare(context) != null) {
            return;
        }
        Intent service = H2RayVpnService.startIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
