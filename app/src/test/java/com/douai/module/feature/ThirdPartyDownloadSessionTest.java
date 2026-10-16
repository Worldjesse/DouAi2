package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

/** Exercises parser ownership, user confirmation, and the detached accepted-download lifetime. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class ThirdPartyDownloadSessionTest {
    @org.junit.Before public void requireDrainedDownloadTasks() { DownloadTaskTestIdle.beforeTest(); }

    @After public void cancelPendingTasks() {
        DownloadTaskTestIdle.afterTest();
    }

    @Test public void defaultModeParsesFirstThenShowsOnlyConfiguredMediaWithoutCreatingAnyFiles() throws Exception {
        try (Harness harness = new Harness()) {
            Map<String, Object> values = harness.values();
            values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true);
            // The official direct preference must not skip the independent third-party confirmation.
            assertFalse(new ThirdPartyDownloadSettings(new LocalOptions(values)).direct);
            harness.start(values);
            harness.awaitParser();
            assertMiuixLoading();
            assertEquals(1, harness.paths.size());
            assertTrue(harness.documents.names.isEmpty());
            harness.parseGate.countDown();
            awaitChoices("图片（3）", "视频（1）");
            assertMiuix(latest());
            assertEquals(List.of("图片（3）", "视频（1）"), labels());
            assertEquals(1, harness.paths.size());
            assertTrue(harness.documents.names.isEmpty());
            choose("图片（3）");
            choose("选择图片保存");
            assertEquals(List.of("data.images · 第 1 项", "data.images · 第 2 项", "data.images · 第 3 项"), labels());
            assertEquals(1, harness.paths.size());
            assertTrue(harness.documents.names.isEmpty());
        }
    }

    @Test public void confirmingASubsetDownloadsOnlyOriginalUrlsWithOriginalSequenceNamesAndMetadata() throws Exception {
        try (Harness harness = new Harness()) {
            harness.start(harness.values());
            harness.awaitParser();
            harness.parseGate.countDown();
            awaitChoices("图片（3）");
            choose("图片（3）");
            choose("选择图片保存");
            check(2);
            check(0);
            assertEquals(1, harness.paths.size());
            assertTrue(harness.documents.names.isEmpty());
            clickButton(AlertDialog.BUTTON_POSITIVE);
            await(() -> noActiveTasks() && harness.documents.files.size() == 3, "selected bundle was not saved");
            assertEquals(Set.of("/first.jpg", "/third.jpg"), harness.mediaPaths());
            Map<String, String> saved = harness.savedFiles();
            assertEquals(Set.of("work_2_data_images.jpg", "work_4_data_images.jpg", "work.txt"), saved.keySet());
            assertEquals("/first.jpg", saved.get("work_2_data_images.jpg"));
            assertEquals("/third.jpg", saved.get("work_4_data_images.jpg"));
            assertTrue(saved.get("work.txt").contains("caption"));
            assertTrue(saved.get("work.txt").contains("unconfigured.mp4"));
            assertFalse(saved.get("work.txt").contains(harness.server.url("/second.jpg").toString()));
            assertEquals(3, harness.paths.size());
            harness.assertOneBundleDirectory();
        }
    }

    @Test public void cancellingTheLoadingDialogCancelsParsingAndNeverShowsMediaChoices() throws Exception {
        try (Harness harness = new Harness()) {
            harness.start(harness.values());
            harness.awaitParser();
            assertMiuixLoading();
            clickButton(AlertDialog.BUTTON_NEGATIVE);
            harness.parseGate.countDown();
            harness.assertCancelledWithoutMedia();
        }
    }

    @Test public void closingTheOwnedSessionWhileParsingPreventsADeferredPopupOrDownload() throws Exception {
        try (Harness harness = new Harness()) {
            harness.start(harness.values());
            harness.awaitParser();
            harness.owner.close();
            ShadowLooper.idleMainLooper();
            harness.parseGate.countDown();
            harness.assertCancelledWithoutMedia();
        }
    }

    @Test public void cancellingAQueuedParserFromGlobalTasksClosesLoadingWithoutRequestingTheParser() throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch occupied = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            String key = "third-party-session-blockers-" + UUID.randomUUID();
            try {
                for (int index = 0; index < 2; index++) {
                    DownloadTasks.Task blocker = DownloadTasks.submit(harness.activity.get().getApplication(),
                            key + '-' + index, "测试工作线程占位", transfer -> {
                                occupied.countDown();
                                release.await();
                                transfer.check();
                                return "测试工作线程已释放";
                            }, ignored -> { });
                    assertNotNull("a worker blocker could not be queued", blocker);
                }
                assertTrue("both download workers must be occupied before queuing the parser",
                        occupied.await(3, TimeUnit.SECONDS));
                harness.start(harness.values());
                assertMiuixLoading();
                AlertDialog loading = latest();
                DownloadTasks.Snapshot parser = DownloadTasks.snapshots().stream()
                        .filter(task -> "第三方解析".equals(task.name) && "queued".equals(task.status))
                        .findFirst().orElse(null);
                assertNotNull("parser must remain queued while both workers are occupied", parser);
                assertEquals(0, harness.paths.size());
                DownloadTasks.cancel(parser.id);
                await(() -> !loading.isShowing() && DownloadTasks.snapshots().stream()
                                .anyMatch(task -> parser.id.equals(task.id) && "cancelled".equals(task.status)),
                        "global cancellation did not close the queued parser's loading dialog");
                assertNoShowingDialog();
                assertEquals(0, harness.paths.size());
                assertTrue(harness.documents.names.isEmpty());
            } finally {
                release.countDown();
            }
            await(() -> noActiveTasks(), "download worker blockers were not released");
            // Releasing workers must not accidentally run the cancelled queued parser afterward.
            assertEquals(0, harness.paths.size());
            assertTrue(harness.documents.names.isEmpty());
        }
    }

    @Test public void pausingTheOwnerActivityWhileParsingCancelsThePendingSession() throws Exception {
        try (Harness harness = new Harness()) {
            harness.start(harness.values());
            harness.awaitParser();
            harness.pause();
            ShadowLooper.idleMainLooper();
            harness.parseGate.countDown();
            harness.assertCancelledWithoutMedia();
        }
    }

    @Test public void invalidatingTheCurrentWorkDuringParsingCannotOpenChoicesOrRequestMedia() throws Exception {
        try (Harness harness = new Harness()) {
            harness.start(harness.values());
            harness.awaitParser();
            harness.valid.set(false);
            // Even when only the validity supplier changes, the late response must be discarded.
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
            harness.parseGate.countDown();
            await(() -> noActiveTasks(), "invalid session did not stop parsing");
            assertNoShowingDialog();
            assertEquals(1, harness.paths.size());
            assertTrue(harness.documents.names.isEmpty());
        }
    }

    @Test public void independentDirectPreferenceUsesConfiguredResourcesWithoutShowingAnyDialog() throws Exception {
        try (Harness harness = new Harness()) {
            Map<String, Object> values = harness.values();
            values.put(ThirdPartyDownloadSettings.DIRECT, true);
            harness.start(values);
            harness.awaitParser();
            assertNoShowingDialog();
            harness.parseGate.countDown();
            await(() -> noActiveTasks() && harness.documents.files.size() == 5, "direct bundle was not saved");
            assertNoShowingDialog();
            assertEquals(Set.of("/configured.mp4", "/first.jpg", "/second.jpg", "/third.jpg"), harness.mediaPaths());
            assertEquals(Set.of("work_1_data_video.mp4", "work_2_data_images.jpg", "work_3_data_images.jpg",
                    "work_4_data_images.jpg", "work.txt"), harness.savedFiles().keySet());
            assertEquals(5, harness.paths.size());
            harness.assertOneBundleDirectory();
        }
    }

    @Test public void directModeRejectsInitiallyInvalidOrThrowingOwnerBeforeCreatingAnyTask() throws Exception {
        try (Harness harness = new Harness()) {
            Map<String, Object> values = harness.values();
            values.put(ThirdPartyDownloadSettings.DIRECT, true);
            LocalOptions options = new LocalOptions(values);
            Set<String> before = DownloadTasks.snapshots().stream().map(task -> task.id).collect(Collectors.toSet());
            AtomicInteger checks = new AtomicInteger();
            AutoCloseable invalid = ThirdPartyDownload.startOwned(harness.activity.get(), harness.work, options,
                    ignored -> { }, () -> { checks.incrementAndGet(); return false; });
            assertNull(invalid);
            AutoCloseable throwing = ThirdPartyDownload.startOwned(harness.activity.get(), harness.work, options,
                    ignored -> { }, () -> { checks.incrementAndGet(); throw new IllegalStateException("owner expired"); });
            assertNull(throwing);
            ShadowLooper.idleMainLooper();

            assertEquals(2, checks.get());
            assertEquals(before, DownloadTasks.snapshots().stream().map(task -> task.id).collect(Collectors.toSet()));
            assertNull(harness.server.takeRequest(150, TimeUnit.MILLISECONDS));
            assertTrue(harness.paths.isEmpty());
            assertTrue(harness.documents.names.isEmpty());
            assertTrue(harness.documents.files.isEmpty());
            assertNoShowingDialog();
        }
    }

    @Test public void acceptedBundleOutlivesItsOwnerAndDoesNotGetCancelledWhenTheActivityPauses() throws Exception {
        try (Harness harness = new Harness()) {
            harness.holdMedia = true;
            harness.start(harness.values());
            harness.awaitParser();
            harness.parseGate.countDown();
            awaitChoices("图片（3）");
            choose("图片（3）");
            choose("选择图片保存");
            check(1);
            clickButton(AlertDialog.BUTTON_POSITIVE);
            assertTrue("confirmed bundle did not request its selected file", harness.mediaReached.await(3, TimeUnit.SECONDS));
            harness.owner.close();
            harness.pause();
            harness.valid.set(false);
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
            harness.mediaGate.countDown();
            await(() -> noActiveTasks() && harness.documents.files.size() == 2, "accepted bundle was cancelled with its owner");
            assertEquals(Set.of("/second.jpg"), harness.mediaPaths());
            assertEquals("/second.jpg", harness.savedFiles().get("work_3_data_images.jpg"));
            assertTrue(harness.savedFiles().containsKey("work.txt"));
            assertNoShowingDialog();
            assertEquals(2, harness.paths.size());
        }
    }

    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void assertMiuixLoading() {
        assertMiuix(latest());
        assertNull("loading must not offer media choices before parsing", latest().getListView());
    }

    private static void assertMiuix(AlertDialog dialog) {
        assertTrue(dialog.getClass().getName().contains("MiuixAlertDialog"));
    }

    private static void assertNoShowingDialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("an abandoned/direct session must not show a dialog", dialog == null || !dialog.isShowing());
    }

    private static List<String> labels() {
        List<String> result = new ArrayList<>();
        AlertDialog dialog = latest();
        assertNotNull(dialog.getListView());
        for (int index = 0; index < dialog.getListView().getCount(); index++) {
            result.add(String.valueOf(dialog.getListView().getAdapter().getItem(index)));
        }
        return result;
    }

    private static void awaitChoices(String... expected) {
        await(() -> {
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            return dialog != null && dialog.isShowing() && dialog.getListView() != null
                    && labels().containsAll(List.of(expected));
        }, "third-party media choices were not shown");
    }

    private static void choose(String label) {
        int index = labels().indexOf(label);
        assertTrue("Missing choice: " + label, index >= 0);
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }

    private static void check(int index) {
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }

    private static void clickButton(int which) {
        assertNotNull(latest().getButton(which));
        latest().getButton(which).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static boolean noActiveTasks() {
        return DownloadTasks.snapshots().stream().noneMatch(task -> "running".equals(task.status)
                || "queued".equals(task.status) || "cancelling".equals(task.status));
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        do {
            ShadowLooper.idleMainLooper();
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
        fail(message);
    }

    public static final class Work {
        public String aweme_id = "123456", share_url = "https://www.douyin.com/video/123456";
        public DownloadActionHookTest.VideoFixture video = new DownloadActionHookTest.VideoFixture();
    }

    private static final class Harness implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        final MockWebServer server = new MockWebServer();
        final CountDownLatch parserReached = new CountDownLatch(1), parseGate = new CountDownLatch(1);
        final CountDownLatch mediaReached = new CountDownLatch(1), mediaGate = new CountDownLatch(1);
        final List<String> paths = new CopyOnWriteArrayList<>();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final AtomicInteger dispatching = new AtomicInteger();
        final Set<String> previousTasks = DownloadTasks.snapshots().stream().map(task -> task.id).collect(Collectors.toSet());
        final Work work = new Work();
        AutoCloseable owner;
        volatile boolean holdMedia;
        boolean paused;

        Harness() throws Exception {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    dispatching.incrementAndGet();
                    try {
                        String path = request.getPath();
                        paths.add(path);
                        if (path.startsWith("/parse")) {
                            parserReached.countDown();
                            assertTrue("parser response gate was not released", parseGate.await(5, TimeUnit.SECONDS));
                            return closingResponse("application/json", body());
                        }
                        mediaReached.countDown();
                        if (holdMedia) assertTrue("media response gate was not released", mediaGate.await(5, TimeUnit.SECONDS));
                        return closingResponse(path.endsWith(".mp4") ? "video/mp4" : "image/jpeg", path);
                    } finally {
                        dispatching.decrementAndGet();
                    }
                }
            });
            server.start();
            work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official-fallback.mp4").toString());
        }

        private static MockResponse closingResponse(String type, String body) {
            // Cancellation can disconnect the client before a held parser response is written.
            // Never leave that server connection waiting for another keep-alive request.
            return new MockResponse().addHeader("Content-Type", type).addHeader("Connection", "close")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END).setBody(body);
        }

        Map<String, Object> values() {
            Map<String, Object> values = new HashMap<>();
            values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            values.put(ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse").toString() + "?url={url}");
            values.put(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
            values.put(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                    ThirdPartyDownloadSettings.encodeFields(List.of("data.video", "data.images")));
            values.put(ThirdPartyDownloadSettings.NAMING_FIELDS, ThirdPartyDownloadSettings.encodeFields(List.of("data.id")));
            values.put(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE);
            return values;
        }

        void start(Map<String, Object> values) {
            owner = ThirdPartyDownload.startOwned(activity.get(), work, new LocalOptions(values), ignored -> { }, valid::get);
            assertNotNull(owner);
            ShadowLooper.idleMainLooper();
        }

        void awaitParser() throws Exception {
            assertTrue("third-party parser was not requested", parserReached.await(3, TimeUnit.SECONDS));
            assertTrue(paths.get(0).startsWith("/parse?url="));
            assertEquals(1, paths.size());
        }

        String body() {
            try {
                JSONObject data = new JSONObject().put("id", "work").put("desc", "caption")
                        .put("video", server.url("/configured.mp4").toString())
                        .put("images", new JSONArray(List.of(server.url("/first.jpg").toString(),
                                server.url("/second.jpg").toString(), server.url("/third.jpg").toString())))
                        .put("extra_video", server.url("/unconfigured.mp4").toString())
                        .put("audio", server.url("/unconfigured.mp3").toString());
                return new JSONObject().put("data", data).toString();
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        void pause() {
            if (!paused) { activity.pause(); paused = true; }
        }

        Set<String> mediaPaths() {
            return paths.stream().filter(path -> !path.startsWith("/parse")).collect(Collectors.toSet());
        }

        Map<String, String> savedFiles() throws Exception {
            Map<String, String> result = new HashMap<>();
            for (String id : documents.files.keySet()) {
                result.put(documents.names.get(id), Files.readString(documents.files.get(id).toPath()));
            }
            return result;
        }

        void assertOneBundleDirectory() {
            Set<String> parents = new HashSet<>();
            for (String id : documents.files.keySet()) parents.add(documents.parents.get(id));
            assertEquals(1, parents.size());
            String folder = parents.iterator().next();
            assertEquals("root", documents.parents.get(folder));
            assertTrue(documents.names.get(folder).startsWith("work_"));
        }

        void assertCancelledWithoutMedia() {
            await(() -> noActiveTasks(), "cancelled parser did not stop");
            assertNoShowingDialog();
            assertEquals(1, paths.size());
            assertTrue(documents.names.isEmpty());
            assertTrue(DownloadTasks.snapshots().stream().anyMatch(task -> !previousTasks.contains(task.id)
                    && "cancelled".equals(task.status)));
        }

        @Override public void close() throws Exception {
            if (owner != null) owner.close();
            ShadowLooper.idleMainLooper();
            // Invalidate ownership before unblocking a late parse completion. Finish all client
            // work and dispatch callbacks before MockWebServer shuts down its connection queues.
            parseGate.countDown();
            mediaGate.countDown();
            try {
                await(() -> noActiveTasks() && dispatching.get() == 0,
                        "third-party fixture still has active client work or response dispatch");
            } finally {
                try {
                    pause();
                    activity.stop().destroy();
                } finally {
                    server.close();
                }
            }
        }
    }
}
