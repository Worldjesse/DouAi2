package com.ss.android.ugc.aweme.im.business.chat.msgcell.common.bg;

import android.content.res.Resources;
import android.graphics.Rect;

/** Official pure content-inset contract; no Message or CellInteract is needed. */
public final class NewBgContentCellUI {
    public Rect LJJJIL() {
        float density = Resources.getSystem().getDisplayMetrics().density;
        int horizontal = Math.round(12 * density);
        int vertical = Math.round(8 * density);
        return new Rect(horizontal, vertical, horizontal, vertical);
    }
}
