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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Batch download of one author's works from the profile page.
 *
 * <p>The work list is taken from the host post-tab model ({@code PostAwemeModelV2}) captured through a
 * verified hook, so enumeration no longer depends on the adapter exposing its items. The RecyclerView
 * instance is captured through the same component, which lets the batch page the list by scrolling the
 * host's own widget. Downloads reuse the verified single-work path, so quality and naming settings apply.
 */
public final class ProfileBatchDownload implements AutoCloseable {
    static final int MAX_ITEMS = 300;
    static final int MAX_SCROLLS = 40;
    static final int STABLE_ROUNDS = 3;
    static final long SCROLL_DELAY_MS = 700L;
    static final long ITEM_DELAY_MS = 250L;

    private final BehaviorHookGroup registration;
    private final Method headerRoot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Header> headers = new WeakHashMap<>();
    private final List<Object> models = new ArrayList<>();
    private WeakReference<View> listView = new WeakReference<>(null);
    private final List<Object> awemes = new ArrayList<>();
    private final Set<String> awemeIds = new LinkedHashSet<>();
    private int appendHits;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile LocalOptions options;
    private volatile boolean closed;

    private ProfileBatchDownload(LocalOptions options, BehaviorHookGroup registration, Method headerRoot) {
        this.options = options;
        this.registration = registration;
        this.headerRoot = headerRoot;
    }

