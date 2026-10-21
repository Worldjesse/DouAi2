package com.douai.module.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostFeatureCatalog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

/** Reports static version evidence, rule gaps and installation without claiming runtime success. */
public final class HostAdaptationDiagnostics {
    private HostAdaptationDiagnostics() { }

    public static Bundle readInstalledVersion(Context context) {
        try {
            // Archive parsing is independent of the module's PackageManager version-spoof hook.
            String archive = context.getApplicationInfo().sourceDir;
            if (archive == null) return versionEvidence(null);
            java.io.File apk = new java.io.File(archive);
            com.douai.module.scan.HostApkProfile.Identity identity =
                    com.douai.module.scan.HostApkProfile.readIdentity(apk);
            PackageInfo info = new PackageInfo();
            info.packageName = identity.packageName;
            info.versionName = identity.versionName;
            info.setLongVersionCode(identity.versionCode);
            Bundle result = versionEvidence(info);
            result.putLong("host_update_version_code", identity.updateVersionCode);
            result.putBoolean("host_version_profile_matched",
                    com.douai.module.scan.HostApkProfile.read(apk) != null);
            return result;
        } catch (java.io.IOException | RuntimeException failure) {
            return versionEvidence(null);
        }
    }

    public static Bundle versionEvidence(PackageInfo info) {
        Bundle result = new Bundle();
        boolean available = info != null && info.versionName != null && info.getLongVersionCode() > 0;
        result.putBoolean("host_version_available", available);
        result.putString("host_package", info == null ? null : info.packageName);
        result.putString("host_version_name", info == null ? null : info.versionName);
        result.putLong("host_version_code", info == null ? 0L : info.getLongVersionCode());
        result.putBoolean("host_version_profile_matched", available && HostAdaptation.matchesVersion(
                info.packageName, info.versionName, info.getLongVersionCode()));
        return result;
    }

