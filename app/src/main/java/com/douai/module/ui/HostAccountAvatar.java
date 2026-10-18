package com.douai.module.ui;

import android.content.Context;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads the signed-in user's avatar model; the host image loader owns caching and loading. */
final class HostAccountAvatar {
    private final Object avatar;
    private final Method load;

    private HostAccountAvatar(Object avatar, Method load) { this.avatar = avatar; this.load = load; }

    static HostAccountAvatar read(Context context) {
        try {
            ClassLoader loader = context.getClassLoader();
            Method serviceMethod = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService", false, loader)
                    .getMethod("userService");
            Object service = serviceMethod.invoke(null);
            Object user = serviceMethod.getReturnType().getMethod("getCurUser").invoke(service);
            if (user == null) return null;
            Field thumb = user.getClass().getField("avatarThumb");
            Object avatar = thumb.get(user);
            if (avatar == null) return null;
            Method load = Class.forName("com.ss.android.ugc.aweme.im.sdk.common.ImFrescoHelper", false, loader)
                    .getMethod("LJIIIZ", Class.forName("com.ss.android.ugc.aweme.base.ui.AvatarImageView", false, loader),
                            thumb.getType(), boolean.class, int.class, String.class, int.class);
            return new HostAccountAvatar(avatar, load);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    boolean bind(ImageView view) {
        try { load.invoke(null, view, avatar, false, 0, "DouAi.NativeChat", 0); return true; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
}
