package com.douai.module.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Compact comment queue status; private comment content is shown only in the detail dialog. */
public final class CommentRingView extends View {
    public static final int DIAMETER_DP = 18;
    public static final int TOUCH_SIZE_DP = 32;
    private static final int TEAL = Color.rgb(112, 229, 211);
    private static final int AMBER = Color.rgb(255, 201, 113);
    private static final int RED = Color.rgb(255, 127, 144);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final String title;
    private final int primaryColor;
    private final boolean reply;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!animates()) return;
            invalidate();
            postOnAnimationDelayed(this, 32);
        }
    };
    private int queueCount;
    private int retriesLeft;
    private String phase = "waiting";
    private float progress = -1;

    public CommentRingView(Context context) {
        this(context, "automation.comment.ring", "自动评论", TEAL, false);
    }

    CommentRingView(Context context, String tag, String title, int color, boolean reply) {
        super(context);
        this.title = title;
        this.primaryColor = color;
        this.reply = reply;
        setTag(tag);
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                int diameter = Math.round(dp(DIAMETER_DP));
                int left = (view.getWidth() - diameter) / 2;
                int top = (view.getHeight() - diameter) / 2;
                outline.setOval(left, top, left + diameter, top + diameter);
                outline.setAlpha(.22f);
            }
        });
        setElevation(dp(3));
        updateDescription();
    }

    public void setState(int queueCount, int retriesLeft, String phase, float progress) {
        this.queueCount = Math.max(0, queueCount);
        this.retriesLeft = Math.max(0, retriesLeft);
        this.phase = phase == null ? "waiting" : phase;
        this.progress = Float.isFinite(progress) && progress >= 0 ? Math.min(1, progress) : -1;
        updateDescription();
        removeCallbacks(frame);
        if (animates()) postOnAnimation(frame);
        invalidate();
    }

    public int getQueueCount() { return queueCount; }
    public int getRetriesLeft() { return retriesLeft; }
    public String getPhase() { return phase; }
    public int getPrimaryColor() { return primaryColor; }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean performLongClick() {
        return getParent() != null && isLongClickable() && super.performLongClick();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int diameter = Math.round(dp(TOUCH_SIZE_DP));
        setMeasuredDimension(resolveSize(diameter, widthMeasureSpec), resolveSize(diameter, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float diameter = Math.min(dp(DIAMETER_DP), Math.min(getWidth(), getHeight()));
        float scale = diameter / dp(DIAMETER_DP);
        if (scale <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - diameter) / 2, (getHeight() - diameter) / 2);
        canvas.scale(scale, scale);
        float center = dp(DIAMETER_DP / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isPressed() ? Color.argb(246, 29, 34, 38) : Color.argb(232, 22, 26, 30));
        canvas.drawCircle(center, center, dp(8), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.35f));
        paint.setColor(Color.argb(135, 223, 237, 236));
        canvas.drawCircle(center, center, dp(7.8f), paint);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(Color.argb(72, 223, 237, 236));
        canvas.drawCircle(center, center, dp(8.6f), paint);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(accent());
        arc.set(dp(.4f), dp(.4f), dp(17.6f), dp(17.6f));
        float angle = progress < 0 && animates() ? (SystemClock.uptimeMillis() % 1800) / 5f - 90 : -90;
        float sweep = progress < 0 ? (animates() ? 108 : 360) : progress * 360;
        if (sweep > 0) canvas.drawArc(arc, angle, sweep, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(249, 251, 250));
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawText(canvas, queueCount > 99 ? "99+" : Integer.toString(queueCount), 7.5f, 6.3f, 11);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setColor("failed".equals(phase) ? RED : AMBER);
        drawText(canvas, Integer.toString(retriesLeft), 5.5f, 12.8f, 9);
        canvas.restore();
    }

    private void drawText(Canvas canvas, String value, float size, float centerY, float maxWidth) {
        float fontScale = Math.max(1, Math.min(1.1f, getResources().getConfiguration().fontScale));
        paint.setTextSize(dp(size) * fontScale);
        float width = paint.measureText(value);
        if (width > dp(maxWidth)) paint.setTextSize(paint.getTextSize() * dp(maxWidth) / width);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, dp(DIAMETER_DP / 2f), dp(centerY) - (metrics.ascent + metrics.descent) / 2, paint);
    }

    private int accent() {
        if ("failed".equals(phase)) return RED;
        if ("retrying".equals(phase)) return AMBER;
        if ("submitted".equals(phase)) return AMBER;
        if ("details".equals(phase)) return Color.rgb(224, 238, 234);
        return primaryColor;
    }

    private boolean animates() {
        return isAttachedToWindow() && getVisibility() == VISIBLE && progress < 0
                && !"details".equals(phase) && !"failed".equals(phase) && !"submitted".equals(phase);
    }

    private void updateDescription() {
        String status;
        switch (phase) {
            case "generating": status = "生成中"; break;
            case "preview": status = reply ? "等待回复" : "等待评论"; break;
            case "details": status = "已暂停，等待确认"; break;
            case "publishing": status = reply ? "发送中" : "发布中"; break;
            case "retrying": status = "重试中"; break;
            case "failed": status = reply ? "回复失败" : "评论失败"; break;
            case "submitted": status = "已提交，结果待确认"; break;
            default: status = "等待处理";
        }
        setContentDescription(title + "，队列 " + queueCount + (reply ? "，倒计时 " + retriesLeft + " 秒，" : "，剩余重试 " + retriesLeft + "，") + status);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(frame);
        if (animates()) postOnAnimation(frame);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame == null) return;
        removeCallbacks(frame);
        if (animates()) postOnAnimation(frame);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
