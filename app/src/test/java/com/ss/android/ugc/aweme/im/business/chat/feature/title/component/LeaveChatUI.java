package com.ss.android.ugc.aweme.im.business.chat.feature.title.component;

import android.content.Context;
import android.widget.ImageView;

/** Host back icon's ordinary-font padding contract. */
public final class LeaveChatUI {
    public static void LJLIIL(Context context, ImageView image) {
        float density = context.getResources().getDisplayMetrics().density;
        image.setPadding(Math.round(15 * density), Math.round(9 * density),
                Math.round(3 * density), Math.round(9 * density));
    }
}
