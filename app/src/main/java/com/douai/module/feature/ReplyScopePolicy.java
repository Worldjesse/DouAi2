package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

/** Private chat identities and message filtering are separate, cumulative gates. */
public final class ReplyScopePolicy {
    public static final String OFF = "off";
    public static final String INCLUDE = "include";
    public static final String EXCLUDE = "exclude";
    private ReplyScopePolicy() { }

    public static String scopeMode(LocalOptions options) {
        return ContentScopePolicy.scopeMode("reply", options);
    }

    public static String filterMode(LocalOptions options) {
        return ContentScopePolicy.filterMode("reply", options);
    }

    public static String keywords(LocalOptions options) {
        return ContentScopePolicy.keywords("reply", options);
    }

    public static String fields(LocalOptions options) {
        return ContentScopePolicy.fields("reply", options);
    }

    public static boolean allows(LocalOptions options, String uid, String author, String text,
                                 String title, AutomationScopePolicy.Relation relation) {
        String message = (text == null ? "" : text) + (title == null || title.isEmpty() ? "" : "\n" + title);
        return ContentScopePolicy.allows("reply", options, uid, author, message, "", relation);
    }
}
