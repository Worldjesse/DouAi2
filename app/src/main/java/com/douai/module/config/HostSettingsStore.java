package com.douai.module.config;

import android.content.Context;

/** Dyyds-style in-host settings backed by the target app's own private preferences. */
public final class HostSettingsStore implements SettingsStore {
    private final SettingsRepository repository;

    public HostSettingsStore(Context hostContext) {
        repository = new SettingsRepository(hostContext);
    }

    @Override public FeatureSettings load() { return repository.load(); }
    @Override public void save(FeatureSettings settings) { repository.save(settings); }
    @Override public boolean getBoolean(String key, boolean fallback) {
        return repository.getBoolean(key, fallback);
    }
    @Override public String getString(String key, String fallback) {
        return repository.getString(key, fallback);
    }
    @Override public void putBoolean(String key, boolean value) { repository.putBoolean(key, value); }
    @Override public void putString(String key, String value) { repository.putString(key, value); }
    @Override public boolean isInHost() { return true; }
}