    public static ProfileBatchDownload install(XposedInterface framework, HostScanReport report,
                                               ClassLoader loader, LocalOptions options,
                                               Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        BehaviorHookGroup registration = new BehaviorHookGroup("profile_batch_download", sink);
        ProfileBatchDownload hook = null;
        try {
            Method header = HostTargets.method(report, "adv_profile_root", loader);
            Method modelGetter = HostTargets.method(report, "profile_post_component_model", loader);
            Method listGetter = HostTargets.method(report, "profile_post_component_list", loader);
            Method append = HostTargets.method(report, "profile_post_model_append", loader);
            Method mergeRule = HostTargets.method(report, "profile_post_model_merge", loader);
            if (!View.class.isAssignableFrom(header.getReturnType()) || header.getParameterCount() != 0) {
                throw new IllegalStateException("Profile header relationship changed");
            }
            hook = new ProfileBatchDownload(options, registration, header);
            ProfileBatchDownload installed = hook;
            registration.add(framework, header, chain -> {
                Object result = chain.proceed();
                if (!installed.closed) installed.attach(chain.getThisObject());
                return result;
            });
            registration.add(framework, modelGetter, chain -> {
                Object result = chain.proceed();
                if (!installed.closed) installed.keepModel(result);
                return result;
            });
            registration.add(framework, listGetter, chain -> {
                Object result = chain.proceed();
                if (!installed.closed && result instanceof View) {
                    installed.listView = new WeakReference<>((View) result);
                }
                return result;
            });
            registration.add(framework, append, chain -> {
                Object result = chain.proceed();
                if (!installed.closed) installed.keepArgument(chain, 0);
                return result;
            });
            registration.add(framework, mergeRule, chain -> {
                Object result = chain.proceed();
                if (!installed.closed) installed.keepArgument(chain, 1);
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

    public boolean isActive() { return !closed && registration.isActive(); }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        this.options = options;
        onMain(() -> {
            for (Header header : new ArrayList<>(headers.values())) header.refresh();
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        registration.close();
        onMain(() -> {
            for (Header header : new ArrayList<>(headers.values())) header.detach();
            headers.clear();
        });
        synchronized (models) { models.clear(); }
    }

    /** Captures works straight from the host append arguments; no field or adapter reading needed. */
    private void keepArgument(Object chain, int index) {
        try {
            java.lang.reflect.Method argsMethod = chain.getClass().getMethod("getArgs");
            Object value = argsMethod.invoke(chain);
            if (!(value instanceof List)) return;
            List<?> args = (List<?>) value;
            if (index >= args.size()) return;
            Object argument = args.get(index);
            if (argument instanceof Collection) {
                for (Object item : (Collection<?>) argument) keepAweme(item);
            } else {
                keepAweme(argument);
            }
        } catch (Throwable unavailable) {
            // Argument access is best effort; failure only means fewer captured items.
        }
    }

    private void keepAweme(Object candidate) {
        if (candidate == null) return;
        String id = identity(candidate);
        if (id == null) return;
        synchronized (awemes) {
            appendHits++;
            if (!awemeIds.add(id)) return;
            if (awemes.size() >= MAX_ITEMS) return;
            awemes.add(candidate);
        }
    }

    private int capturedCount() {
        synchronized (awemes) { return awemes.size(); }
    }
    private void keepModel(Object model) {
        if (model == null) return;
        synchronized (models) {
            for (Object known : models) if (known == model) return;
            models.add(new WeakHolder(model));
            if (models.size() > 8) models.remove(0);
        }
    }

    /** Keeps a weak reference so repeated renders cannot pin host models forever. */
    private static final class WeakHolder {
        private final WeakReference<Object> reference;
        WeakHolder(Object value) { reference = new WeakReference<>(value); }
        Object get() { return reference.get(); }
    }

    private void attach(Object owner) {
        if (owner == null) return;
        onMain(() -> {
            if (closed || !registration.isActive()) return;
            Header header = headers.get(owner);
            if (header == null) {
                View root;
                try { root = (View) headerRoot.invoke(owner); }
                catch (ReflectiveOperationException | RuntimeException failure) { return; }
                if (root == null || !root.isAttachedToWindow()) return;
                header = new Header(root);
                headers.put(owner, header);
            }
            header.refresh();
        });
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private final class Header {
        private final WeakReference<View> stats;
        private Button button;
        private ViewGroup host;

        Header(View stats) { this.stats = new WeakReference<>(stats); }

        void refresh() {
            LocalOptions current = options;
            if (current == null || !current.enabled("profile_batch_download", false)) { detach(); return; }
            View view = stats.get();
            if (view == null || !view.isAttachedToWindow()) { detach(); return; }
            if (button != null && button.isAttachedToWindow()) { reposition(); return; }
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
                created.setPadding(dp(view, 14), dp(view, 3), dp(view, 14), dp(view, 3));
                created.setOnClickListener(v -> toggle());
                button = created;
            }
            // Draw above the statistics row inside the header, so the host layout is never reflowed.
            host = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
            if (host == null) return;
            try {
                if (button.getParent() == null) host.getOverlay().add(button);
                button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                reposition();
                DouAiLog.event("profile_batch_download", "inject", "overlay_above_statistics",
                        "host=" + host.getClass().getSimpleName());
            } catch (RuntimeException failure) {
                DouAiLog.event("profile_batch_download", "inject", "overlay_rejected", "reason=runtime");
            }
        }

        private void reposition() {
            View view = stats.get();
            if (button == null || host == null || view == null) return;
            int height = button.getMeasuredHeight() > 0 ? button.getMeasuredHeight() : dp(view, 28);
            int width = button.getMeasuredWidth();
            float x = Math.max(0, host.getWidth() - width - dp(view, 12));
            float y = Math.max(0, view.getTop() - height - dp(view, 2));
            button.setX(x);
            button.setY(y);
        }

        void detach() {
            Button view = button;
            button = null;
            if (view != null && host != null) {
                try { host.getOverlay().remove(view); } catch (RuntimeException ignored) { }
            }
            host = null;
        }

        private int dp(View view, int value) {
            float density = view.getResources().getDisplayMetrics().density;
            return Math.round(value * density);
        }

        private void toggle() {
            if (running.get()) { running.set(false); return; }
            LocalOptions current = options;
            View view = stats.get();
            if (current == null || view == null) return;
            if (!running.compareAndSet(false, true)) return;
            button.setText("正在收集…");
            Context context = view.getContext().getApplicationContext();
            String tree = current.downloadSaveTree();
            Thread worker = new Thread(() -> run(context, current, tree), "DouAi-ProfileBatch");
            worker.setDaemon(true);
            worker.start();
        }

        private void setLabel(String text) { if (button != null) button.setText(text); }
    }

    private void run(Context context, LocalOptions current, String tree) {
        int queued = 0, failed = 0, skipped = 0;
        try {
            List<Object> items = collect();
            DouAiLog.event("profile_batch_download", "collect", items.isEmpty() ? "no_items" : "items_found",
                    "count=" + items.size() + ";models=" + modelCount() + ";captured=" + capturedCount() + ";append_hits=" + appendHits);
            for (int index = 0; index < items.size(); index++) {
                if (!running.get() || closed) break;
                List<DownloadActionHook.MediaAsset> assets =
                        DownloadActionHook.mediaAssets(items.get(index), true, current);
                if (assets.isEmpty()) { skipped++; continue; }
                for (DownloadActionHook.MediaAsset asset : assets) {
                    if (!running.get() || closed) break;
                    boolean accepted = asset.image
                            ? MediaStoreDownloader.downloadImage(context, asset.url, asset.name, tree, this::log)
                            : MediaStoreDownloader.download(context, asset.url, asset.name, false, tree, this::log);
                    if (accepted) queued++; else failed++;
                    sleep(ITEM_DELAY_MS);
                }
                int position = index + 1;
                onMain(() -> {
                    for (Header header : new ArrayList<>(headers.values())) header.setLabel(progressLabel(position, items.size()));
                });
            }
        } catch (RuntimeException failure) {
            DouAiLog.event("profile_batch_download", "run", "failed", "reason=runtime");
        } finally {
            running.set(false);
            int total = queued, errors = failed, empty = skipped;
            onMain(() -> {
                for (Header header : new ArrayList<>(headers.values())) header.setLabel("批量下载");
                try {
                    Toast.makeText(context, total == 0
                            ? "未读取到作品（追加回调 " + appendHits + " 次）：请先在主页下滑浏览几屏再试"
                            : "已加入下载 " + total + " 项" + (errors > 0 ? "，失败 " + errors : "")
                            + (empty > 0 ? "，跳过 " + empty : ""), Toast.LENGTH_SHORT).show();
                } catch (RuntimeException ignored) { }
            });
            DouAiLog.event("profile_batch_download", "finish", "completed",
                    "queued=" + queued + ";failed=" + failed + ";empty=" + skipped);
        }
    }

    private int modelCount() {
        synchronized (models) { return models.size(); }
    }

    /**
     * Collects works from the captured host models first; the list adapter is only a fallback. The host
     * list widget is scrolled between rounds so later pages are loaded by the host itself.
     */
    private List<Object> collect() {
        List<Object> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int stable = 0;
        for (int round = 0; round <= MAX_SCROLLS && stable < STABLE_ROUNDS; round++) {
            if (!running.get() || closed) break;
            int before = ordered.size();
            mergeCaptured(ordered, seen);
            mergeModels(ordered, seen);
            mergeAdapter(ordered, seen);
            if (ordered.size() >= MAX_ITEMS) break;
            if (ordered.size() == before) stable++; else stable = 0;
            if (!scroll()) break;
            sleep(SCROLL_DELAY_MS);
        }
        return ordered.size() > MAX_ITEMS ? new ArrayList<>(ordered.subList(0, MAX_ITEMS)) : ordered;
    }

    private void mergeCaptured(List<Object> ordered, Set<String> seen) {
        ArrayList<Object> snapshot;
        synchronized (awemes) { snapshot = new ArrayList<>(awemes); }
        mergeAll(snapshot, ordered, seen);
    }
    private void mergeModels(List<Object> ordered, Set<String> seen) {
        ArrayList<Object> snapshot = new ArrayList<>();
        synchronized (models) {
            for (Object holder : models) {
                if (holder instanceof WeakHolder) {
                    Object value = ((WeakHolder) holder).get();
                    if (value != null) snapshot.add(value);
                }
            }
        }
        for (Object model : snapshot) mergeAll(readItems(model), ordered, seen);
    }

    private void mergeAdapter(List<Object> ordered, Set<String> seen) {
        View list = listView.get();
        if (list == null) list = findListView();
        Object adapter = adapterOf(list);
        if (adapter != null) mergeAll(readItems(adapter), ordered, seen);
    }

    private void mergeAll(List<Object> source, List<Object> ordered, Set<String> seen) {
        for (Object candidate : source) {
            String id = identity(candidate);
            if (id == null || !seen.add(id)) continue;
            ordered.add(candidate);
            if (ordered.size() >= MAX_ITEMS) return;
        }
    }

    private boolean scroll() {
        View list = listView.get();
        if (list == null) list = findListView();
        if (list == null) return false;
        try {
            Method smooth = list.getClass().getMethod("smoothScrollBy", int.class, int.class);
            smooth.invoke(list, 0, Math.max(1, list.getHeight()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static String identity(Object candidate) {
        if (candidate == null) return null;
        Object id = DownloadActionHook.jsonValue(candidate, "aweme_id");
        if (id == null) return null;
        String text = String.valueOf(id);
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    /** Finds a list widget in the current window as a fallback when the hook has not fired yet. */
    private View findListView() {
        for (Header header : new ArrayList<>(headers.values())) {
            View root = header.stats.get();
            if (root == null) continue;
            ArrayList<View> queue = new ArrayList<>();
            queue.add(root.getRootView());
            for (int index = 0; index < queue.size() && index < 400; index++) {
                View view = queue.get(index);
                if (isListLike(view) && adapterOf(view) != null) return view;
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int child = 0; child < group.getChildCount(); child++) queue.add(group.getChildAt(child));
                }
            }
        }
        return null;
    }

    private static boolean isListLike(View view) {
        for (Class<?> type = view.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            String name = type.getName();
            if ("androidx.recyclerview.widget.RecyclerView".equals(name)
                    || "androidx.viewpager2.widget.ViewPager2".equals(name)) return true;
        }
        String name = view.getClass().getName();
        return name.contains("RecyclerView") || name.endsWith("ListView") || name.endsWith("GridView");
    }

    private static Object adapterOf(View view) {
        if (view == null || !isListLike(view)) return null;
        try {
            Method getAdapter = view.getClass().getMethod("getAdapter");
            Object adapter = getAdapter.invoke(view);
            return adapter == null ? null : adapter;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    static List<Object> readItems(Object source) {
        ArrayList<Object> items = new ArrayList<>();
        if (source == null) return items;
        collectItems(source, items, 0);
        return items;
    }

    private static void collectItems(Object source, List<Object> sink, int depth) {
        if (source == null || depth > 3 || sink.size() >= MAX_ITEMS) return;
        if (source instanceof Collection<?>) {
            for (Object item : (Collection<?>) source) if (item != null) sink.add(item);
            return;
        }
        if (collectIndexed(source, sink)) return;
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Collection.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    collectItems(field.get(source), sink, depth + 1);
                    if (!sink.isEmpty()) return;
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
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
        }
    }

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

    private void log(String message) {
        try { DouAiLog.event("profile_batch_download", "transfer", "message", "text=" + DouAiLog.reference(message)); }
        catch (Throwable ignored) { }
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
