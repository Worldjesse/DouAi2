package com.douai.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

/**
 * A module-owned foreground handoff. Android 14 permits this visible activity to
 * start the host after the host process has been asked to terminate.
 */
public final class HostRestartActivity extends Activity {
    private static final long HOST_RESTART_DELAY_MILLIS = 800L;
    private boolean launched;
    private ResultReceiver receiver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Intent hostLaunch;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        receiver = getReceiver(getIntent());
        String targetPackage = getIntent().getStringExtra(HostAppRestarter.EXTRA_TARGET_PACKAGE);
        String identity = getIntent().getStringExtra(HostAppRestarter.EXTRA_IDENTITY);
        if (!HostAppRestarter.ACTION_RESTART_RELAY.equals(getIntent().getAction())
                || !HostAppRestarter.HOST_PACKAGE.equals(targetPackage)
                || !validIdentity(identity) || receiver == null) {
            finish();
            return;
        }
        setTitle("正在重启抖音");
    }

    private static boolean validIdentity(String identity) {
        if (identity == null || identity.length() != 64) return false;
        for (int i = 0; i < identity.length(); i++) {
            char c = identity.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (launched || receiver == null) return;
        launched = true;
        Intent launch = getPackageManager().getLaunchIntentForPackage(HostAppRestarter.HOST_PACKAGE);
        if (launch == null || launch.getComponent() == null
                || !HostAppRestarter.HOST_PACKAGE.equals(launch.getComponent().getPackageName())) {
            receiver.send(1, null);
            finish();
            return;
        }
        launch.setPackage(HostAppRestarter.HOST_PACKAGE);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        hostLaunch = launch;
        receiver.send(0, null);
        main.postDelayed(this::launchHost, HOST_RESTART_DELAY_MILLIS);
    }

    private void launchHost() {
        if (isFinishing() || isDestroyed() || hostLaunch == null) return;
        Intent launch = hostLaunch;
        hostLaunch = null;
        try {
            startActivity(launch);
        } catch (RuntimeException failure) {
            finish();
            return;
        }
        finish();
    }

    private static ResultReceiver getReceiver(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(HostAppRestarter.EXTRA_RESULT_RECEIVER,
                    ResultReceiver.class);
        }
        @SuppressWarnings("deprecation")
        ResultReceiver legacy = intent.getParcelableExtra(HostAppRestarter.EXTRA_RESULT_RECEIVER);
        return legacy;
    }
}