    public static void append(Bundle snapshot, Bundle version, String rulesFingerprint, HostScanReport report) {
        snapshot.putAll(version);
        boolean versionMatched = version.getBoolean("host_version_profile_matched");
        HostAdaptation.Profile profile = HostAdaptation.forVersion(version.getString("host_package"),
                version.getString("host_version_name"), version.getLong("host_version_code"));
        boolean rulesMatched = profile != null && profile.matchesRules(rulesFingerprint);
        snapshot.putBoolean("host_adaptation_static_verified", versionMatched && rulesMatched);
        snapshot.putBoolean("host_adaptation_rules_verified", rulesMatched);
        snapshot.putString("host_adaptation_rules_asset", profile == null ? "" : profile.rulesAsset);
        snapshot.putString("host_adaptation_rules_fingerprint", rulesFingerprint);
        snapshot.putInt("host_adaptation_resource_count", profile == null ? 0 : profile.getFeedResources().size());
        int gaps = 0;
        int uninstalled = 0;
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            StringBuilder missing = new StringBuilder();
            for (String rule : feature.rules) {
                RuleScanResult result = report == null ? null : report.getResults().get(rule);
                if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
                    if (missing.length() > 0) missing.append(", ");
                    missing.append(rule).append('=').append(result == null
                            ? "MISSING" : result.getStatus().name());
                }
            }
            String prefix = "capability." + feature.id;
            snapshot.putString(prefix + ".rule_gaps", missing.toString());
            snapshot.putBoolean(prefix + ".rules_unique", missing.length() == 0);
            boolean installed = snapshot.getBoolean(feature.installedKey);
            snapshot.putBoolean(prefix + ".installed", installed);
            snapshot.putString(prefix + ".installation_failure", installed ? ""
                    : snapshot.getString("feature." + feature.id + ".failure", ""));
            if (missing.length() > 0) gaps++;
            if (!installed) uninstalled++;
        }
        snapshot.putInt("host_adaptation_feature_rule_gaps", gaps);
        snapshot.putInt("host_adaptation_features_uninstalled", uninstalled);
    }

    /** Installation diagnostics retain contract names, never arbitrary exception payloads. */
    public static String installationFailure(Throwable failure) {
        if (failure == null) return "";
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        String contract = null;
        if (cause instanceof ClassNotFoundException && message != null) {
            contract = missingClassContract(message);
        } else if (message != null && message.length() <= 256) {
            if (cause instanceof NoSuchFieldException && isJavaName(message)) {
                contract = message;
            } else if (cause instanceof NoSuchMethodException) {
                contract = isMethodContract(message) ? message : androidMethodContract(message);
            } else if (cause instanceof IllegalStateException) {
                String prefix = "Host rule is not unique: ";
                if (message.startsWith(prefix)) {
                    String rule = message.substring(prefix.length());
                    for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
                        if (feature.rules.contains(rule)) { contract = prefix + rule; break; }
                    }
                }
            }
        }
        return contract == null ? type : type + ": " + contract;
    }

    private static String missingClassContract(String message) {
        if (message.length() <= 256 && isJavaName(message)) return message;
        String prefix = "Didn't find class \"";
        if (!message.startsWith(prefix)) return null;
        int end = message.indexOf("\" on path: ", prefix.length());
        if (end < 0 || end - prefix.length() > 256) return null;
        String name = message.substring(prefix.length(), end);
        // Android's DexPathList contains local paths; retain only the validated missing class.
        return isJavaName(name) ? name : null;
    }

    private static String androidMethodContract(String message) {
        int arguments = message.indexOf(" [");
        if (arguments < 0 || !message.endsWith("]")) return null;
        String member = message.substring(0, arguments);
        if (!isJavaName(member.replace(".<init>", ".constructor"))) return null;
        String parameters = message.substring(arguments + 2, message.length() - 1);
        StringBuilder contract = new StringBuilder(member).append('(');
        if (!parameters.isEmpty()) {
            for (String parameter : parameters.split(",", -1)) {
                String type = androidParameterType(parameter.trim());
                if (type == null) return null;
                if (contract.charAt(contract.length() - 1) != '(') contract.append(',');
                contract.append(type);
            }
        }
        return contract.append(')').toString();
    }

    private static String androidParameterType(String value) {
        if (value.startsWith("class ")) value = value.substring(6);
        else if (value.startsWith("interface ")) value = value.substring(10);
        else return value.matches("boolean|byte|char|short|int|long|float|double") ? value : null;
        int dimensions = 0;
        while (dimensions < value.length() && value.charAt(dimensions) == '[') dimensions++;
        if (dimensions == 0) return isJavaName(value) ? value : null;
        String element = value.substring(dimensions);
        if (element.startsWith("L") && element.endsWith(";")) {
            element = element.substring(1, element.length() - 1);
            if (!isJavaName(element)) return null;
        } else {
            switch (element) {
                case "Z": element = "boolean"; break;
                case "B": element = "byte"; break;
                case "C": element = "char"; break;
                case "S": element = "short"; break;
                case "I": element = "int"; break;
                case "J": element = "long"; break;
                case "F": element = "float"; break;
                case "D": element = "double"; break;
                default: return null;
            }
        }
        StringBuilder type = new StringBuilder(element);
        for (int index = 0; index < dimensions; index++) type.append("[]");
        return type.toString();
    }

    private static boolean isJavaName(String value) {
        return value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    }

    private static boolean isMethodContract(String value) {
        int arguments = value.indexOf('(');
        if (arguments < 0) return isJavaName(value);
        if (!value.endsWith(")") || !isJavaName(value.substring(0, arguments).replace(".<init>", ".constructor"))) {
            return false;
        }
        String parameters = value.substring(arguments + 1, value.length() - 1);
        if (parameters.isEmpty()) return true;
        for (String parameter : parameters.split(",", -1)) {
            String type = parameter.trim();
            while (type.endsWith("[]")) type = type.substring(0, type.length() - 2);
            if (!isJavaName(type)) return false;
        }
        return true;
    }

    public static String describe(Bundle snapshot) {
        StringBuilder detail = new StringBuilder("宿主版本：");
        long scanMillis = snapshot.getLong("host_scan_millis", -1);
        long heapBytes = snapshot.getLong("host_java_heap_used_bytes", -1);
        if (snapshot.getBoolean("host_version_available")) {
            detail.append(snapshot.getString("host_version_name", "未知"))
                    .append(" / ").append(snapshot.getLong("host_version_code"));
            if (snapshot.getLong("host_update_version_code", -1) >= 0) {
                detail.append(" / 构建 ").append(snapshot.getLong("host_update_version_code"));
            }
        } else detail.append("读取失败，未验证");
        if (snapshot.getBoolean("host_adaptation_static_verified")) {
            detail.append("\n版本和规则配置匹配 ").append(snapshot.getString("host_version_name", "未知"))
                    .append(" 静态验证记录；DEX 规则仍以本次扫描为准。");
        } else if (!snapshot.getBoolean("host_version_profile_matched")) {
            detail.append("\n该宿主版本未验证；唯一规则命中和 Hook 安装不构成版本适配证明。");
        } else {
            detail.append("\n版本与 ").append(snapshot.getString("host_version_name", "未知"))
                    .append(" 一致，当前规则配置未绑定已验证证据。");
        }
        if (scanMillis >= 0) {
            detail.append("\n本次").append(snapshot.getBoolean("cache_hit") ? "缓存读取" : "规则扫描")
                    .append("耗时：").append(scanMillis).append(" ms。");
        }
        if (heapBytes >= 0) detail.append("\n扫描结束时宿主 Java 堆占用：")
                .append(heapBytes / (1024 * 1024)).append(" MiB（包含宿主，不能单独归因于模块）。");
        int resourceCount = snapshot.getInt("host_adaptation_resource_count");
        detail.append("\n资源映射：").append(resourceCount)
                .append(" 项 Feed ID；按运行时包名和资源名校验。");
        int mismatches = snapshot.getInt("feed_ui_resource_mismatches");
        if (mismatches > 0) detail.append("已跳过 ").append(mismatches).append(" 个不符的组件 ID。");
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            String prefix = "capability." + feature.id;
            String gaps = snapshot.getString(prefix + ".rule_gaps", "");
            if (!gaps.isEmpty()) {
                detail.append('\n').append(feature.label).append("规则缺口：").append(gaps);
            }
            String failure = snapshot.getString(prefix + ".installation_failure", "");
            if (!failure.isEmpty()) {
                detail.append('\n').append(feature.label).append("：Hook 安装失败：").append(failure);
            } else if (gaps.isEmpty() && snapshot.containsKey(prefix + ".installed")
                    && !snapshot.getBoolean(prefix + ".installed")) {
                detail.append('\n').append(feature.label).append("：规则唯一命中，Hook 未安装。");
            }
        }
        if (snapshot.containsKey("hidden_skip_enabled")) {
            detail.append("\n视频跳过保护：开关")
                    .append(snapshot.getBoolean("hidden_skip_enabled") ? "开启" : "关闭")
                    .append(snapshot.getBoolean("hidden_skip_installed") ? "，四处 Hook 完整安装。" : "，Hook 未完整安装。")
                    .append("\n当前安全召回：命中 ").append(hiddenCount(snapshot, "hidden_skip_current_hits"))
                    .append("，拦截 ").append(hiddenCount(snapshot, "hidden_skip_current_blocked"))
                    .append("；后台自动移除：命中 ").append(hiddenCount(snapshot, "hidden_skip_background_hits"))
                    .append("，拦截 ").append(hiddenCount(snapshot, "hidden_skip_background_blocked"))
                    .append("。计数随宿主进程重启清零。");
        }
        return detail.append("\n功能开关与触发计数单独报告；点击、发送及其他实际行为仍需真机确认。").toString();
    }

    private static long hiddenCount(Bundle snapshot, String key) {
        try { return Math.max(0, Math.min(1_000_000_000L, snapshot.getLong(key))); }
        catch (RuntimeException unavailable) { return 0; }
    }
}
