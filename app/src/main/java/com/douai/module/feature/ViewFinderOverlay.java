package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.CustomViewLocator;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Same-window picker with a bounded tree snapshot and screen-coordinate hit testing. */
@SuppressLint({"ViewConstructor", "RtlHardcoded", "SetTextI18n"})
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
    private static final int VIEW_OUTLINE = 0xFF00D9E8;
    private static final int SELECTED_OUTLINE = 0xFF168BFF;
    private final Activity activity;
    private final View windowRoot;
    private final View feedRoot;
    private final Listener listener;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView position;
    private final Button confirmButton;
    private final LinearLayout toolbar;
    private final DragHandle dragHandle;
    private final LinearLayout selectionPanel;
    private final TextView selectionCount;
    private final TextView selectionDetails;
    private final TextView selectionCallout;
    private final SeekBar selectionSeekBar;
    private final Button hideButton;
    private final Deque<View> selectionHistory = new ArrayDeque<>();
    private final int[] location = new int[2];
    private List<Node> nodes = Collections.emptyList();
    private ViewTreeObserver observer;
    private AlertDialog treeDialog;
    private AlertDialog ruleDialog;
    private View selected;
    private View previousFocus;
    private long lastSnapshot;
    private int visibleNodeCount;
    private int actionableNodeCount;
    private int groupNodeCount;
    private int leafNodeCount;
    private int maximumDepth;
    private float dragDownX;
    private float dragDownY;
    private float dragStartTranslationX;
    private float dragStartTranslationY;
    private boolean dragging;
    private float panelDragDownX;
    private float panelDragDownY;
    private float panelDragStartTranslationX;
    private float panelDragStartTranslationY;
    private boolean panelDragging;
    private boolean updatingSeekBar;
    private boolean truncated;
    private boolean closed;

    private ViewFinderOverlay(Activity activity, View windowRoot, View feedRoot, Listener listener) {
        super(activity);
        this.activity = activity;
        this.windowRoot = windowRoot;
        this.feedRoot = feedRoot;
        this.listener = listener;
        setTag("douai.view_finder");
        setWillNotDraw(false);
        setClickable(true);
        setFocusableInTouchMode(true);
        setElevation(dp(32));

        toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(4), dp(3), dp(4), dp(3));
        toolbar.setBackground(rounded(0xE620252B, dp(20)));
        toolbar.setElevation(dp(10));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        dragHandle = new DragHandle(activity);
        dragHandle.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        dragHandle.setColorFilter(0xFFE5F8FA);
        dragHandle.setBackgroundColor(0x00000000);
        dragHandle.setContentDescription("拖动查找工具栏");
        dragHandle.setTooltipText("拖动工具栏");
        dragHandle.setTag("view_finder.drag");
        dragHandle.setOnTouchListener(this::dragToolbar);
        actions.addView(dragHandle, new LinearLayout.LayoutParams(dp(38), dp(44)));
        addButton(actions, "退出", "exit", this::close);
        addButton(actions, "刷新", "refresh", this::refreshTree);
        addButton(actions, "撤销", "undo", this::undoSelection);
        addButton(actions, "View树", "tree", this::showTree);
        confirmButton = addButton(actions, "规则", "confirm", this::showRuleEditor);
        toolbar.addView(actions, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(44)));

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                Math.min(dp(620), Math.max(1, screenWidth - dp(24))),
                LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        toolbarParams.topMargin = dp(68);
        addView(toolbar, toolbarParams);

        selectionPanel = new LinearLayout(activity);
        selectionPanel.setOrientation(LinearLayout.VERTICAL);
        selectionPanel.setPadding(dp(16), dp(10), dp(16), dp(12));
        selectionPanel.setBackground(rounded(0xE620252B, dp(18)));
        selectionPanel.setElevation(dp(12));
        selectionPanel.setTag("view_finder.selection_panel");

        FrameLayout panelDragHandle = new FrameLayout(activity);
        panelDragHandle.setTag("view_finder.selection_drag");
        panelDragHandle.setContentDescription("拖动组件查找面板");
        panelDragHandle.setTooltipText("拖动组件查找面板");
        View handle = new View(activity);
        handle.setBackground(rounded(0x99E9EEF0, dp(8)));
        FrameLayout.LayoutParams handleVisualParams = new FrameLayout.LayoutParams(dp(38), dp(4),
                Gravity.CENTER);
        panelDragHandle.addView(handle, handleVisualParams);
        panelDragHandle.setOnTouchListener(this::dragSelectionPanel);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(30));
        handleParams.bottomMargin = dp(2);
        selectionPanel.addView(panelDragHandle, handleParams);

        LinearLayout selectionHeader = new LinearLayout(activity);
        selectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        selectionCount = text(13, true);
        selectionCount.setTag("view_finder.selection_count");
        selectionHeader.addView(selectionCount, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView hint = text(12, false);
        hint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        hint.setText("← 滑动切换控件 →");
        selectionHeader.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        selectionHeader.setOnTouchListener(this::dragSelectionPanel);
        selectionPanel.addView(selectionHeader, matchWrap());

        LinearLayout switchRow = new LinearLayout(activity);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        addIcon(switchRow, android.R.drawable.ic_media_previous, "上一个", () -> moveSelection(-1));
        selectionSeekBar = new SeekBar(activity);
        selectionSeekBar.setTag("view_finder.selection_seekbar");
        selectionSeekBar.setMax(0);
        selectionSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser && !updatingSeekBar && value >= 0 && value < nodes.size()) {
                    select(nodes.get(value).view);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        switchRow.addView(selectionSeekBar, new LinearLayout.LayoutParams(0, dp(42), 1));
        addIcon(switchRow, android.R.drawable.ic_media_next, "下一个", () -> moveSelection(1));
        selectionPanel.addView(switchRow, matchWrap());

        LinearLayout hierarchy = new LinearLayout(activity);
        hierarchy.setGravity(Gravity.CENTER_VERTICAL);
        addIcon(hierarchy, android.R.drawable.arrow_up_float, "父节点", this::selectParent);
        addIcon(hierarchy, android.R.drawable.arrow_down_float, "子节点", this::selectChild);
        position = text(11, false);
        position.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        hierarchy.addView(position, new LinearLayout.LayoutParams(0, dp(38), 1));
        selectionPanel.addView(hierarchy, matchWrap());

        selectionDetails = text(12, true);
        selectionDetails.setTag("view_finder.selection_details");
        selectionDetails.setMaxLines(3);
        selectionDetails.setPadding(0, dp(6), 0, dp(8));
        selectionPanel.addView(selectionDetails, matchWrap());

        LinearLayout commands = new LinearLayout(activity);
        commands.setGravity(Gravity.CENTER);
        addButton(commands, "取消", "cancel", this::close);
        hideButton = addButton(commands, "隐藏", "hide", this::saveSelectedRule);
        selectionPanel.addView(commands, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(46)));
        // Empty panel areas can move the floating panel while child controls keep their own gestures.
        selectionPanel.setOnTouchListener(this::dragSelectionPanel);
        FrameLayout.LayoutParams selectionParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        selectionParams.setMargins(dp(8), 0, dp(8), dp(14));
        addView(selectionPanel, selectionParams);
        selectionPanel.setVisibility(View.GONE);

        selectionCallout = text(12, true);
        selectionCallout.setTextColor(0xFFF7F9FA);
        selectionCallout.setPadding(dp(10), dp(7), dp(10), dp(7));
        selectionCallout.setBackground(rounded(0xE620252B, dp(8)));
        selectionCallout.setTag("view_finder.selection_callout");
        selectionCallout.setVisibility(View.GONE);
        addView(selectionCallout, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(VIEW_OUTLINE);
        selectedFill.setStyle(Paint.Style.FILL);
        selectedFill.setColor(0x330078FF);
        labelPaint.setTextSize(dp(9));
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        refreshSnapshot();
    }

    public static ViewFinderOverlay show(Activity activity, View scanRoot, Listener listener) {
        return show(activity, scanRoot, null, listener);
    }

    public static ViewFinderOverlay show(Activity activity, View scanRoot, View feedRoot,
                                         Listener listener) {
        if (activity == null || scanRoot == null || listener == null
                || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return null;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup) || scanRoot != decor) return null;
        ViewFinderOverlay picker = new ViewFinderOverlay(activity, decor, feedRoot, listener);
        try {
            picker.previousFocus = decor.findFocus();
            ((ViewGroup) decor).addView(picker, new ViewGroup.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            picker.observer = decor.getViewTreeObserver();
            if (picker.observer.isAlive()) picker.observer.addOnPreDrawListener(picker);
            picker.requestFocus();
            return picker;
        } catch (RuntimeException failure) {
            picker.close();
            return null;
        }
    }

    private TextView text(int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setTextColor(0xFFF6F7FA);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button addButton(LinearLayout row, String title, String tag, Runnable action) {
        Button button = new Button(getContext());
        button.setText(title);
        button.setTextColor(0xFFF4F7F8);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setBackgroundColor(0x00000000);
        button.setTag("view_finder." + tag);
        button.setOnClickListener(ignored -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        return button;
    }

    private void addIcon(LinearLayout row, int icon, String name, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(icon);
        button.setColorFilter(0xFFFFFFFF);
        button.setBackgroundColor(0x00000000);
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setTag("view_finder." + name);
        button.setOnClickListener(ignored -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    private boolean dragToolbar(View view, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
        float centerX = (getWidth() - toolbar.getWidth()) / 2f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = event.getRawX();
                dragDownY = event.getRawY();
                dragStartTranslationX = toolbar.getTranslationX();
                dragStartTranslationY = toolbar.getTranslationY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - dragDownX;
                float dy = event.getRawY() - dragDownY;
                if (!dragging && Math.hypot(dx, dy) >= ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    dragging = true;
                }
                if (dragging) {
                    float minX = dp(8) - centerX;
                    float maxX = getWidth() - toolbar.getWidth() - dp(8) - centerX;
                    float minY = dp(8) - params.topMargin;
                    float maxY = getHeight() - toolbar.getHeight() - dp(8) - params.topMargin;
                    toolbar.setTranslationX(Math.max(minX, Math.min(maxX, dragStartTranslationX + dx)));
                    toolbar.setTranslationY(Math.max(minY, Math.min(maxY, dragStartTranslationY + dy)));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private boolean dragSelectionPanel(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panelDragDownX = event.getRawX();
                panelDragDownY = event.getRawY();
                panelDragStartTranslationX = selectionPanel.getTranslationX();
                panelDragStartTranslationY = selectionPanel.getTranslationY();
                panelDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - panelDragDownX;
                float dy = event.getRawY() - panelDragDownY;
                if (!panelDragging && Math.hypot(dx, dy)
                        >= ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    panelDragging = true;
                }
                if (panelDragging) {
                    float baseX = selectionPanel.getLeft();
                    float baseY = selectionPanel.getTop();
                    float minX = dp(8) - baseX;
                    float maxX = getWidth() - selectionPanel.getWidth() - dp(8) - baseX;
                    float minY = dp(8) - baseY;
                    float maxY = getHeight() - selectionPanel.getHeight() - dp(8) - baseY;
                    selectionPanel.setTranslationX(Math.max(minX, Math.min(maxX,
                            panelDragStartTranslationX + dx)));
                    selectionPanel.setTranslationY(Math.max(minY, Math.min(maxY,
                            panelDragStartTranslationY + dy)));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                panelDragging = false;
                return true;
            default:
                return true;
        }
    }

    private void refreshTree() {
        refreshSnapshot();
        invalidate();
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void undoSelection() {
        while (!selectionHistory.isEmpty()) {
            View previous = selectionHistory.removeLast();
            if (isDescendant(previous, windowRoot)) {
                select(previous, false);
                return;
            }
        }
        Toast.makeText(getContext(), "没有可撤销的选择", Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (closed) return;
        getLocationOnScreen(location);
        canvas.save();
        canvas.translate(-location[0], -location[1]);
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            if (node.screenBounds.isEmpty()) continue;
            outline.setColor(VIEW_OUTLINE);
            outline.setStrokeWidth(dp(1));
            canvas.drawRect(node.screenBounds, outline);
        }
        Rect selectedBounds = selectedBounds();
        if (selected != null && !selectedBounds.isEmpty()) {
            outline.setColor(SELECTED_OUTLINE);
            outline.setStrokeWidth(dp(3));
            canvas.drawRect(selectedBounds, selectedFill);
            canvas.drawRect(selectedBounds, outline);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closed) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
            refreshSnapshot();
            Node best = null;
            long bestArea = Long.MAX_VALUE;
            for (Node node : nodes) {
                if (!node.screenBounds.contains((int) event.getRawX(), (int) event.getRawY())) continue;
                long area = (long) node.screenBounds.width() * node.screenBounds.height();
                if (best == null || node.depth > best.depth
                        || (node.depth == best.depth && area <= bestArea)) {
                    best = node;
                    bestArea = area;
                }
            }
            if (best != null) select(best.view);
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void moveSelection(int direction) {
        if (selected == null || nodes.isEmpty()) return;
        int current = -1;
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).view == selected) { current = index; break; }
        }
        if (current < 0) return;
        int next = current + direction;
        while (next >= 0 && next < nodes.size()) {
            if (!nodes.get(next).screenBounds.isEmpty()) {
                select(nodes.get(next).view);
                return;
            }
            next += direction;
        }
    }

    private void selectParent() {
        if (selected != null && selected.getParent() instanceof View
                && selected.getParent() != windowRoot) select((View) selected.getParent());
    }

    private void selectChild() {
        if (!(selected instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) selected;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (!(child instanceof ViewFinderOverlay)) {
                select(child);
                return;
            }
        }
    }

    private void select(View view) {
        select(view, true);
    }

    private void select(View view, boolean recordPrevious) {
        if (view == null || view == selected) return;
        if (recordPrevious && selected != null) selectionHistory.addLast(selected);
        selected = view;
        refreshSnapshot();
        updateSelectionPanel();
        invalidate();
    }

    @SuppressLint("SetTextI18n")
    private void showTree() {
        if (closed) return;
        refreshSnapshot();
        List<Node> snapshot = new ArrayList<>(nodes);
        String[] labels = new String[snapshot.size()];
        for (int index = 0; index < labels.length; index++) {
            Node node = snapshot.get(index);
            StringBuilder indentBuilder = new StringBuilder();
            for (int depth = 0; depth < Math.min(8, node.depth); depth++) indentBuilder.append("  ");
            String indent = indentBuilder.toString();
            labels[index] = indent + (index + 1) + " " + node.view.getClass().getSimpleName()
                    + "\n" + indent + resourceName(node.view) + " · " + visibility(node.view);
        }
        if (treeDialog != null) treeDialog.dismiss();
        treeDialog = new AlertDialog.Builder(getContext()).setTitle("视图列表 · " + labels.length
                        + (truncated ? "+" : ""))
                .setItems(labels, (dialog, index) -> {
                    View view = snapshot.get(index).view;
                    if (isDescendant(view, windowRoot)) select(view);
                }).setNegativeButton("返回", null).create();
        treeDialog.show();
    }

    @SuppressLint("SetTextI18n")
    private void updateDetails() {
        position.setText("层级 " + selectedDepth());
        if (selected == null) {
            selectionCount.setText("点选控件");
            selectionDetails.setText("");
            confirmButton.setEnabled(false);
            hideButton.setEnabled(false);
            selectionPanel.setVisibility(View.GONE);
            selectionCallout.setVisibility(View.GONE);
            updateSeekBar(-1);
            return;
        }
        int index = -1;
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).view == selected) { index = i; break; }
        CustomViewLocator locator = CustomViewLocator.capture(windowRoot, feedRoot, selected);
        selectionCount.setText((index + 1) + " / " + nodes.size());
        selectionDetails.setText(selected.getClass().getSimpleName() + "  #"
                + resourceName(selected) + "\n" + selected.getWidth() + " × " + selected.getHeight()
                + " px  ·  层级 " + selectedDepth() + "\n父: " + parentName(selected));
        confirmButton.setEnabled(locator != null);
        hideButton.setEnabled(locator != null);
        selectionPanel.setVisibility(View.VISIBLE);
        updateSeekBar(index);
        updateCallout(index);
    }

    private void updateSelectionPanel() { updateDetails(); }

    private void updateSeekBar(int index) {
        selectionSeekBar.setMax(Math.max(0, nodes.size() - 1));
        updatingSeekBar = true;
        selectionSeekBar.setProgress(Math.max(0, Math.min(index, Math.max(0, nodes.size() - 1))));
        updatingSeekBar = false;
    }

    private int selectedDepth() {
        for (Node node : nodes) if (node.view == selected) return node.depth;
        return 0;
    }

    private String parentName(View view) {
        return view != null && view.getParent() instanceof View
                ? ((View) view.getParent()).getClass().getSimpleName() : "无";
    }

    private Rect selectedBounds() {
        if (selected == null) return new Rect();
        Node node = findNode(selected);
        if (node != null && !node.screenBounds.isEmpty()) return new Rect(node.screenBounds);
        if (selected.getWidth() <= 0 || selected.getHeight() <= 0) return new Rect();
        int[] screen = new int[2];
        selected.getLocationOnScreen(screen);
        return new Rect(screen[0], screen[1], screen[0] + selected.getWidth(),
                screen[1] + selected.getHeight());
    }

    private Node findNode(View view) {
        for (Node node : nodes) if (node.view == view) return node;
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void updateCallout(int index) {
        Rect bounds = selectedBounds();
        if (selected == null || bounds.isEmpty()) {
            selectionCallout.setVisibility(View.GONE);
            return;
        }
        selectionCallout.setText(selected.getClass().getSimpleName() + "  #"
                + resourceName(selected) + "\n" + selected.getWidth() + " × "
                + selected.getHeight() + " px  ·  " + parentName(selected));
        getLocationOnScreen(location);
        selectionCallout.measure(MeasureSpec.makeMeasureSpec(getWidth() - dp(24), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
        int left = Math.max(dp(8), Math.min(bounds.left - location[0],
                getWidth() - selectionCallout.getMeasuredWidth() - dp(8)));
        int top = bounds.top - location[1] - selectionCallout.getMeasuredHeight() - dp(6);
        if (top < dp(8)) top = Math.min(getHeight() - selectionCallout.getMeasuredHeight() - dp(8),
                bounds.bottom - location[1] + dp(6));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) selectionCallout.getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = left;
        params.topMargin = Math.max(dp(8), top);
        selectionCallout.setLayoutParams(params);
        selectionCallout.setVisibility(View.VISIBLE);
    }

    private CustomViewLocator currentLocator() {
        return selected == null ? null : CustomViewLocator.capture(windowRoot, feedRoot, selected);
    }

    private void saveSelectedRule() {
        CustomViewLocator locator = currentLocator();
        if (closed || locator == null) return;
        saveRule(locator);
    }

    private void showRuleEditor() {
        CustomViewLocator locator = currentLocator();
        if (closed || locator == null) return;
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView selectedLabel = text(13, true);
        selectedLabel.setText(selected.getClass().getSimpleName() + "  #" + resourceName(selected));
        content.addView(selectedLabel, matchWrap());
        TextView selectedPath = text(11, false);
        selectedPath.setText(locator.scope + " · " + locator.viewClassName);
        selectedPath.setMaxLines(3);
        content.addView(selectedPath, matchWrap());

        TextView existingTitle = text(14, true);
        existingTitle.setText("自定义组件");
        existingTitle.setPadding(0, dp(12), 0, dp(6));
        content.addView(existingTitle, matchWrap());
        List<CustomViewLocator> existing = listener.existingCustomViews();
        if (existing.isEmpty()) {
            TextView empty = text(12, false);
            empty.setText("暂无已添加组件");
            content.addView(empty, matchWrap());
        } else {
            for (CustomViewLocator item : existing) {
                TextView row = text(12, false);
                row.setText(item.label + "\n" + item.viewClassName);
                row.setPadding(0, dp(5), 0, dp(5));
                content.addView(row, matchWrap());
            }
        }
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(content, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        AlertDialog editor = new AlertDialog.Builder(getContext())
                .setTitle("规则编辑")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存到自定义组件", null)
                .create();
        ruleDialog = editor;
        editor.setOnDismissListener(ignored -> { if (ruleDialog == editor) ruleDialog = null; });
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (saveRule(locator)) editor.dismiss();
        });
        if (editor.getWindow() != null) {
            int width = Math.max(1, Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(40)));
            int height = Math.max(dp(240), Math.min(dp(520), getHeight() - dp(80)));
            editor.getWindow().setLayout(width, height);
        }
    }

    private boolean saveRule(CustomViewLocator locator) {
        try {
            if (listener.onConfirm(locator)) {
                Toast.makeText(getContext(), "已加入自定义组件并隐藏", Toast.LENGTH_SHORT).show();
                close();
                return true;
            }
            Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException failure) {
            Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    private void confirm() {
        showRuleEditor();
    }

    private void refreshSnapshot() {
        ArrayList<Node> snapshot = new ArrayList<>();
        truncated = false;
        visibleNodeCount = 0;
        actionableNodeCount = 0;
        groupNodeCount = 0;
        leafNodeCount = 0;
        maximumDepth = 0;
        collect(windowRoot, 0, snapshot);
        nodes = snapshot;
        if (selected != null && !isDescendant(selected, windowRoot)) selected = null;
        lastSnapshot = SystemClock.uptimeMillis();
        updateDetails();
    }

    private void collect(View view, int depth, List<Node> result) {
        if (view == null || view instanceof ViewFinderOverlay) return;
        if (result.size() >= MAX_VIEWS || depth > MAX_DEPTH) { truncated = true; return; }
        maximumDepth = Math.max(maximumDepth, depth);
        if (view instanceof ViewGroup) groupNodeCount++;
        else leafNodeCount++;
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
                if (truncated) return;
            }
        }
    }

    public String diagnosticSummary() {
        View content = activity.findViewById(android.R.id.content);
        return "root=" + windowRoot.getClass().getName()
                + ", directChildren=" + (windowRoot instanceof ViewGroup
                ? ((ViewGroup) windowRoot).getChildCount() : 0)
                + ", content=" + (content == null ? "missing" : content.getClass().getName())
                + ", views=" + nodes.size() + (truncated ? "+" : "")
                + ", visibleBounds=" + visibleNodeCount
                + ", actionable=" + actionableNodeCount
                + ", groups=" + groupNodeCount + ", leaves=" + leafNodeCount
                + ", depth=" + maximumDepth;
    }

    static Rect screenBounds(View view) {
        Rect visible = new Rect();
        if (!view.isShown() || view.getAlpha() <= 0.01f || !view.getGlobalVisibleRect(visible)) return visible;
        int[] screen = new int[2];
        int[] window = new int[2];
        View root = view.getRootView();
        root.getLocationOnScreen(screen);
        root.getLocationInWindow(window);
        visible.offset(screen[0] - window[0], screen[1] - window[1]);
        return visible;
    }

    private static String resourceName(View view) {
        try { return view.getResources().getResourceName(view.getId()); }
        catch (RuntimeException ignored) { return "无资源 ID"; }
    }

    private static String visibility(View view) {
        if (view.getVisibility() == View.GONE) return "GONE";
        if (!view.isShown() || view.getVisibility() == View.INVISIBLE) return "INVISIBLE";
        return view.getWidth() + "×" + view.getHeight();
    }

    private static boolean isDescendant(View view, View ancestor) {
        for (View current = view; current != null; ) {
            if (current == ancestor) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public boolean isClosed() { return closed; }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        if (treeDialog != null) treeDialog.dismiss();
        treeDialog = null;
        if (ruleDialog != null) ruleDialog.dismiss();
        ruleDialog = null;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (previousFocus != null && previousFocus.isAttachedToWindow()) previousFocus.requestFocus();
        previousFocus = null;
        try { listener.onDismiss(); } catch (RuntimeException ignored) { }
        selected = null;
        selectionHistory.clear();
        nodes = Collections.emptyList();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private static final class Node {
        final View view;
        final Rect screenBounds;
        final int depth;

        Node(View view, Rect bounds, int depth) {
            this.view = view;
            this.screenBounds = bounds;
            this.depth = depth;
        }
    }

    private static final class DragHandle extends ImageButton {
        DragHandle(Context context) { super(context); }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
