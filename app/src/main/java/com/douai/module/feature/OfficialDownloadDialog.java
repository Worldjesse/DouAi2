package com.douai.module.feature;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.ui.ModuleTheme;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Offers explicit official media choices before creating any download task. */
final class OfficialDownloadDialog implements AutoCloseable {
    interface LiveSaver {
        boolean start(Context context, Object aweme, int imageIndex, LocalOptions options, Consumer<String> log);
    }

    private final LiveSaver liveSaver;
    private final AnalyticsActionCapture analytics;
    private Context context;
    private Object aweme;
    private LocalOptions options;
    private Consumer<String> log;
    private BooleanSupplier valid;
    private AlertDialog dialog;
    private boolean closed;
    private long navigation;

    OfficialDownloadDialog(Context context, Object aweme, LocalOptions options,
                           Consumer<String> log, BooleanSupplier valid) {
        this(context, aweme, options, log, valid, OfficialLivePhotoDownload::start);
    }

    OfficialDownloadDialog(Context context, Object aweme, LocalOptions options,
                           Consumer<String> log, BooleanSupplier valid, LiveSaver liveSaver) {
        this.context = ModuleTheme.themedContext(context);
        this.analytics = AnalyticsActionCapture.capture(context, aweme);
        this.aweme = aweme;
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        this.log = log == null ? ignored -> { } : log;
        this.valid = valid == null ? () -> true : valid;
        this.liveSaver = liveSaver == null ? OfficialLivePhotoDownload::start : liveSaver;
    }

