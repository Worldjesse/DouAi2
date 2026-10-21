package com.douai.module.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import com.douai.module.ui.ModuleTheme;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Quiet, labelled task badges; message content stays in the detail dialog. */
public final class CommentRingView extends View {
    public static final int DIAMETER_DP = 18;
    public static final int TOUCH_SIZE_DP = 32;
    public static final int TOUCH_WIDTH_DP = 32;
    public static final int REPLY_COLOR = Color.rgb(120, 174, 239);
    public static final int COMMENT_COLOR = Color.rgb(222, 168, 98);
    private static final int AMBER = Color.rgb(255, 201, 113);
    private static final int RED = Color.rgb(255, 127, 144);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Path icon = new Path();
    private final String title;
    private final int primaryColor;
    private final boolean reply;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!animates()) return;
            invalidate();
            postOnAnimationDelayed(this, 120);
        }
    };
    private int queueCount;
    private int retriesLeft;
    private String phase = "waiting";
    private float progress = -1;
    private boolean ambient;

    public CommentRingView(Context context) {
        this(context, "automation.comment.ring", "自动评论", COMMENT_COLOR, false);
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
                int left = (view.getWidth() - Math.round(dp(DIAMETER_DP))) / 2;
                int top = (view.getHeight() - Math.round(dp(DIAMETER_DP))) / 2;
                outline.setRoundRect(left, top, left + Math.round(dp(DIAMETER_DP)),
                        top + Math.round(dp(DIAMETER_DP)), dp(reply ? 9 : 4));
                outline.setAlpha(.12f);
            }
        });
        setElevation(dp(2));
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
    public String getLabel() { return reply ? "回复" : "评论"; }

    void setAmbient(boolean value) {
        if (ambient == value) return;
        ambient = value;
        removeCallbacks(frame);
        if (animates()) postOnAnimation(frame);
        invalidate();
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean performLongClick() {
        return getParent() != null && isLongClickable() && super.performLongClick();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(Math.round(dp(TOUCH_WIDTH_DP)), widthMeasureSpec),
                resolveSize(Math.round(dp(TOUCH_SIZE_DP)), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        boolean dark = ModuleTheme.isDark(getContext());
        canvas.save();
        canvas.scale(getWidth() / dp(TOUCH_WIDTH_DP), getHeight() / dp(TOUCH_SIZE_DP));
        canvas.clipRect(dp(7), dp(7), dp(25), dp(25));
        float radius = dp(reply ? 9 : 4);
        arc.set(dp(7.4f), dp(7.4f), dp(24.6f), dp(24.6f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dark ? Color.argb(221, 25, 29, 35) : Color.argb(228, 250, 249, 247));
        if (isPressed()) paint.setColor(dark ? Color.rgb(45, 52, 61) : Color.rgb(234, 237, 240));
        canvas.drawRoundRect(arc, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.65f));
        paint.setColor(accent());
        canvas.drawRoundRect(arc, radius, radius, paint);
        paint.setColor(accent());
        paint.setStrokeWidth(dp(.9f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (reply) {
            arc.set(dp(10), dp(10), dp(16.2f), dp(13.8f));
            canvas.drawRoundRect(arc, dp(1.3f), dp(1.3f), paint);
            icon.reset(); icon.moveTo(dp(11.5f), dp(13.8f)); icon.lineTo(dp(10.8f), dp(15.1f)); icon.lineTo(dp(14), dp(13.8f));
            canvas.drawPath(icon, paint);
        } else {
            canvas.drawLine(dp(10.5f), dp(14.6f), dp(15.1f), dp(10), paint);
            canvas.drawLine(dp(10), dp(15.6f), dp(12.3f), dp(15), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(dark ? Color.rgb(232, 235, 239) : Color.rgb(42, 49, 58));
        drawText(canvas, queueCount > 99 ? "99+" : Integer.toString(queueCount), 8, 18, 17.5f, 10);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(Color.argb(dark ? 45 : 35, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)));
        canvas.drawLine(dp(11), dp(22), dp(21), dp(22), paint);
        paint.setColor(accent());
        float start = progress >= 0 ? 11 : ambient ? 14 : 11 + (SystemClock.uptimeMillis() % 3600) / 3600f * 7;
        float end = progress >= 0 ? 11 + progress * 10 : start + 3;
        canvas.drawLine(dp(start), dp(22), dp(end), dp(22), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        canvas.restore();
    }

    private void drawText(Canvas canvas, String value, float size, float centerX, float centerY, float maxWidth) {
        float fontScale = Math.max(1, Math.min(1.1f, getResources().getConfiguration().fontScale));
        paint.setTextSize(dp(size) * fontScale);
        float width = paint.measureText(value);
        if (width > dp(maxWidth)) paint.setTextSize(paint.getTextSize() * dp(maxWidth) / width);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, dp(centerX), dp(centerY) - (metrics.ascent + metrics.descent) / 2, paint);
    }

    private int accent() {
        if ("failed".equals(phase)) return RED;
        if ("retrying".equals(phase)) return AMBER;
        if ("submitted".equals(phase)) return AMBER;
        return primaryColor;
    }

    private boolean animates() {
        return !ambient && isAttachedToWindow() && getVisibility() == VISIBLE && progress < 0
                && !"details".equals(phase) && !"failed".equals(phase) && !"submitted".equals(phase);
    }

    private void updateDescription() {
        String status;
        switch (phase) {
            case "generating": status = "生成中"; break;
            case "media": status = "读取素材中"; break;
            case "speech": status = "语音合成中"; break;
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
