package com.ss.android.ugc.aweme.im.business.chat.feature.title.component;

import android.content.Context;
import android.widget.TextView;
import com.bytedance.ies.dmt.ui.widget.DmtTextView;

/** Test-only static title view factory contract. */
public final class ChatTitleUI {
    public static int createCalls;

    public static TextView LJLIIL(int id, Context context) {
        createCalls++;
        TextView title = new DmtTextView(context);
        title.setId(id);
        title.setTextSize(15);
        title.setTextColor(0xff161823);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setMaxLines(1);
        return title;
    }
}
