package com.douai.module.config;

import android.content.Context;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-wide settings repository and LSPosed connection owned by the module app. */
public final class ModuleConfigRuntime {
    private static final Object INSTANCE_LOCK = new Object();
    private static volatile ModuleConfigRuntime instance;

    private final SettingsRepository repository;
    private final FrameworkConfigSync sync;
    private final Set<FrameworkConfigSync.Listener> statusListeners = new CopyOnWriteArraySet<>();
    private final Set<ServiceListener> serviceListeners = new CopyOnWriteArraySet<>();
    private volatile XposedService service;

    private ModuleConfigRuntime(Context context) {
        Context application = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        repository = new SettingsRepository(application);
        sync = new FrameworkConfigSync(this::dispatchStatus);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService boundService) {
                handleServiceBound(boundService);
            }

            @Override
            public void onServiceDied(XposedService deadService) {
                handleServiceDied(deadService);
            }
        });
    }

    public static ModuleConfigRuntime get(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        ModuleConfigRuntime current = instance;
        if (current != null) return current;
        synchronized (INSTANCE_LOCK) {
            current = instance;
            if (current == null) {
                current = new ModuleConfigRuntime(context);
                instance = current;
            }
            return current;
        }
    }

    public SettingsRepository repository() {
        return repository;
    }

    public FrameworkConfigSync sync() {
        return sync;
    }

    public XposedService service() {
        return service;
    }

    public void addStatusListener(FrameworkConfigSync.Listener listener) {
        if (listener != null) statusListeners.add(listener);
    }

    public void removeStatusListener(FrameworkConfigSync.Listener listener) {
        if (listener != null) statusListeners.remove(listener);
    }

    public void addServiceListener(ServiceListener listener) {
        if (listener == null) return;
        serviceListeners.add(listener);
        XposedService current = service;
        if (current != null) listener.onServiceBound(current);
    }

    public void removeServiceListener(ServiceListener listener) {
        if (listener != null) serviceListeners.remove(listener);
    }

    private void handleServiceBound(XposedService boundService) {
        if (boundService == null) {
            sync.onServiceError("LSPosed 返回了空服务");
            return;
        }
        service = boundService;
        sync.onServiceBound(boundService);
        for (ServiceListener listener : serviceListeners) {
            try {
                listener.onServiceBound(boundService);
            } catch (RuntimeException ignored) {
                // A destroyed UI must not break the process-wide service listener.
            }
        }
    }

    private void handleServiceDied(XposedService deadService) {
        if (deadService != null && service != deadService) return;
        service = null;
        sync.onServiceUnbound();
        for (ServiceListener listener : serviceListeners) {
            try {
                listener.onServiceDied(deadService);
            } catch (RuntimeException ignored) {
                // A destroyed UI must not break other module clients.
            }
        }
    }

    private void dispatchStatus(boolean connected, String message, String error) {
        for (FrameworkConfigSync.Listener listener : statusListeners) {
            try {
                listener.onStatusChanged(connected, message, error);
            } catch (RuntimeException ignored) {
                // Status UI callbacks are isolated from configuration delivery.
            }
        }
    }

    public interface ServiceListener {
        void onServiceBound(XposedService service);
        void onServiceDied(XposedService service);
    }
}
