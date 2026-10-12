package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Changes only local download presentation for media URLs already present in a feed item. */
public final class MediaModelHook implements AutoCloseable {
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, List<Change>> changes = new WeakHashMap<>();
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private boolean logged;

    MediaModelHook(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log;
    }

    public static MediaModelHook install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, LocalOptions options,
                                          Consumer<String> log) throws Exception {
        MediaModelHook hook = new MediaModelHook(options, log == null ? ignored -> { } : log);
        try {
            HostTargets.type(report, "media_video_control", loader);
            for (String id : new String[]{"feed_item_bind_aweme", "feed_item_bind_aweme_position"}) {
                Method bind = HostTargets.method(report, id, loader);
                XposedInterface.HookHandle handle = framework.hook(bind)
                        .setId("DouAi.MediaModel." + id)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            if (hook.options.enabled("remove_download_limits")) {
                                try { hook.apply(chain.getArg(0)); }
                                catch (ReflectiveOperationException | RuntimeException failure) {
                                    hook.safeLog("Download model update failed: " + failure.getMessage());
                                }
                            }
                            return chain.proceed();
                        });
                if (handle == null) throw new IllegalStateException("Empty media model handle");
                hook.handles.add(handle);
            }
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    public void setOptions(LocalOptions options) {
        this.options = options;
        if (!options.enabled("remove_download_limits")) restore();
    }

    void apply(Object aweme) throws ReflectiveOperationException {
        if (aweme == null || DownloadActionHook.mediaAssets(aweme, false, options).isEmpty()) return;
        synchronized (changes) {
            if (changes.containsKey(aweme)) return;
            Object control = HostTargets.read(aweme, "videoControl");
            if (control == null) return;
            List<Change> pending = new ArrayList<>();
            try {
                change(control, "allowDownload", Boolean.TRUE, pending);
                change(control, "downloadIgnoreVisibility", Boolean.TRUE, pending);
                change(control, "preventDownloadType", 0, pending);
                changes.put(aweme, pending);
                if (!logged) {
                    logged = true;
                    safeLog("Enabled local download presentation for an item with existing media URLs");
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                for (Change change : pending) change.restore();
                throw failure;
            }
        }
    }

    private static void change(Object target, String name, Object value, List<Change> changes)
            throws ReflectiveOperationException {
        Field field = HostTargets.field(target.getClass(), name);
        Object previous = field.get(target);
        if (java.util.Objects.equals(previous, value)) return;
        field.set(target, value);
        changes.add(new Change(target, field, previous, value));
    }

    private void restore() {
        synchronized (changes) {
            for (List<Change> values : changes.values()) {
                for (Change change : values) {
                    try { change.restore(); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        safeLog("Download presentation restore failed: " + failure.getMessage());
                    }
                }
            }
            changes.clear();
        }
    }

    private static final class Change {
        final WeakReference<Object> target;
        final Field field;
        final Object original;
        final Object applied;
        Change(Object target, Field field, Object original, Object applied) {
            this.target = new WeakReference<>(target);
            this.field = field;
            this.original = original;
            this.applied = applied;
        }
        void restore() throws ReflectiveOperationException {
            Object owner = target.get();
            if (owner != null && java.util.Objects.equals(field.get(owner), applied)) field.set(owner, original);
        }
    }

    @Override public void close() {
        options = new LocalOptions(Collections.emptyMap());
        restore();
        for (int i = handles.size() - 1; i >= 0; i--) {
            try { handles.get(i).unhook(); }
            catch (Throwable failure) { safeLog("Download model unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }
}
