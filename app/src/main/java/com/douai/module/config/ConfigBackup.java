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
        checkBytes(encoded);
        Object value = ConfigJson.parse(encoded, MAX_BYTES, AnalyticsBackup.MAX_ROWS);
        if (!(value instanceof JSONObject)) throw ConfigJson.invalid();
        JSONObject root = (JSONObject) value;
        if (!root.has("backupFormatVersion")) return new Preview(ConfigTransfer.inspect(store, encoded, mode), null);
        try {
            if (root.length() != 4 || !Long.valueOf(1).equals(root.get("backupFormatVersion"))
                    || !KIND.equals(root.get("kind")) || !root.has("config") || !root.has("analytics"))
                throw new IllegalArgumentException("不支持此本地备份格式");
            ConfigTransfer.Preview config = ConfigTransfer.inspect(store, root.getJSONObject("config").toString(), mode);
            AnalyticsBackup.Snapshot analytics = AnalyticsBackup.inspect(root.getJSONObject("analytics"));
            return new Preview(config, analytics);
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw ConfigJson.invalid(); }
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
        if (encoded == null || encoded.length() > MAX_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("备份为空或超过 32 MiB 限制");
    }
}
