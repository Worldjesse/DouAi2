package com.douai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class RecallHighlightHook implements AutoCloseable {
    private final Map<View, Drawable> originals = new WeakHashMap<>();
    private final BehaviorHookGroup registration;
    private final SharedPreferences records;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalOptions options;
    private volatile boolean closed;

    private RecallHighlightHook(Context context, LocalOptions options, Consumer<String> log) {
        this.options = options;
        records = context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        registration = new BehaviorHookGroup("recall_background", log == null ? ignored -> { } : log);
    }

    public static RecallHighlightHook install(XposedInterface framework, HostScanReport report,
                                               ClassLoader loader, Context context, LocalOptions options,
                                               Consumer<String> log) throws Exception {
        RecallHighlightHook hook = new RecallHighlightHook(context, options, log);
        try {
            Method bind = HostTargets.method(report, "adv_im_cell_bind", loader);
            Method message = HostTargets.method(report, "adv_im_cell_message", loader);
            Method uuid = HostTargets.method(report, "adv_im_message_uuid", loader);
            Method account = HostTargets.method(report, "media_account_service", loader);
            Method user = HostTargets.method(report, "media_current_user", loader);
            hook.registration.add(framework, bind, chain -> {
                Object holder = chain.getArg(0);
                Object raw = HostTargets.read(holder, "itemView");
                View view = raw instanceof View ? (View) raw : null;
                if (view != null) hook.restore(view);
                Object result = chain.proceed();
                if (!hook.closed && options != null && hook.options.enabled("prevent_message_recall") && view != null) {
                    try {
                        Object currentUser = user.invoke(account.invoke(null));
                        Object uid = HostTargets.read(currentUser, "uid");
                        Object item = message.invoke(chain.getThisObject(), chain.getArg(1));
                        String id = item == null ? null : (String) uuid.invoke(item);
                        if (uid != null && id != null && hook.records.getBoolean("recall." + uid + "." + id, false)) {
                            hook.originals.put(view, view.getBackground());
                            view.setBackgroundColor(color(hook.options.text("recall_background", "#2051BA55")));
                        }
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        hook.restoreAll();
                        hook.registration.fail(failure);
                    }
                }
                return result;
            });
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    static int color(String value) {
        try { return Color.parseColor(value); }
        catch (RuntimeException ignored) { return Color.parseColor("#2051BA55"); }
    }

    public boolean isActive() { return !closed && registration.isActive(); }

    public void setOptions(LocalOptions options) {
        this.options = options;
        if (!options.enabled("prevent_message_recall")) main.post(this::restoreAll);
    }

    private void restore(View view) {
        if (originals.containsKey(view)) view.setBackground(originals.remove(view));
    }

    private void restoreAll() {
        for (View view : new ArrayList<>(originals.keySet())) if (view != null) restore(view);
    }

    @Override public void close() {
        closed = true;
        registration.close();
        main.post(this::restoreAll);
    }
}
