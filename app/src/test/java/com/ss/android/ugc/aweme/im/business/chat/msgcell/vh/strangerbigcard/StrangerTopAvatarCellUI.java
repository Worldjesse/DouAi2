package com.ss.android.ugc.aweme.im.business.chat.msgcell.vh.strangerbigcard;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.bytedance.ies.dmt.ui.widget.DmtTextView;
import com.ss.android.ugc.exview.ImAvatarImageView;

/** Test representation of the host's pure large-avatar layout, without relationship binding. */
public final class StrangerTopAvatarCellUI {
    public View LJJIIJ(Context context, ViewGroup parent) { return LJJIIJZLJL(context, parent); }
    public View LJJIIJZLJL(Context context, ViewGroup parent) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, Math.round(24 * density), 0, Math.round(16 * density));
        ImAvatarImageView avatar = new ImAvatarImageView(context);
        avatar.setId(com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a10e6));
        root.addView(avatar, new LinearLayout.LayoutParams(Math.round(96 * density), Math.round(96 * density)));
        DmtTextView name = new DmtTextView(context);
        name.setId(com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a8189));
        name.setTextSize(17);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setPadding(0, Math.round(16 * density), 0, 0);
        root.addView(name, new LinearLayout.LayoutParams(-2, -2));
        return root;
    }
}
