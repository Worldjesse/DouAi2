package com.douai.module.diagnostics;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import com.douai.module.BuildConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only host-to-module diagnostics over a signature-protected ordered broadcast. */
public final class ScanDiagnosticsChannel {
    public static final String HOST_PACKAGE = "com.ss.android.ugc.aweme";
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".action.REQUEST_SCAN_DIAGNOSTICS";
    public static final String PERMISSION = BuildConfig.APPLICATION_ID + ".permission.REQUEST_SCAN_DIAGNOSTICS";
    public static final long REQUEST_TIMEOUT_MILLIS = 2500L;

    private ScanDiagnosticsChannel() { }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static BroadcastReceiver registerHost(Context hostContext, SnapshotProvider snapshotProvider) {
        if (hostContext == null || snapshotProvider == null) throw new IllegalArgumentException();
        IntentFilter filter = new IntentFilter(ACTION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
                Bundle snapshot = snapshotProvider.snapshot();
                Bundle result = new Bundle();
                result.putBundle(HOST_PACKAGE, snapshot == null ? new Bundle() : new Bundle(snapshot));
                setResultExtras(result);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            hostContext.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            hostContext.registerReceiver(receiver, filter, PERMISSION, null);
        }
        return receiver;
    }

    /** Queries only a running host process and never launches the target application. */
    public static void request(Context moduleContext, Handler callbackHandler, Callback callback) {
        requestForHost(moduleContext, HOST_PACKAGE, callbackHandler, callback);
    }

    static void requestForHost(Context moduleContext, String hostPackage,
                               Handler callbackHandler, Callback callback) {
        if (moduleContext == null || hostPackage == null || callbackHandler == null || callback == null) {
            throw new IllegalArgumentException();
        }
        AtomicBoolean completed = new AtomicBoolean();
        Runnable timeout = () -> {
            if (completed.compareAndSet(false, true)) callback.onResult(null);
        };
        callbackHandler.postDelayed(timeout, REQUEST_TIMEOUT_MILLIS);

        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!completed.compareAndSet(false, true)) return;
                callbackHandler.removeCallbacks(timeout);
                Bundle extras = getResultExtras(false);
                Bundle snapshot = extras == null ? null : extras.getBundle(HOST_PACKAGE);
                callback.onResult(snapshot == null ? null : new Bundle(snapshot));
            }
        };
        Intent intent = new Intent(ACTION).setPackage(hostPackage)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        try {
            moduleContext.sendOrderedBroadcast(intent, null, resultReceiver, callbackHandler,
                    0, null, null);
        } catch (RuntimeException exception) {
            if (completed.compareAndSet(false, true)) {
                callbackHandler.removeCallbacks(timeout);
                callback.onResult(null);
            }
        }
    }

    public interface SnapshotProvider {
        Bundle snapshot();
    }

    public interface Callback {
        void onResult(Bundle snapshot);
    }
}
