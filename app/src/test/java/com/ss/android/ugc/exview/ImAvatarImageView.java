package com.ss.android.ugc.exview;

import android.content.Context;
import android.widget.ImageView;
import android.graphics.Canvas;
import android.graphics.Path;

/** Test-only local image path of the host avatar widget. */
public final class ImAvatarImageView extends ImageView {
    public ImAvatarImageView(Context context) { super(context); }

    @Override protected void onDraw(Canvas canvas) {
        // Bitmap Canvas has no RenderNode outline clipping. Mirror that enabled host
        // hardware/Fresco behavior for the explicitly labelled local layout preview.
        if (!getClipToOutline()) { super.onDraw(canvas); return; }
        int checkpoint = canvas.save();
        Path oval = new Path();
        oval.addOval(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        canvas.clipPath(oval);
        super.onDraw(canvas);
        canvas.restoreToCount(checkpoint);
    }
}
