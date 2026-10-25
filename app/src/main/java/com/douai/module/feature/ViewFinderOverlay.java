package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.CustomViewLocator;
import com.douai.module.ui.ModuleTheme;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixViewFinderSurface;
import com.douai.module.ui.miuix.MiuixViewTreeDialog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Owns bounded host inspection and canvas bounds; all visible controls use the module's Miuix UI. */
@SuppressLint({"ViewConstructor", "SetTextI18n"})
public final class ViewFinderOverlay extends FrameLayout
        implements ViewTreeObserver.OnPreDrawListener, AutoCloseable {
    public interface Listener {
        boolean onConfirm(CustomViewLocator locator);
        default List<CustomViewLocator> existingCustomViews() { return Collections.emptyList(); }
        default void onDismiss() { }
    }

    private static final int MAX_VIEWS = 2000;
    private static final int MAX_DEPTH = 64;
    private static final long SNAPSHOT_INTERVAL_MS = 250;
    private final Activity activity;
    private final View windowRoot;
    private final View feedRoot;
    private final Listener listener;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Deque<View> selectionHistory = new ArrayDeque<>();
    private final int[] location = new int[2];
    private final MiuixViewFinderSurface surface;
    private List<Node> nodes = Collections.emptyList();
    private List<View> pointCandidates = Collections.emptyList();
    private ViewTreeObserver observer;
    private Dialog treeDialog;
    private AlertDialog propertyDialog;
    private AlertDialog ruleDialog;
    private View selected;
    private View previousFocus;
    private long lastSnapshot;
    private int visibleNodeCount;
    private int actionableNodeCount;
    private int groupNodeCount;
    private int leafNodeCount;
    private int maximumDepth;
    private int hitX;
    private int hitY;
    private boolean hasHitPoint;
    private boolean showAllBounds;
    private boolean truncated;
    private boolean closed;
    private String treeQuery = "";
    private boolean treeVisibleOnly;
    private boolean treeClickableOnly;

    private ViewFinderOverlay(Activity activity, View root, View feedRoot, Listener listener) {
        super(activity);
        this.activity = activity;
        this.windowRoot = root;
        this.feedRoot = feedRoot;
        this.listener = listener;
        setTag("douai.view_finder");
        setWillNotDraw(false);
        setClickable(true);
        setFocusableInTouchMode(true);
        setElevation(dp(32));
        outline.setStyle(Paint.Style.STROKE);
        selectedFill.setStyle(Paint.Style.FILL);
        surface = new MiuixViewFinderSurface(activity, new MiuixViewFinderSurface.Actions() {
            @Override public void onClose() { close(); }
            @Override public void onRefresh() { refreshTree(); }
            @Override public void onUndo() { undoSelection(); }
            @Override public void onTree() { showTree(); }
            @Override public void onToggleBounds() {
                showAllBounds = !showAllBounds;
                updateSurface();
                invalidate();
            }
            @Override public void onPrevious() { moveSelection(-1); }
            @Override public void onNext() { moveSelection(1); }
            @Override public void onParent() { selectParent(); }
            @Override public void onChild() { selectChild(); }
            @Override public void onIndex(int index) {
                if (index >= 0 && index < nodes.size()) select(nodes.get(index).view);
            }
            @Override public void onOverlap() { cyclePointCandidate(); }
            @Override public void onProperties() { showProperties(); }
            @Override public void onRules() { showRuleEditor(); }
            @Override public void onHide() { saveSelectedRule(); }
        });
        addView(surface.getView(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        refreshSnapshot();
    }

    public static ViewFinderOverlay show(Activity activity, View root, Listener listener) {
        return show(activity, root, null, listener);
    }

    public static ViewFinderOverlay show(Activity activity, View root, View feedRoot, Listener listener) {
        if (activity == null || root == null || listener == null || activity.isFinishing()
                || activity.isDestroyed() || activity.getWindow() == null) return null;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup) || root != decor) return null;
        ViewFinderOverlay picker = null;
        try {
            picker = new ViewFinderOverlay(activity, decor, feedRoot, listener);
            picker.previousFocus = decor.findFocus();
            ((ViewGroup) decor).addView(picker, new ViewGroup.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            picker.observer = decor.getViewTreeObserver();
            if (picker.observer.isAlive()) picker.observer.addOnPreDrawListener(picker);
            picker.requestFocus();
            return picker;
        } catch (RuntimeException unavailable) {
            if (picker != null) picker.close();
            return null;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (closed) return;
        int accent = ModuleTheme.color(getContext(), 0xFF0085E1);
        getLocationOnScreen(location);
        canvas.save();
        canvas.translate(-location[0], -location[1]);
        if (showAllBounds) {
            outline.setColor(Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)));
            outline.setStrokeWidth(dp(1));
            for (Node node : nodes) if (!node.bounds.isEmpty()) canvas.drawRect(node.bounds, outline);
        }
        Rect bounds = selectedBounds();
        if (!bounds.isEmpty()) {
            outline.setColor(accent);
            outline.setStrokeWidth(dp(3));
            selectedFill.setColor(Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)));
            canvas.drawRect(bounds, selectedFill);
            canvas.drawRect(bounds, outline);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closed) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
            refreshSnapshot();
            int x = (int) event.getRawX();
            int y = (int) event.getRawY();
            List<View> candidates = ViewInspector.hitCandidates(windowRoot, x, y);
            boolean repeated = hasHitPoint
                    && Math.hypot(x - hitX, y - hitY) <= ViewConfiguration.get(getContext()).getScaledTouchSlop()
                    && candidates.equals(pointCandidates);
            int previous = repeated ? candidates.indexOf(selected) : -1;
            hitX = x;
            hitY = y;
            hasHitPoint = !candidates.isEmpty();
            pointCandidates = candidates;
            if (hasHitPoint) select(candidates.get((previous + 1) % candidates.size()));
            updateSurface();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void refreshTree() {
        if (closed) return;
        clearHitPoint();
        refreshSnapshot();
        invalidate();
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void undoSelection() {
        while (!selectionHistory.isEmpty()) {
            View previous = selectionHistory.removeLast();
            if (isSelectable(previous)) {
                select(previous, false);
                return;
            }
        }
        toast("没有可撤销的选择");
    }

    private void moveSelection(int direction) {
        int current = selectedIndex();
        if (closed || current < 0) return;
        for (int next = current + direction; next >= 0 && next < nodes.size(); next += direction) {
            if (!nodes.get(next).bounds.isEmpty()) {
                select(nodes.get(next).view);
                return;
            }
        }
    }

    private void selectParent() {
        if (selected != null && selected.getParent() instanceof View) {
            select((View) selected.getParent());
        }
    }

    private void selectChild() {
        if (!(selected instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) selected;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (isSelectable(child)) {
                select(child);
                return;
            }
        }
    }

    private void select(View view) { select(view, true); }

    private void select(View view, boolean recordPrevious) {
        if (closed || !isSelectable(view) || view == selected) return;
        if (recordPrevious && selected != null) {
            if (selectionHistory.size() >= MAX_DEPTH) selectionHistory.removeFirst();
            selectionHistory.addLast(selected);
        }
        if (!pointCandidates.contains(view)) clearHitPoint();
        selected = view;
        refreshSnapshot();
        invalidate();
    }

    private void cyclePointCandidate() {
        if (closed || !hasHitPoint) return;
        pointCandidates = ViewInspector.hitCandidates(windowRoot, hitX, hitY);
        if (pointCandidates.isEmpty()) clearHitPoint();
        else select(pointCandidates.get((pointCandidates.indexOf(selected) + 1) % pointCandidates.size()));
        updateSurface();
    }

    private void clearHitPoint() {
        hasHitPoint = false;
        pointCandidates = Collections.emptyList();
    }

    private void showTree() {
        if (closed) return;
        refreshSnapshot();
        dismissDialog(treeDialog);
        Dialog next = MiuixViewTreeDialog.show(activity, treeSnapshot(), selected,
                treeQuery, treeVisibleOnly, treeClickableOnly, new MiuixViewTreeDialog.Listener() {
                    @Override public boolean onSelect(View view) {
                        if (closed || !isSelectable(view)) return false;
                        clearHitPoint();
                        select(view);
                        updateSurface();
                        return true;
                    }
                    @Override public MiuixViewTreeDialog.Snapshot onRefresh() {
                        refreshSnapshot();
                        return treeSnapshot();
                    }
                    @Override public void onQueryChanged(String query, boolean visible, boolean actionable) {
                        treeQuery = query;
                        treeVisibleOnly = visible;
                        treeClickableOnly = actionable;
                    }
                    @Override public boolean matches(View view, String query) {
                        return ViewInspector.matches(view, query);
                    }
                });
        treeDialog = next;
        next.setOnDismissListener(ignored -> { if (treeDialog == next) treeDialog = null; });
    }

    private MiuixViewTreeDialog.Snapshot treeSnapshot() {
        ArrayList<MiuixViewTreeDialog.Entry> entries = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            boolean actionable = node.view.isClickable() || node.view.isLongClickable();
            entries.add(new MiuixViewTreeDialog.Entry(node.view, index, node.depth,
                    node.view.getClass().getName(), resourceName(node.view),
                    ViewInspector.contentSummary(node.view) + "\n" + visibility(node.view) + (actionable ? " · 可交互" : "")
                            + (node.view.isEnabled() ? "" : " · 已停用"),
                    dimensions(node.view), !node.bounds.isEmpty(), actionable));
        }
        return new MiuixViewTreeDialog.Snapshot(entries, truncated);
    }

    private void showProperties() {
        if (closed || selected == null) return;
        String details = ViewInspector.describe(selected);
        CustomViewLocator locator = currentLocator();
        if (locator != null) details += "\n\n" + locator.scopeDescription() + " · "
                + locator.matchingDescription() + "\n" + locator.pathDescription();
        final String information = details;
        ScrollView content = textContent(information, "view_finder.properties_text", true);
        dismissDialog(propertyDialog);
        AlertDialog next = new MiuixDialogBuilder(content.getContext(), true)
                .setTitle("视图属性").setView(content).setNegativeButton("关闭", null)
                .setPositiveButton("复制属性", (ignored, which) -> copy(information)).create();
        propertyDialog = next;
        next.setOnDismissListener(ignored -> { if (propertyDialog == next) propertyDialog = null; });
        openDialog(next);
    }

    private void showRuleEditor() {
        CustomViewLocator locator = currentLocator();
        if (closed || locator == null) return;
        StringBuilder information = new StringBuilder(locator.label)
                .append('\n').append(locator.viewClassName)
                .append("\n\n").append(locator.scopeDescription()).append(" · ")
                .append(locator.matchingDescription()).append('\n').append(locator.pathDescription())
                .append("\n\n自定义组件");
        List<CustomViewLocator> existing = listener.existingCustomViews();
        if (existing.isEmpty()) information.append("\n暂无已添加组件");
        else for (CustomViewLocator item : existing) information.append("\n\n").append(item.label)
                .append('\n').append(item.scopeDescription()).append(" · ").append(item.matchingDescription());
        ScrollView content = textContent(information.toString(), "view_finder.rule_text", false);
        dismissDialog(ruleDialog);
        AlertDialog next = new MiuixDialogBuilder(content.getContext(), true)
                .setTitle("规则编辑").setView(content).setNegativeButton("取消", null)
                .setPositiveButton("保存到自定义组件", null).create();
        ruleDialog = next;
        next.setOnDismissListener(ignored -> { if (ruleDialog == next) ruleDialog = null; });
        if (openDialog(next)) next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (saveRule(locator)) next.dismiss();
        });
    }

    private ScrollView textContent(String information, String tag, boolean monospace) {
        Context context = ModuleTheme.themedContext(getContext());
        TextView text = new TextView(context);
        text.setText(information);
        text.setTextSize(13);
        text.setTextColor(0xFF242A30);
        text.setTextIsSelectable(true);
        text.setTag(tag);
        text.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (monospace) text.setTypeface(Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        ModuleTheme.bind(scroll);
        return scroll;
    }

    private boolean openDialog(AlertDialog dialog) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            dialog.setOwnerActivity(activity);
            dialog.show();
            return true;
        } catch (RuntimeException unavailable) {
            dismissDialog(dialog);
            return false;
        }
    }

    private void copy(String information) {
        if (closed) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText("DouAi 视图属性", information));
            toast("属性已复制");
        } catch (RuntimeException unavailable) { toast("复制失败"); }
    }

    private CustomViewLocator currentLocator() {
        return selected == null ? null : CustomViewLocator.capture(windowRoot, feedRoot, selected);
    }

    private void saveSelectedRule() {
        CustomViewLocator locator = currentLocator();
        if (!closed && locator != null) saveRule(locator);
    }

    private boolean saveRule(CustomViewLocator locator) {
        if (closed) return false;
        if (selected == null || locator.resolve(windowRoot, feedRoot) != selected) {
            toast("视图结构已变化，请刷新后重新选择");
            refreshSnapshot();
            return false;
        }
        try {
            if (listener.onConfirm(locator)) {
                toast("已加入自定义组件并隐藏");
                close();
                return true;
            }
        } catch (RuntimeException unavailable) { }
        toast("保存失败");
        return false;
    }

    private void refreshSnapshot() {
        if (closed) return;
        ArrayList<Node> snapshot = new ArrayList<>();
        truncated = false;
        visibleNodeCount = actionableNodeCount = groupNodeCount = leafNodeCount = maximumDepth = 0;
        collect(windowRoot, 0, snapshot);
        nodes = snapshot;
        if (selected != null && !isSelectable(selected)) {
            selected = null;
            clearHitPoint();
        }
        if (hasHitPoint) {
            pointCandidates = ViewInspector.hitCandidates(windowRoot, hitX, hitY);
            if (!pointCandidates.contains(selected)) clearHitPoint();
        }
        lastSnapshot = SystemClock.uptimeMillis();
        updateSurface();
    }

    private void collect(View view, int depth, List<Node> result) {
        if (view == null || isFinderView(view)) return;
        if (result.size() >= MAX_VIEWS || depth > MAX_DEPTH) { truncated = true; return; }
        maximumDepth = Math.max(maximumDepth, depth);
        if (view instanceof ViewGroup) groupNodeCount++; else leafNodeCount++;
        if (view.isClickable() || view.isLongClickable()) actionableNodeCount++;
        if (view != windowRoot) {
            Rect bounds = screenBounds(view);
            if (!bounds.isEmpty()) visibleNodeCount++;
            result.add(new Node(view, bounds, depth));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), depth + 1, result);
                if (result.size() >= MAX_VIEWS) {
                    if (index + 1 < group.getChildCount()) truncated = true;
                    return;
                }
            }
        }
    }

    private int selectedIndex() {
        for (int index = 0; index < nodes.size(); index++) if (nodes.get(index).view == selected) return index;
        return -1;
    }

    private Rect selectedBounds() { return selected == null ? new Rect() : screenBounds(selected); }

    private void updateSurface() {
        if (closed || surface == null) return;
        int index = selectedIndex();
        int depth = index < 0 ? 0 : nodes.get(index).depth;
        Rect relative = selectedBounds();
        getLocationOnScreen(location);
        if (!relative.isEmpty()) relative.offset(-location[0], -location[1]);
        surface.update(selected != null, selected == null ? "" : selected.getClass().getSimpleName(),
                selected == null ? "" : resourceName(selected), selected == null ? "" : dimensions(selected),
                depth, index, nodes.size(), pointCandidates.indexOf(selected), pointCandidates.size(),
                currentLocator() != null, showAllBounds, relative);
    }

    private boolean isSelectable(View view) {
        if (view == null || view == windowRoot) return false;
        for (View current = view; current != null; ) {
            if (isFinderView(current)) return false;
            if (current == windowRoot) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    public String diagnosticSummary() {
        View content = activity.findViewById(android.R.id.content);
        return "root=" + windowRoot.getClass().getName()
                + ", directChildren=" + (windowRoot instanceof ViewGroup ? ((ViewGroup) windowRoot).getChildCount() : 0)
                + ", content=" + (content == null ? "missing" : content.getClass().getName())
                + ", views=" + nodes.size() + (truncated ? "+" : "")
                + ", visibleBounds=" + visibleNodeCount + ", actionable=" + actionableNodeCount
                + ", groups=" + groupNodeCount + ", leaves=" + leafNodeCount + ", depth=" + maximumDepth;
    }

    static Rect screenBounds(View view) { return ViewInspector.screenBounds(view); }

    private static boolean isFinderView(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith("douai.view_finder");
    }

    private static String resourceName(View view) {
        try { return view.getResources().getResourceName(view.getId()); }
        catch (RuntimeException ignored) { return "无资源 ID"; }
    }

    private static String dimensions(View view) { return view.getWidth() + " × " + view.getHeight() + " px"; }

    private static String visibility(View view) {
        if (view.getVisibility() == View.GONE) return "GONE";
        return view.isShown() && view.getAlpha() > 0.01f ? "VISIBLE" : "INVISIBLE";
    }

    @Override public boolean onPreDraw() {
        if (closed) return true;
        if (activity.isFinishing() || activity.isDestroyed() || !windowRoot.isAttachedToWindow()) {
            close();
            return true;
        }
        if (SystemClock.uptimeMillis() - lastSnapshot >= SNAPSHOT_INTERVAL_MS) {
            refreshSnapshot();
            invalidate();
        }
        return true;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateSurface();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        close();
    }

    public boolean isClosed() { return closed; }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void toast(String message) { Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show(); }

    private static void dismissDialog(Dialog dialog) {
        if (dialog != null) try { dialog.dismiss(); } catch (RuntimeException unavailable) { }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        dismissDialog(treeDialog);
        dismissDialog(ruleDialog);
        dismissDialog(propertyDialog);
        treeDialog = null;
        ruleDialog = propertyDialog = null;
        try { surface.close(); } catch (RuntimeException unavailable) { }
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (previousFocus != null && previousFocus.isAttachedToWindow()) previousFocus.requestFocus();
        previousFocus = null;
        selected = null;
        selectionHistory.clear();
        clearHitPoint();
        nodes = Collections.emptyList();
        try { listener.onDismiss(); } catch (RuntimeException unavailable) { }
    }

    private static final class Node {
        final View view;
        final Rect bounds;
        final int depth;
        Node(View view, Rect bounds, int depth) {
            this.view = view;
            this.bounds = bounds;
            this.depth = depth;
        }
    }
}
