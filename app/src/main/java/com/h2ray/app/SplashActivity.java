package com.h2ray.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

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
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.78f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.78f, 1f);
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f);
        ObjectAnimator titleMove = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 18f, 0f);
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(
            logoScaleX,
            logoScaleY,
            logoAlpha,
            titleAlpha,
            titleMove
        );
        animation.setDuration(700);
        animation.setInterpolator(new AccelerateDecelerateInterpolator());
        animation.start();
        handler.postDelayed(openMain, 2000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openMain);
        super.onDestroy();
    }
}
