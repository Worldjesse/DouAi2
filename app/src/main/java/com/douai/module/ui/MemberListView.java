package com.douai.module.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.douai.module.feature.AvatarImageLoader;
import com.douai.module.feature.ProfileListEntry;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.BuildConfig;
import com.douai.module.R;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Searchable, recycled identity rows shared by every member and conversation picker. */
public final class MemberListView extends LinearLayout implements AutoCloseable {
    interface AvatarLoader { Bitmap load(String url, AvatarImageLoader.Request request) throws Exception; }
    private final List<ProfileListEntry> all;
    private final List<ProfileListEntry> visible = new ArrayList<>();
    private final Set<String> selected;
    private final boolean selectable;
    private final ExecutorService images;
    private final AvatarLoader avatarLoader;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Map<String, AvatarTask> pending = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Rows rows = new Rows();
    private final TextView count;
    private final android.graphics.drawable.Drawable.ConstantState placeholder;
    private volatile boolean closed;

    public MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                          boolean selectable) {
        this(baseContext, miuix, entries, selected, selectable, Executors.newFixedThreadPool(2),
                (url, request) -> request.load(url));
    }

    MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                   boolean selectable, ExecutorService images, AvatarLoader avatarLoader) {
        super(ModuleTheme.themedContext(baseContext));
        this.images = images;
        this.avatarLoader = avatarLoader;
        Context context = getContext();
        android.graphics.drawable.Drawable icon;
        try {
            android.content.res.Resources resources = context.getResources();
            if (!BuildConfig.APPLICATION_ID.equals(resources.getResourcePackageName(R.drawable.ic_member_placeholder))) {
                resources = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).getResources();
            }
            icon = resources.getDrawable(R.drawable.ic_member_placeholder, null);
        } catch (Exception unavailable) { icon = context.getDrawable(android.R.drawable.ic_menu_myplaces); }
        placeholder = icon.getConstantState();
        all = new ArrayList<>(entries);
        this.selected = new LinkedHashSet<>(selected);
        this.selectable = selectable;
        setOrientation(VERTICAL);
        EditText search = miuix ? MiuixFormControls.input(context, "搜索名称或 UID", "", false) : new EditText(context);
        search.setHint("搜索名称或 UID");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTag("settings.followLists.search");
        addView(MiuixFormControls.viewFor(search), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        count = new TextView(context);
        count.setTextColor(Color.rgb(105, 115, 122));
        count.setTextSize(12);
        count.setPadding(dp(8), dp(8), dp(8), dp(8));
        count.setTag("settings.followLists.resultCount");
        addView(count);
        ListView list = new ListView(context);
        list.setTag("settings.followLists.members");
        list.setDividerHeight(dp(1));
        list.setAdapter(rows);
        int height = getResources().getConfiguration().screenHeightDp;
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, dp(Math.max(104, Math.min(300, height - 230)))));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int n, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int n) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        filter("");
    }

    public Set<String> selected() { return Collections.unmodifiableSet(new LinkedHashSet<>(selected)); }

    private void filter(String value) {
        String query = value.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (ProfileListEntry entry : all) {
            if (query.isEmpty() || entry.uid.toLowerCase(Locale.ROOT).contains(query)
                    || entry.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(entry);
        }
        count.setText(visible.isEmpty() ? "无匹配结果 · 共 " + all.size() + " 项"
                : visible.size() + " / " + all.size() + " 项");
        rows.notifyDataSetChanged();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public ProfileListEntry getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            IdentityRow row = recycled instanceof IdentityRow ? (IdentityRow) recycled : new IdentityRow(getContext());
            ProfileListEntry entry = getItem(position);
            row.setTag(entry.uid);
            row.name.setText(entry.name.isEmpty() ? "未知名称" : entry.name);
            row.uid.setText("UID: " + (entry.uid.isEmpty() ? "未读取" : entry.uid));
            row.setContentDescription(row.name.getText() + "，" + row.uid.getText());
            row.check.setVisibility(selectable ? View.VISIBLE : View.GONE);
            row.check.setChecked(selected.contains(entry.selectionKey));
            row.setOnClickListener(selectable ? ignored -> {
                if (!selected.remove(entry.selectionKey)) selected.add(entry.selectionKey);
                row.check.setChecked(selected.contains(entry.selectionKey));
            } : null);
            bindAvatar(row.avatar, entry.avatarUrl);
            return row;
        }
    }

    private final class IdentityRow extends LinearLayout {
        final ImageView avatar;
        final TextView name;
        final TextView uid;
        final CheckBox check;
        IdentityRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(8), dp(8), dp(8));
            setMinimumHeight(dp(64));
            avatar = new ImageView(context);
            avatar.setContentDescription("头像");
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(Color.rgb(233, 238, 241));
            avatar.setBackground(background);
            avatar.setClipToOutline(true);
            addView(avatar, new LayoutParams(dp(40), dp(40)));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            LayoutParams textParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            textParams.setMarginStart(dp(12));
            addView(texts, textParams);
            name = new TextView(context);
            name.setTextSize(14);
            name.setTextColor(Color.rgb(31, 39, 45));
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTag("member.name");
            texts.addView(name);
            uid = new TextView(context);
            uid.setTextSize(12);
            uid.setTextColor(Color.rgb(105, 115, 122));
            uid.setMaxLines(2);
            uid.setEllipsize(TextUtils.TruncateAt.END);
            uid.setTag("member.uid");
            texts.addView(uid);
            check = new CheckBox(context);
            check.setClickable(false);
            check.setFocusable(false);
            addView(check, new LayoutParams(dp(40), dp(40)));
        }
    }

    private void bindAvatar(ImageView view, String url) {
        Object previous = view.getTag();
        view.setTag(url);
        if (previous instanceof String && !url.equals(previous)) {
            AvatarTask old = pending.get(previous);
            if (old != null) {
                old.targets.removeIf(target -> target.get() == null || target.get() == view);
                if (old.targets.isEmpty()) { pending.remove(previous); old.cancel(); }
            }
        }
        Bitmap cached = cache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        view.setImageDrawable(placeholder.newDrawable());
        if (url.isEmpty() || closed || failed.contains(url)) return;
        AvatarTask waiting = pending.get(url);
        if (waiting != null) { waiting.add(view); return; }
        AvatarTask task = new AvatarTask(url);
        task.add(view);
        pending.put(url, task);
        WeakReference<MemberListView> owner = new WeakReference<>(this);
        AvatarLoader loader = avatarLoader;
        android.os.Handler dispatcher = main;
        try { task.future = images.submit(() -> {
            Bitmap bitmap = null;
            try { bitmap = loader.load(url, task.request); }
            catch (Exception ignored) { }
            Bitmap result = bitmap;
            if (!dispatcher.post(() -> {
                MemberListView members = owner.get();
                if (members != null) members.avatarLoaded(task, result);
                else if (result != null) result.recycle();
            }) && result != null) result.recycle();
        }); } catch (RejectedExecutionException unavailable) {
            pending.remove(url);
            task.cancel();
        }
    }

    private void avatarLoaded(AvatarTask task, Bitmap bitmap) {
        if (closed || pending.get(task.url) != task) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        pending.remove(task.url);
        if (bitmap == null) { failed.add(task.url); return; }
        cache.put(task.url, bitmap);
        for (WeakReference<ImageView> target : task.targets) {
            ImageView current = target.get();
            if (current != null && task.url.equals(current.getTag())) current.setImageBitmap(bitmap);
        }
    }

    private static final class AvatarTask {
        final String url;
        final AvatarImageLoader.Request request = new AvatarImageLoader.Request();
        final List<WeakReference<ImageView>> targets = new ArrayList<>();
        Future<?> future;

        AvatarTask(String url) { this.url = url; }

        void add(ImageView view) {
            targets.removeIf(target -> target.get() == null);
            for (WeakReference<ImageView> target : targets) if (target.get() == view) return;
            targets.add(new WeakReference<>(view));
        }

        void cancel() {
            request.cancel();
            if (future != null) future.cancel(true);
            targets.clear();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void close() {
        closed = true;
        for (AvatarTask task : pending.values()) task.cancel();
        pending.clear();
        images.shutdownNow();
        cache.evictAll();
        failed.clear();
    }
}
