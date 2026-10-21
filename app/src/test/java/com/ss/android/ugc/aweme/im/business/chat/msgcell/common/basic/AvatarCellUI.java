package com.ss.android.ugc.aweme.im.business.chat.msgcell.common.basic;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.ss.android.ugc.exview.ImAvatarImageView;

/** Pure avatar factory shape recovered from host 40.6.0, with no session lifecycle. */
public final class AvatarCellUI {
    private Context context;
    public View LJJIIJ(Context context, ViewGroup parent) { return LJJIIJZLJL(context, parent); }
    public View LJJIIJZLJL(Context context, ViewGroup parent) {
        this.context = context;
        return new ImAvatarImageView(context);
    }
    public ViewGroup.LayoutParams getLayoutParams() {
        int size = Math.round(36 * context.getResources().getDisplayMetrics().density);
        return new LinearLayout.LayoutParams(size, size);
    }
}
