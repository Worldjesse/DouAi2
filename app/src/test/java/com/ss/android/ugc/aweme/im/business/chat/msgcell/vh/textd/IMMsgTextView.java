package com.ss.android.ugc.aweme.im.business.chat.msgcell.vh.textd;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test-only shape of the host text renderer; deliberately has no IM session or send API. */
public final class IMMsgTextView extends LinearLayout {
    private final TextView text;

    public IMMsgTextView(Context context) {
        super(context);
        text = new TextView(context);
        addView(text, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setText(CharSequence value) { text.setText(value); }
    public CharSequence getText() { return text.getText(); }
    public TextView getRealTextView() { return text; }
    public void setTextColor(int color) { text.setTextColor(color); }
    public void setTextSizePx(float size) { text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size); }
    public void setMaxWidthPx(int width) { text.setMaxWidth(width); }
    public void setMinWidthPx(int width) { text.setMinWidth(width); }
    public void setLineSpacing(float extra) { text.setLineSpacing(extra, 1f); }
}