    void show() {
        if (!available()) return;
        List<Object> images = images();
        ArrayList<Integer> liveIndexes = new ArrayList<>();
        ArrayList<Integer> motionIndexes = new ArrayList<>();
        for (int index = 0; index < images.size(); index++) {
            Object image = images.get(index);
            boolean live = OfficialMediaModel.isLivePhoto(aweme, image);
            if (live) liveIndexes.add(index);
            // Explicit LivePhoto metadata keeps the category visible when a host URL field changes.
            if (live || motionUrl(image) != null) motionIndexes.add(index);
        }
        ArrayList<Choice> choices = new ArrayList<>();
        if (!images.isEmpty()) {
            choices.add(new Choice("图片（" + images.size() + "）", () -> showCategory(Category.IMAGE, images, allIndexes(images.size()))));
            if (!motionIndexes.isEmpty()) choices.add(new Choice("动态视频（" + motionIndexes.size() + "）",
                    () -> showCategory(Category.MOTION, images, motionIndexes)));
            if (!liveIndexes.isEmpty()) choices.add(new Choice("实况（" + liveIndexes.size() + "）",
                    () -> showCategory(Category.LIVE, images, liveIndexes)));
        } else {
            String video = motionUrl(aweme);
            if (video != null) {
                String source = video;
                choices.add(new Choice("视频", () -> showChoices("视频", List.of(
                        new Choice("保存视频", () -> saveVideo(source, 0))), this::show)));
            }
            String audio = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(
                    DownloadActionHook.jsonValue(aweme, "music"), "play_url"));
            if (audio != null) choices.add(new Choice("音频", () -> showChoices("音频", List.of(
                    new Choice("保存音频（原声/配乐）", () -> saveAudio(audio))), this::show)));
        }
        logChoices(images, motionIndexes.size(), liveIndexes.size(), choices);
        if (choices.isEmpty()) {
            toast("当前作品没有可保存的媒体地址");
            close();
            return;
        }
        showChoices("保存媒体", choices, null);
    }

    private void showChoices(String title, List<Choice> choices, Runnable back) {
        if (!available()) return;
        long page = ++navigation;
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label;
        AlertDialog.Builder builder = new MiuixDialogBuilder(context, true).setTitle(title)
                .setItems(labels, (ignored, which) -> {
                    if (available() && navigation == page && which >= 0 && which < choices.size()) {
                        choices.get(which).action.run();
                    }
                })
                .setNegativeButton("取消", (ignored, which) -> close());
        if (back != null) builder.setNeutralButton("返回", (ignored, which) -> {
            if (available() && navigation == page) back.run();
        });
        AlertDialog next = builder.create();
        replace(next);
        next.show();
        ModuleTheme.bind(next.getWindow().getDecorView());
    }

    private void showCategory(Category category, List<Object> images, List<Integer> indexes) {
        if (!available()) return;
        ArrayList<Choice> choices = new ArrayList<>();
        if (images.size() == 1) {
            choices.add(new Choice("保存" + category.label, () -> saveSelection(category, images, toIndexes(indexes))));
        } else {
            int current = currentImageIndex(aweme);
            if (indexes.contains(current) && (category != Category.IMAGE || imageUrl(images.get(current)) != null)) {
                choices.add(new Choice("保存当前" + category.currentLabel,
                        () -> saveSelection(category, images, new int[]{current})));
            }
            choices.add(new Choice("选择" + category.label + "保存", () -> showSelection(category, images, indexes)));
            choices.add(new Choice("保存全部" + category.label, () -> saveSelection(category, images, toIndexes(indexes))));
        }
        showChoices(category.label, choices, this::show);
    }

    private void showSelection(Category category, List<Object> images, List<Integer> indexes) {
        if (!available()) return;
        long page = ++navigation;
        String[] labels = new String[indexes.size()];
        boolean[] selected = new boolean[indexes.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = "第 " + (indexes.get(i) + 1) + " 张" + category.selectionSuffix;
        AlertDialog next = new MiuixDialogBuilder(context, true).setTitle("选择" + category.label)
                .setMultiChoiceItems(labels, selected, (ignored, which, checked) -> selected[which] = checked)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", (ignored, which) -> close())
                .setNeutralButton("返回", (ignored, which) -> {
                    if (available() && navigation == page) showCategory(category, images, indexes);
                }).create();
        next.setOnShowListener(ignored -> next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (!available() || navigation != page) return;
            ArrayList<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(indexes.get(i));
            if (chosen.isEmpty()) { toast("请先选择" + category.label); return; }
            saveSelection(category, images, toIndexes(chosen));
        }));
        replace(next);
        next.show();
        ModuleTheme.bind(next.getWindow().getDecorView());
    }

    private void saveSelection(Category category, List<Object> images, int[] indexes) {
        if (category == Category.IMAGE) saveImages(images, indexes);
        else if (category == Category.MOTION) saveDynamicVideos(images, indexes);
        else saveLivePhotos(indexes);
    }

    private void replace(AlertDialog next) {
        AlertDialog previous = dialog;
        dialog = next;
        next.setOnCancelListener(ignored -> close());
        next.setOnDismissListener(ignored -> { if (dialog == next) close(); });
        if (previous != null) previous.dismiss();
    }

    private void saveImages(List<Object> images, int[] indexes) {
        if (!readyToSave()) return;
        ArrayList<DownloadActionHook.MediaAsset> assets = new ArrayList<>();
        for (int index : indexes) {
            if (index < 0 || index >= images.size()) { toast("当前图片位置不可用"); return; }
            String url = imageUrl(images.get(index));
            if (url == null) { toast("所选图片没有可保存的媒体地址"); return; }
            assets.add(new DownloadActionHook.MediaAsset(url,
                    MediaNaming.forAweme(aweme, options, "picture", index + 1, imageExtension(url)), true));
        }
        Context target = context;
        String tree = options.downloadSaveTree();
        Consumer<String> logger = log;
        close();
        analytics.manual("download_request", "下载任务请求", "request");
        int accepted = 0;
        for (DownloadActionHook.MediaAsset asset : assets) {
            if (MediaStoreDownloader.downloadImage(target, asset.url, asset.name, tree, logger)) accepted++;
        }
        if (accepted > 0) analytics.manual("download_queued", "下载任务已加入", "accepted");
        if (accepted < assets.size()) Toast.makeText(target, "部分图片未加入下载，请检查下载任务", Toast.LENGTH_SHORT).show();
    }

    private void saveVideo(String source, int imageIndex) {
        if (!readyToSave()) return;
        Context target = context;
        String name = MediaNaming.forAweme(aweme, options, "video", imageIndex, "mp4");
        String tree = options.downloadSaveTree();
        Consumer<String> logger = log;
        close();
        analytics.manual("download_request", "下载任务请求", "request");
        if (!MediaStoreDownloader.download(target, source, name, false, tree, logger)) {
            Toast.makeText(target, "视频未加入下载，请检查下载任务", Toast.LENGTH_SHORT).show();
        } else analytics.manual("download_queued", "下载任务已加入", "accepted");
    }

    private void saveAudio(String source) {
        if (!readyToSave()) return;
        Context target = context;
        String name = MediaNaming.forAweme(aweme, options, "audio", 0, "mp3");
        String tree = options.downloadSaveTree();
        Consumer<String> logger = log;
        close();
        analytics.manual("download_request", "下载任务请求", "request");
        if (!MediaStoreDownloader.download(target, source, name, true, tree, logger)) {
            Toast.makeText(target, "音频未加入下载，请检查下载任务", Toast.LENGTH_SHORT).show();
        } else analytics.manual("download_queued", "下载任务已加入", "accepted");
    }

    private void saveDynamicVideos(List<Object> images, int[] indexes) {
        if (!readyToSave()) return;
        ArrayList<DownloadActionHook.MediaAsset> assets = new ArrayList<>();
        for (int index : indexes) {
            if (index < 0 || index >= images.size()) { toast("当前图片位置不可用"); return; }
            String video = motionUrl(images.get(index));
            if (video == null) { toast("所选动态视频缺少可下载的媒体地址"); return; }
            assets.add(new DownloadActionHook.MediaAsset(video,
                    MediaNaming.forAweme(aweme, options, "video", index + 1, "mp4"), false));
        }
        Context target = context;
        String tree = options.downloadSaveTree();
        Consumer<String> logger = log;
        close();
        analytics.manual("download_request", "下载任务请求", "request");
        int accepted = 0;
        for (DownloadActionHook.MediaAsset asset : assets) {
            if (MediaStoreDownloader.download(target, asset.url, asset.name, false, tree, logger)) accepted++;
        }
        if (accepted > 0) analytics.manual("download_queued", "下载任务已加入", "accepted");
        if (accepted < assets.size()) {
            Toast.makeText(target, accepted == 0 ? "动态视频未加入下载，请检查下载任务"
                    : "部分动态视频未加入下载，请检查下载任务", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveLivePhotos(int[] imageIndexes) {
        if (!readyToSave()) return;
        if (imageIndexes.length == 0) { toast("请先选择实况"); return; }
        // Validate every selected source before any task can be queued.
        for (int imageIndex : imageIndexes) {
            try { OfficialLivePhotoDownload.plan(aweme, imageIndex, options); }
            catch (RuntimeException failure) {
                toast("所选实况缺少可下载的图片或动态视频");
                return;
            }
        }
        Context target = context;
        Object work = aweme;
        LocalOptions current = options;
        Consumer<String> logger = log;
        close();
        analytics.manual("download_request", "下载任务请求", "request");
        int accepted = 0;
        for (int imageIndex : imageIndexes) {
            if (liveSaver.start(target, work, imageIndex, current, logger)) accepted++;
        }
        if (accepted > 0) analytics.manual("download_queued", "下载任务已加入", "accepted");
        if (accepted < imageIndexes.length) {
            Toast.makeText(target, accepted == 0 ? "实况未加入下载，请检查下载任务"
                    : "部分实况未加入下载，请检查下载任务", Toast.LENGTH_SHORT).show();
        }
    }

    private void logChoices(List<Object> images, int motionCategoryCount, int liveCount, List<Choice> choices) {
        ArrayList<Integer> clips = new ArrayList<>();
        ArrayList<Integer> livePhotoTypes = new ArrayList<>();
        int motionCount = 0;
        for (int index = 0; index < images.size(); index++) {
            Object image = images.get(index);
            if (index < 32) {
                clips.add(integer(image, "clip_type", "clipType", -1));
                livePhotoTypes.add(integer(image, "live_photo_type", "livePhotoType", -1));
            }
            if (DownloadActionHook.findPlaybackVideoUrl(image) != null) motionCount++;
        }
        String hostPackage = "n/a";
        String hostVersion = "n/a";
        try {
            hostPackage = context.getPackageName();
            String version = context.getPackageManager().getPackageInfo(hostPackage, 0).versionName;
            if (version != null && !version.isEmpty()) hostVersion = version;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        ArrayList<String> labels = new ArrayList<>();
        for (Choice choice : choices) labels.add(choice.label);
        try {
            log.accept("Official download choices: host=" + hostPackage + ", versionName=" + hostVersion
                    + ", images=" + images.size()
                    + ", is_live_photo=" + integer(aweme, "is_live_photo", "isLivePhoto", -1)
                    + ", currentIndex=" + currentImageIndex(aweme) + ", clipTypes=" + clips
                    + ", livePhotoTypes=" + livePhotoTypes
                    + ", motionCount=" + motionCount + ", liveCount=" + liveCount
                    + ", categoryCounts={images=" + images.size() + ", motion=" + motionCategoryCount
                    + ", live=" + liveCount + "}, labels=" + labels);
        } catch (RuntimeException ignored) { }
    }

    private static int[] toIndexes(List<Integer> indexes) {
        int[] result = new int[indexes.size()];
        for (int i = 0; i < result.length; i++) result[i] = indexes.get(i);
        return result;
    }

    private static List<Integer> allIndexes(int count) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(i);
        return result;
    }

    private boolean readyToSave() {
        if (!available()) return false;
        if (options.downloadSaveTree().isEmpty() && Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            toast("请先授予抖音存储权限");
            return false;
        }
        return true;
    }

    private boolean available() {
        if (closed || context == null || aweme == null) return false;
        boolean active;
        try { active = valid.getAsBoolean(); }
        catch (RuntimeException failure) { active = false; }
        if (!active || new ThirdPartyDownloadSettings(options).thirdParty) {
            close();
            return false;
        }
        return true;
    }

    private List<Object> images() {
        ArrayList<Object> result = new ArrayList<>();
        Object value = DownloadActionHook.jsonValue(aweme, "images");
        if (value instanceof Iterable<?>) for (Object image : (Iterable<?>) value) result.add(image);
        return result;
    }

    private String imageUrl(Object image) {
        boolean clean = options.enabled("remove_watermark", true);
        String url = clean ? DownloadActionHook.firstUrlValue(
                DownloadActionHook.jsonValue(image, "watermark_free_download_url_list")) : null;
        if (url == null) url = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "url_list"));
        if (url == null && !clean) {
            url = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "download_url_list"));
        }
        return url;
    }

    private String motionUrl(Object source) {
        String video = DownloadActionHook.findPlaybackVideoUrl(source);
        if (video == null && !options.enabled("remove_watermark", true)) {
            video = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(
                    DownloadActionHook.jsonValue(source, "video"), "download_addr"));
        }
        return video;
    }

    private static int currentImageIndex(Object work) {
        int index = integer(work, "photosCurPos", "photos_cur_pos", Integer.MIN_VALUE);
        if (index != Integer.MIN_VALUE) return index;
        try {
            Method method = work.getClass().getMethod("getPhotosCurPos");
            Object value = method.invoke(work);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return -1; }
    }

    private static int integer(Object source, String first, String second, int fallback) {
        Object value = DownloadActionHook.jsonValue(source, first);
        if (!(value instanceof Number)) value = DownloadActionHook.jsonValue(source, second);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String imageExtension(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                path = path.toLowerCase(Locale.ROOT);
                for (String extension : new String[]{"png", "webp", "gif", "heic"}) {
                    if (path.endsWith("." + extension) || path.endsWith("~" + extension)) return extension;
                }
            }
        } catch (IllegalArgumentException ignored) { }
        return "jpg";
    }

    private void toast(String message) {
        if (context != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    @Override public void close() {
        closed = true;
        AlertDialog current = dialog;
        dialog = null;
        if (current != null) current.dismiss();
        context = null;
        aweme = null;
        options = null;
        log = null;
        valid = null;
    }

    private static final class Choice {
        final String label;
        final Runnable action;
        Choice(String label, Runnable action) { this.label = label; this.action = action; }
    }

    private enum Category {
        IMAGE("图片", "图片", ""),
        MOTION("动态视频", "动态视频", "动态视频"),
        LIVE("实况", "实况", "实况");

        final String label, currentLabel, selectionSuffix;
        Category(String label, String currentLabel, String selectionSuffix) {
            this.label = label;
            this.currentLabel = currentLabel;
            this.selectionSuffix = selectionSuffix;
        }
    }
}
