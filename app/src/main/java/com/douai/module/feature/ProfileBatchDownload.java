package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Batch download of one author's works from the profile page.
 *
 * <p>The button is injected into the existing verified profile header, so no new host rule is
 * required. Work items are read from the profile list adapter by reflection and the list is scrolled
 * until it stops producing new items. Only read-only access to the host list is performed; every
 * download goes through the same verified {@link MediaStoreDownloader} path as single-work saves.
 */
public final class ProfileBatchDownload implements AutoCloseable {
    /** Items are capped so a very large profile cannot queue an unbounded download burst. */
    static final int MAX_ITEMS = 300;
    static final int MAX_SCROLLS = 40;
    static final int STABLE_ROUNDS = 3;
    static final long SCROLL_DELAY_MS = 600L;
    static final long ITEM_DELAY_MS = 250L;

    private final BehaviorHookGroup registration;
    private final Method rootView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Host> hosts = new WeakHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile LocalOptions options;
    private volatile boolean closed;

    private ProfileBatchDownload(LocalOptions options, BehaviorHookGroup registration, Method rootView) {
        this.options = options;
        this.registration = registration;
        this.rootView = rootView;
    }

    public static ProfileBatchDownload install(XposedInterface framework, HostScanReport report,
                                               ClassLoader loader, LocalOptions options,
                                               Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        BehaviorHookGroup registration = new BehaviorHookGroup("profile_batch_download", sink);
        ProfileBatchDownload hook = null;
        try {
            Method bind = HostTargets.method(report, "adv_profile_render", loader);
            Method root = HostTargets.method(report, "adv_profile_root", loader);
            if (!bind.getDeclaringClass().equals(root.getDeclaringClass())
                    || !View.class.isAssignableFrom(root.getReturnType())
                    || root.getParameterCount() != 0) {
                throw new IllegalStateException("Profile header relationship changed");
            }
            hook = new ProfileBatchDownload(options, registration, root);
            ProfileBatchDownload installed = hook;
            registration.add(framework, bind, chain -> {
                Object result = chain.proceed();
                if (!installed.closed) installed.attach(chain.getThisObject(), nicknameOf(chain));
                return result;
            });
            try { sink.accept("Feature registered: profile_batch_download"); }
            catch (Throwable ignored) { }
            return hook;
        } catch (Throwable failure) {
            if (hook != null) hook.close();
            registration.fail(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    /** Reads the rendered user's nickname from the intercepted call arguments when they are readable. */
    private static String nicknameOf(Object chain) {
        try {
            java.lang.reflect.Method args = chain.getClass().getMethod("getArgs");
            Object value = args.invoke(chain);
            if (!(value instanceof List)) return null;
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return null;
            Object user = list.get(0);
            Object nickname = DownloadActionHook.jsonValue(user, "nickname");
            return nickname == null ? null : String.valueOf(nickname);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    public boolean isActive() { return !closed && registration.isActive(); }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        this.options = options;
        onMain(() -> {
            for (Host host : new ArrayList<>(hosts.values())) host.refreshVisibility();
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        registration.close();
        onMain(() -> {
            for (Host host : new ArrayList<>(hosts.values())) host.detach();
            hosts.clear();
        });
    }

    private void attach(Object owner, String nickname) {
        if (owner == null) return;
        onMain(() -> {
            if (closed || !registration.isActive()) return;
            Host host = hosts.get(owner);
            if (host == null) {
                View root;
                try { root = (View) rootView.invoke(owner); }
                catch (ReflectiveOperationException | RuntimeException failure) { return; }
                if (root == null || !root.isAttachedToWindow()) return;
                host = new Host(root);
                hosts.put(owner, host);
            }
            host.refreshVisibility(nickname);
        });
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    /** One injected button per observed profile header. */
    private final class Host {
        private final WeakReference<View> root;
        private Button button;
        private String nickname;

        Host(View root) {
            this.root = new WeakReference<>(root);
        }

        void refreshVisibility() { refreshVisibility(nickname); }

        void refreshVisibility(String nickname) {
            if (nickname != null && !nickname.isEmpty()) this.nickname = nickname;
            LocalOptions current = options;
            boolean enabled = current != null && current.enabled("profile_batch_download", true);
            if (!enabled) { detach(); return; }
            View view = root.get();
            if (view == null || !view.isAttachedToWindow()) { detach(); return; }
            if (button != null && button.getParent() != null) return;
            if (button == null) {
                Button created = new Button(view.getContext());
                created.setText("批量下载");
                created.setAllCaps(false);
                created.setTag("douai.profile.batch_download");
                created.setTextColor(0xFFFFFFFF);
                created.setBackgroundColor(0xFF20C08A);
                created.setTextSize(12f);
                created.setMinHeight(0);
                created.setMinimumHeight(0);
                created.setMinimumWidth(0);
                created.setPadding(dp(14), dp(4), dp(14), dp(4));
                created.setOnClickListener(v -> toggle());
                button = created;
            }
            if (placeUnderNickname(view, nickname)) return;
            placeInHeader(view);
        }

        /**
         * Puts the button directly below the nickname, on the avatar's right side. The nickname column is
         * located by text so the host layout above the statistics row is not disturbed.
         */
        private boolean placeUnderNickname(View root, String nickname) {
            View nicknameView = nickname == null || nickname.isEmpty() ? null : findTextView(root, nickname);
            if (nicknameView == null || !(nicknameView.getParent() instanceof ViewGroup)) return false;
            ViewGroup column = (ViewGroup) nicknameView.getParent();
            try {
                ViewGroup.LayoutParams params = copyParams(nicknameView.getLayoutParams());
                int index = Math.min(column.indexOfChild(nicknameView) + 1, column.getChildCount());
                column.addView(button, index, params);
                tuneSize();
                DouAiLog.event("profile_batch_download", "inject", "below_nickname",
                        "parent=" + column.getClass().getSimpleName());
                return button.getParent() == column;
            } catch (RuntimeException failure) {
                return false;
            }
        }

        /** Fallback for layouts without a readable nickname: insert just above the statistics row. */
        private void placeInHeader(View view) {
            ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
            if (parent == null) return;
            try {
                int index = parent.indexOfChild(view);
                if (index >= 0 && parent instanceof android.widget.LinearLayout
                        && ((android.widget.LinearLayout) parent).getOrientation()
                        == android.widget.LinearLayout.VERTICAL) {
                    parent.addView(button, index, copyParams(view.getLayoutParams()));
                    tuneSize();
                    DouAiLog.event("profile_batch_download", "inject", "above_statistics",
                            "parent=" + parent.getClass().getSimpleName());
                    return;
                }
                parent.addView(button);
                tuneSize();
            } catch (RuntimeException failure) {
                DouAiLog.event("profile_batch_download", "inject", "container_rejected", "reason=layout");
            }
        }

        /** Returns the first TextView inside the subtree whose text equals the nickname. */
        private View findTextView(View root, String nickname) {
            ArrayList<View> queue = new ArrayList<>();
            queue.add(root);
            for (int index = 0; index < queue.size() && index < 300; index++) {
                View view = queue.get(index);
                if (view instanceof android.widget.TextView) {
                    CharSequence text = ((android.widget.TextView) view).getText();
                    if (text != null && nickname.contentEquals(text.toString().trim())) return view;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int child = 0; child < group.getChildCount(); child++) queue.add(group.getChildAt(child));
                }
            }
            return null;
        }

        private void tuneSize() {
            ViewGroup.LayoutParams params = button.getLayoutParams();
            if (params == null) return;
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) params).setMargins(dp(2), dp(6), dp(12), dp(2));
            }
            button.setLayoutParams(params);
        }

        private ViewGroup.LayoutParams copyParams(ViewGroup.LayoutParams source) {
            if (source == null) return new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            try {
                return (ViewGroup.LayoutParams) source.getClass()
                        .getConstructor(ViewGroup.LayoutParams.class).newInstance(source);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return new ViewGroup.MarginLayoutParams(source);
            }
        }

        void detach() {
            Button view = button;
            button = null;
            if (view != null && view.getParent() instanceof ViewGroup) {
                try { ((ViewGroup) view.getParent()).removeView(view); } catch (RuntimeException ignored) { }
            }
        }

        private int dp(int value) {
            View view = root.get();
            float density = view == null ? 1f : view.getResources().getDisplayMetrics().density;
            return Math.round(value * density);
        }

        private void toggle() {
            if (running.get()) { running.set(false); return; }
            LocalOptions current = options;
            View view = root.get();
            if (current == null || view == null) return;
            if (!running.compareAndSet(false, true)) return;
            button.setText("正在收集…");
            Context context = view.getContext().getApplicationContext();
            View decor = view.getRootView();
            String tree = current.downloadSaveTree();
            Thread worker = new Thread(() -> run(context, decor, current, tree), "DouAi-ProfileBatch");
            worker.setDaemon(true);
            worker.start();
        }

        private void run(Context context, View decor, LocalOptions current, String tree) {
            int saved = 0, failed = 0, skipped = 0;
            try {
                List<Object> items = collect(decor);
                DouAiLog.event("profile_batch_download", "collect", items.isEmpty() ? "no_items" : "items_found",
                        "count=" + items.size());
                for (int index = 0; index < items.size(); index++) {
                    if (!running.get() || closed) break;
                    Object aweme = items.get(index);
                    List<DownloadActionHook.MediaAsset> assets =
                            DownloadActionHook.mediaAssets(aweme, true, current);
                    if (assets.isEmpty()) { skipped++; continue; }
                    for (DownloadActionHook.MediaAsset asset : assets) {
                        if (!running.get() || closed) break;
                        boolean accepted = asset.image
                                ? MediaStoreDownloader.downloadImage(context, asset.url, asset.name, tree, this::log)
                                : MediaStoreDownloader.download(context, asset.url, asset.name, false, tree, this::log);
                        if (accepted) saved++; else failed++;
                        sleep(ITEM_DELAY_MS);
                    }
                    int position = index + 1;
                    onMain(() -> setLabel(progressLabel(position, items.size())));
                }
            } catch (RuntimeException failure) {
                DouAiLog.event("profile_batch_download", "run", "failed", "reason=runtime");
                main.post(() -> { if (button != null) button.setText("批量下载"); });
            } finally {
                running.set(false);
                int total = saved, errors = failed, empty = skipped;
                main.post(() -> {
                    if (button != null) button.setText("批量下载");
                    Context target = context;
                    if (target != null) {
                        String message = total == 0
                                ? "未读取到作品列表：请先在主页下滑浏览几屏，再点批量下载"
                                : "已加入下载 " + total + " 项" + (errors > 0 ? "，失败 " + errors : "")
                                + (empty > 0 ? "，跳过 " + empty : "");
                        try { Toast.makeText(target, message, Toast.LENGTH_SHORT).show(); } catch (RuntimeException ignored) { }
                    }
                });
                DouAiLog.event("profile_batch_download", "finish", "completed",
                        "queued=" + saved + ";failed=" + failed + ";empty=" + skipped);
            }
        }

        private void setLabel(String text) { if (button != null) button.setText(text); }

        private void log(String message) {
            try { DouAiLog.event("profile_batch_download", "transfer", "message", "text=" + DouAiLog.reference(message)); }
            catch (Throwable ignored) { }
        }
    }

    /**
     * Collects the author's works from the profile list. The adapter is read reflectively because the
     * host adapter type changes between builds; unknown shapes simply yield no items.
     */
    private List<Object> collect(View decor) {
        Object list = findList(decor);
        List<Object> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int stable = 0;
        for (int round = 0; round <= MAX_SCROLLS && stable < STABLE_ROUNDS; round++) {
            if (!running.get() || closed) break;
            int before = ordered.size();
            merge(list, ordered, seen);
            if (ordered.size() >= MAX_ITEMS) break;
            if (ordered.size() == before) stable++;
            else stable = 0;
            if (!scroll(decor)) break;
            sleep(SCROLL_DELAY_MS);
        }
        return ordered.size() > MAX_ITEMS ? new ArrayList<>(ordered.subList(0, MAX_ITEMS)) : ordered;
    }

    private void merge(Object list, List<Object> ordered, Set<String> seen) {
        for (Object candidate : readItems(list)) {
            String id = identity(candidate);
            if (id == null || !seen.add(id)) continue;
            ordered.add(candidate);
            if (ordered.size() >= MAX_ITEMS) return;
        }
    }

    private static String identity(Object candidate) {
        if (candidate == null) return null;
        Object id = DownloadActionHook.jsonValue(candidate, "aweme_id");
        if (id == null) return null;
        String text = String.valueOf(id);
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    /** Finds the vertical list that holds profile items, without depending on a host class name. */
    private Object findList(View decor) {
        if (decor == null) return null;
        ArrayList<View> queue = new ArrayList<>();
        queue.add(decor);
        for (int index = 0; index < queue.size() && index < 400; index++) {
            View view = queue.get(index);
            Object adapter = adapterOf(view);
            if (adapter != null) return adapter;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int child = 0; child < group.getChildCount(); child++) queue.add(group.getChildAt(child));
            }
        }
        return null;
    }

    private static Object adapterOf(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        // Host builds ship RecyclerView subclasses and wrappers, so match the family, not one exact class.
        boolean listLike = name.contains("RecyclerView") || name.contains("ViewPager2")
                || name.endsWith("ListView") || name.endsWith("GridView");
        if (!listLike) return null;
        try {
            Method getAdapter = view.getClass().getMethod("getAdapter");
            Object adapter = getAdapter.invoke(view);
            return adapter == null ? null : adapter;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Reads list items from an adapter, an adapter-provided collection, or an adapter data field. */
    static List<Object> readItems(Object adapter) {
        ArrayList<Object> items = new ArrayList<>();
        if (adapter == null) return items;
        collectItems(adapter, items, 0);
        return items;
    }

    /** Reads adapters that only expose an index accessor plus a count, without a backing collection. */
    private static boolean collectIndexed(Object source, List<Object> sink) {
        Integer count = null;
        for (String name : new String[]{"getItemCount", "getCount", "size"}) {
            try {
                Method method = source.getClass().getMethod(name);
                Object value = method.invoke(source);
                if (value instanceof Integer) { count = (Integer) value; break; }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        if (count == null || count <= 0) return false;
        Method accessor = null;
        for (String name : new String[]{"getItem", "get", "item"}) {
            try {
                Method candidate = source.getClass().getMethod(name, int.class);
                if (candidate.getReturnType() != void.class) { accessor = candidate; break; }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        if (accessor == null) return false;
        int limit = Math.min(count, MAX_ITEMS);
        try {
            for (int index = 0; index < limit; index++) {
                Object item = accessor.invoke(source, index);
                if (item != null) sink.add(item);
                if (sink.size() >= MAX_ITEMS) break;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
        return !sink.isEmpty();
    }

    private static void collectItems(Object source, List<Object> sink, int depth) {
        if (source == null || depth > 2 || sink.size() >= MAX_ITEMS) return;
        if (source instanceof Collection<?>) {
            for (Object item : (Collection<?>) source) if (item != null) sink.add(item);
            return;
        }
        if (collectIndexed(source, sink)) return;
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !Collection.class.isAssignableFrom(method.getReturnType())) continue;
                String name = method.getName();
                if (!(name.equals("getItems") || name.equals("getData") || name.equals("getList")
                        || name.equals("getValues"))) continue;
                try {
                    method.setAccessible(true);
                    collectItems(method.invoke(source), sink, depth + 1);
                    if (!sink.isEmpty()) return;
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            for (Field field : type.getDeclaredFields()) {
                if (!Collection.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    collectItems(field.get(source), sink, depth + 1);
                    if (!sink.isEmpty()) return;
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
    }

    /** Scrolls the profile list by one viewport; returns false when no scrollable list exists. */
    private static boolean scroll(View decor) {
        Object adapter = null;
        View list = null;
        ArrayList<View> queue = new ArrayList<>();
        if (decor == null) return false;
        queue.add(decor);
        for (int index = 0; index < queue.size() && index < 400; index++) {
            View view = queue.get(index);
            if (adapterOf(view) != null) { list = view; break; }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int child = 0; child < group.getChildCount(); child++) queue.add(group.getChildAt(child));
            }
        }
        if (list == null) return false;
        try {
            Method smooth = list.getClass().getMethod("smoothScrollBy", int.class, int.class);
            smooth.invoke(list, 0, Math.max(1, list.getHeight()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /** Ordered de-duplication by host work id; exposed for tests. */
    static List<Object> dedupe(List<Object> source) {
        Map<String, Object> unique = new LinkedHashMap<>();
        for (Object item : source) {
            String id = identity(item);
            if (id != null) unique.putIfAbsent(id, item);
        }
        return new ArrayList<>(unique.values());
    }

    static String progressLabel(int position, int total) {
        return "批量下载 " + position + "/" + Math.min(total, MAX_ITEMS);
    }
}
