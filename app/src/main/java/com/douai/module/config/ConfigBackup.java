package com.douai.module.config;

import android.content.Context;
import com.douai.module.analytics.AnalyticsBackup;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Optional analytics bundle around the unchanged version-1 settings format. All I/O is worker-only. */
public final class ConfigBackup {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final String KIND = "douai-local-backup";
    private ConfigBackup() { }

    public static final class Preview {
        public final ConfigTransfer.Preview config;
        public final boolean includesAnalytics;
        public final String analyticsSummary;
        public final int analyticsRows;
        private final AnalyticsBackup.Snapshot analytics;
        private Preview(ConfigTransfer.Preview config, AnalyticsBackup.Snapshot analytics) {
            this.config = config; this.analytics = analytics; includesAnalytics = analytics != null;
            analyticsSummary = analytics == null ? "此文件不含本地分析数据" : analytics.summary;
            analyticsRows = analytics == null ? 0 : analytics.rowCount;
        }
    }

    public static String export(Context context, SettingsStore store, boolean includeSecrets, boolean includeAnalytics) {
        String settings = ConfigTransfer.export(store, includeSecrets);
        if (!includeAnalytics) return settings; // Do not initialize or read analytics on the default path.
        try {
            String encoded = new JSONObject().put("backupFormatVersion", 1).put("kind", KIND)
                    .put("config", new JSONObject(settings)).put("analytics", AnalyticsBackup.export(context)).toString();
            checkBytes(encoded); return encoded;
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("本地备份无法导出", failure); }
    }

    /** Inspection validates the incoming data without opening current local analytics. */
    public static Preview inspect(SettingsStore store, String encoded, ConfigTransfer.Mode mode) {
        Object value;
        try {
            checkBytes(encoded);
            value = ConfigJson.parse(encoded, MAX_BYTES, AnalyticsBackup.MAX_ROWS);
        } catch (RuntimeException | LinkageError failure) {
            throw BackupValidationException.wrap(BackupValidationException.Section.BUNDLE_JSON,
                    BackupValidationException.Reason.INVALID_JSON, null, failure);
        }
        if (!(value instanceof JSONObject)) throw new BackupValidationException(
                BackupValidationException.Section.BUNDLE_JSON, BackupValidationException.Reason.HEADER_SHAPE, null);
        JSONObject root = (JSONObject) value;
        if (!root.has("backupFormatVersion")) return new Preview(inspectConfig(store, encoded, mode), null);
        if (root.length() != 4 || !root.has("kind") || !root.has("config") || !root.has("analytics"))
            throw new BackupValidationException(BackupValidationException.Section.BUNDLE_HEADER,
                    BackupValidationException.Reason.HEADER_SHAPE, null);
        if (!Long.valueOf(1).equals(root.opt("backupFormatVersion")))
            throw new BackupValidationException(BackupValidationException.Section.BUNDLE_HEADER,
                    BackupValidationException.Reason.FORMAT_VERSION, "backupFormatVersion");
        if (!KIND.equals(root.opt("kind"))) throw new BackupValidationException(
                BackupValidationException.Section.BUNDLE_HEADER, BackupValidationException.Reason.HEADER_SHAPE, "kind");
        if (!(root.opt("config") instanceof JSONObject)) throw new BackupValidationException(
                BackupValidationException.Section.BUNDLE_HEADER, BackupValidationException.Reason.TYPE_MISMATCH, "config");
        if (!(root.opt("analytics") instanceof JSONObject)) throw new BackupValidationException(
                BackupValidationException.Section.BUNDLE_HEADER, BackupValidationException.Reason.TYPE_MISMATCH, "analytics");
        ConfigTransfer.Preview config = inspectConfig(store, root.opt("config").toString(), mode);
        try {
            AnalyticsBackup.Snapshot analytics = AnalyticsBackup.inspect((JSONObject) root.opt("analytics"));
            return new Preview(config, analytics);
        } catch (RuntimeException | LinkageError failure) {
            throw BackupValidationException.wrap(BackupValidationException.Section.ANALYTICS,
                    BackupValidationException.Reason.UNEXPECTED, null, failure);
        }
    }

    private static ConfigTransfer.Preview inspectConfig(SettingsStore store, String encoded, ConfigTransfer.Mode mode) {
        try { return ConfigTransfer.inspect(store, encoded, mode); }
        catch (RuntimeException | LinkageError failure) {
            throw BackupValidationException.wrap(BackupValidationException.Section.CONFIG,
                    BackupValidationException.Reason.UNEXPECTED, null, failure);
        }
    }

    /** Analytics is independently replaced only when selected; settings retain their own merge mode. */
    public static void apply(Context context, SettingsStore store, Preview preview, boolean restoreAnalytics) {
        if (preview == null) throw new IllegalArgumentException("请先预览备份文件");
        if (!restoreAnalytics) { ConfigTransfer.apply(store, preview.config); return; }
        if (!preview.includesAnalytics) throw new IllegalArgumentException("此备份不含本地分析数据");
        ConfigTransfer.requireCurrent(store, preview.config);
        AnalyticsBackup.restore(context, preview.analytics,
                () -> ConfigTransfer.apply(store, preview.config), () -> ConfigTransfer.rollback(store, preview.config));
    }

    private static void checkBytes(String encoded) {
        if (encoded == null) throw ConfigJson.invalid();
        if (encoded.length() > MAX_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new BackupLimitException(BackupLimitException.Section.FILE);
    }
}
