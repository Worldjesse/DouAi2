package com.douai.module.feature;

/** Display identity captured from host models, with an optional native avatar URL. */
public final class ProfileListEntry {
    public final String uid;
    public final String name;
    public final String avatarUrl;
    public final String selectionKey;

    public ProfileListEntry(String uid, String name, String avatarUrl) {
        this(uid, name, avatarUrl, uid);
    }

    private ProfileListEntry(String uid, String name, String avatarUrl, String key) {
        this.uid = uid == null ? "" : uid;
        this.name = name == null ? "" : name;
        this.avatarUrl = avatarUrl != null && AutomationImages.isMediaUrl(avatarUrl) ? avatarUrl : "";
        this.selectionKey = key == null ? "" : key;
    }

    public static ProfileListEntry named(String name) { return new ProfileListEntry("", name, "", name); }

    static String avatar(Object user) {
        Object avatar = DownloadActionHook.jsonValue(user, "avatar_thumb");
        if (avatar == null) avatar = HostTargets.invoke(user, "getAvatarThumb");
        if (avatar == null) avatar = HostTargets.invoke(user, "getDisplayAvatar");
        String url = DownloadActionHook.firstUrlValue(avatar);
        if (url == null) url = DownloadActionHook.firstUrlValue(HostTargets.invoke(avatar, "getUrlList"));
        return url == null ? "" : url;
    }
}
