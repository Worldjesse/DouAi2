package com.douai.module.feature;

/** Shared host media classification for the choice dialog and the encoded-save plan. */
final class OfficialMediaModel {
    private OfficialMediaModel() { }

    static boolean isLivePhoto(Object aweme, Object image) {
        if (number(image, "clip_type", "clipType") == 5) return true;
        // The host also labels an entire work with is_live_photo. Require this item's
        // own motion source so that static pages in a mixed work remain static.
        return workLiveFlag(aweme) == 1 && DownloadActionHook.findPlaybackVideoUrl(image) != null;
    }

    static int workLiveFlag(Object aweme) {
        return number(aweme, "is_live_photo", "isLivePhoto");
    }

    private static int number(Object target, String jsonName, String fieldName) {
        Object value = DownloadActionHook.jsonValue(target, jsonName);
        if (!(value instanceof Number)) value = DownloadActionHook.jsonValue(target, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }
}
