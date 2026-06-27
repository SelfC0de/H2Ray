package com.h2ray.app.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;

public final class H2RayVpnService extends VpnService {
    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onRevoke() {
        stopSelf();
        super.onRevoke();
    }
}
