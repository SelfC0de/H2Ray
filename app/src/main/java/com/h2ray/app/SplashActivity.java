package com.h2ray.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openMain = () -> {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_H2Ray);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        View logo = findViewById(R.id.splash_logo);
        View title = findViewById(R.id.splash_title);
        View accent = findViewById(R.id.splash_accent);
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        ObjectAnimator logoMove = ObjectAnimator.ofFloat(
            logo,
            View.TRANSLATION_Y,
            dp(18),
            0f
        );
        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f);
        ObjectAnimator titleMove = ObjectAnimator.ofFloat(
            title,
            View.TRANSLATION_Y,
            dp(8),
            0f
        );
        ObjectAnimator accentAlpha = ObjectAnimator.ofFloat(accent, View.ALPHA, 0f, 1f);
        ObjectAnimator accentReveal = ObjectAnimator.ofFloat(accent, View.SCALE_X, 0f, 1f);
        logoAlpha.setDuration(520);
        logoMove.setDuration(620);
        titleAlpha.setDuration(420);
        titleMove.setDuration(420);
        titleAlpha.setStartDelay(300);
        titleMove.setStartDelay(300);
        accentAlpha.setDuration(360);
        accentReveal.setDuration(500);
        accentAlpha.setStartDelay(560);
        accentReveal.setStartDelay(560);
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(
            logoAlpha,
            logoMove,
            titleAlpha,
            titleMove,
            accentAlpha,
            accentReveal
        );
        animation.setInterpolator(new DecelerateInterpolator());
        animation.start();
        handler.postDelayed(openMain, 2000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openMain);
        super.onDestroy();
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
