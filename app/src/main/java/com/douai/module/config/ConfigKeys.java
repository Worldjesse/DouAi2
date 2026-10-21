package com.douai.module.config;

import java.util.Map;

/** Compatibility entry point for the version-1 transfer format. */
final class ConfigKeys {
    static final Map<String, String> TYPES = ConfigurationCatalog.transferableTypes();
    static void validate(String key, Object value) { ConfigurationCatalog.validateTransfer(key, value); }
    private ConfigKeys() { }
}
