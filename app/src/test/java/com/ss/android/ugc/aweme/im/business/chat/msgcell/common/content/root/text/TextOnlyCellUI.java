package com.ss.android.ugc.aweme.im.business.chat.msgcell.common.content.root.text;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.ss.android.ugc.aweme.im.business.chat.msgcell.vh.textd.IMMsgTextView;

/** Test-only host factory contract recovered from 40.6.0, without cell/session binding. */
public final class TextOnlyCellUI {
    public static int createCalls;

    public View LJJJJL(Context context, ViewGroup parent) {
        createCalls++;
        LinearLayout root = new LinearLayout(context);
        IMMsgTextView text = new IMMsgTextView(context);
        text.setId(0x7f0a1dbf);
        float density = context.getResources().getDisplayMetrics().density;
        text.setLineSpacing(3 * density);
        text.setMaxWidthPx(Math.round(244 * density));
        root.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }
}
