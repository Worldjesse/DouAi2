package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Identity scopes and content filters are independent gates for replies and comments. */
public final class ContentScopePolicy {
    public static final String OFF = "off";
    public static final String INCLUDE = "include";
    public static final String EXCLUDE = "exclude";
    private ContentScopePolicy() { }

    private static String targetsKey(String prefix) { return "reply".equals(prefix) ? "reply_targets" : prefix + "_scope_users"; }

    private static String legacyMode(String prefix, LocalOptions options) {
        String mode = options.text(prefix + "_scope_mode", "").trim();
        if (mode.isEmpty()) mode = "reply".equals(prefix) && !options.enabled("reply_global") ? "whitelist" : "all";
        return mode;
    }

    public static String scopeMode(String prefix, LocalOptions options) {
        String mode = legacyMode(prefix, options);
        // Keyword-only legacy whitelists selected content, without restricting its authors.
        if (options.text(prefix + "_filter_mode", "").isEmpty() && "whitelist".equals(mode)
                && !options.text(prefix + "_scope_keywords", "").trim().isEmpty()
                && AutomationScopePolicy.targets(options.text(targetsKey(prefix), "")).isEmpty()
                && AutomationScopePolicy.targets(options.text(prefix + "_scope_relations", "")).isEmpty()) return "all";
        return mode;
    }

    public static String filterMode(String prefix, LocalOptions options) {
        String mode = options.text(prefix + "_filter_mode", "").trim();
        if (!mode.isEmpty()) return mode;
        if (options.text(prefix + "_scope_keywords", "").trim().isEmpty()) return OFF;
        String legacy = legacyMode(prefix, options);
        return "whitelist".equals(legacy) ? INCLUDE : "blacklist".equals(legacy) ? EXCLUDE : OFF;
    }

    public static String keywords(String prefix, LocalOptions options) {
        return options.text(prefix + "_filter_keywords", options.text(prefix + "_scope_keywords", ""));
    }

    public static String fields(String prefix, LocalOptions options) {
        Set<String> fields = new LinkedHashSet<>();
        String selected = options.text(prefix + "_filter_fields", null);
        boolean legacy = selected == null;
        if (legacy) selected = options.text(prefix + "_scope_fields", "");
        for (String field : AutomationScopePolicy.targets(selected)) {
            if (AutomationScopePolicy.AUTHOR.equals(field) || AutomationScopePolicy.CAPTION.equals(field)) fields.add(field);
            if (AutomationScopePolicy.TITLE.equals(field)) {
                fields.add("reply".equals(prefix) ? AutomationScopePolicy.CAPTION : AutomationScopePolicy.TITLE);
            }
        }
        if (fields.isEmpty() && legacy && options.text(prefix + "_scope_keywords", "").trim().isEmpty()) {
            fields.add(AutomationScopePolicy.CAPTION);
        }
        return String.join(",", fields);
    }

    public static boolean allows(String prefix, LocalOptions options, String uid, String author, String caption,
                                 String title, AutomationScopePolicy.Relation relation) {
        Map<String, Object> identities = new LinkedHashMap<>();
        identities.put(prefix + "_scope_mode", scopeMode(prefix, options));
        identities.put(prefix + "_scope_relations", options.text(prefix + "_scope_relations", ""));
        identities.put(targetsKey(prefix), options.text(targetsKey(prefix), ""));
        if (!AutomationScopePolicy.allows(prefix, new LocalOptions(identities), uid, author, "", "", relation)) return false;
        String filter = filterMode(prefix, options);
        if (OFF.equals(filter)) return true;
        if (!INCLUDE.equals(filter) && !EXCLUDE.equals(filter)) return false;
        boolean match = AutomationScopePolicy.keywordMatch(keywords(prefix, options), fields(prefix, options), author, caption, title);
        return INCLUDE.equals(filter) ? match : !match;
    }
}
