package com.douai.module.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;

import com.douai.module.config.LocalOptions;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real loaders and encoders with bounded fixture transports and Android retriever shadows. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AutomationReplyMediaTest {
    private Context context;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        ShadowMediaMetadataRetriever.reset();
    }

    @Test public void commentImageSchemaUsesExplicitUrlsInPriorityOrderAndCapsAtFour() {
        Comment comment = comment("""
                {"image_list":[
                  {"origin_url":{"url_list":["file:///private/no", "https://p.byteimg.com/one.jpg"]},
                   "download_url":{"url_list":["https://p.byteimg.com/watermark.jpg"]}},
                  {"medium_url":{"url_list":["https://p.byteimg.com/two.jpg"]}},
                  {"crop_url":{"url_list":["https://p.byteimg.com/three.jpg"]}},
                  {"thumb_url":{"url_list":["https://p.byteimg.com/four.jpg"]}},
                  {"origin_url":{"url_list":["https://unrelated.test/not-selected.jpg"]}}
                ]}
                """);
        assertTrue(AutomationReplyMedia.hasCommentImages(comment));
        List<String> urls = AutomationReplyMedia.commentImageUrls(comment);
        assertEquals(List.of("https://p.byteimg.com/one.jpg", "https://p.byteimg.com/two.jpg",
                "https://p.byteimg.com/three.jpg", "https://p.byteimg.com/four.jpg"), urls);
        assertThrows(UnsupportedOperationException.class, urls::clear);
    }

    @Test public void actualIncomingCommentImageLoadsWithoutMixingWorkImagesOrItsVideo() throws Exception {
        List<String> requests = new ArrayList<>();
        Comment comment = comment("{\"image_list\":[{\"origin_url\":{\"url_list\":[\"https://p.byteimg.com/comment.png\"]}}]}");
        Work work = work("""
                {"images":[{"url_list":["https://p.byteimg.com/work.png"]}],
                 "video":{"play_addr":{"url_list":["https://unrelated.test/not-the-comment.mp4"]}}}
                """);
        MediaReadCancellation cancellation = transport(requests, png(Color.MAGENTA), null);
        List<ChatImage> images = AutomationReplyMedia.comment(context, event(comment, work), options(true, true), cancellation);
        assertEquals(Collections.singletonList("https://p.byteimg.com/comment.png"), requests);
        assertEquals(1, images.size());
        assertColor(Color.MAGENTA, images.get(0));
        assertEquals("对方评论附图", AutomationReplyMedia.commentSourceLabel(event(comment, work), options(true, true)));
    }

    @Test public void replyImageDefaultIsEnabledAndOldCommentSwitchesDoNotOverrideIt() throws Exception {
        Comment comment = comment("{\"image_list\":[{\"download_url\":{\"url_list\":[\"https://p.byteimg.com/reply.png\"]}}]}");
        List<String> requests = new ArrayList<>();
        LocalOptions defaults = new LocalOptions(Map.of(AutomationCommentMedia.RECOGNIZE_IMAGES, false,
                AutomationCommentMedia.RECOGNIZE_VIDEO, true));
        List<ChatImage> images = AutomationReplyMedia.comment(context, event(comment, new Work()), defaults,
                transport(requests, png(Color.RED), null));
        assertEquals(1, images.size());
        assertEquals(1, requests.size());
        assertEquals("对方评论附图", AutomationReplyMedia.commentSourceLabel(event(comment, new Work()), defaults));
    }

    @Test public void disabledSourcesDoNotInspectOrDownloadInvalidMedia() throws Exception {
        List<String> requests = new ArrayList<>();
        Comment badImage = comment("{\"image_list\":[{\"origin_url\":{\"url_list\":[\"https://unrelated.test/image\"]}}]}");
        Work badVideo = work("{\"video\":{\"play_addr\":{\"url_list\":[\"https://unrelated.test/video\"]}}}");
        assertTrue(AutomationReplyMedia.comment(context, event(badImage, badVideo), options(false, false),
                transport(requests, new byte[0], null)).isEmpty());
        assertEquals("", AutomationReplyMedia.commentSourceLabel(event(badImage, badVideo), options(false, false)));
        Work missingCover = work("{\"video\":{\"play_addr\":{\"url_list\":[\"https://v.douyinvod.com/video.mp4\"]}}}");
        assertTrue("image-only reply recognition must not silently download a video",
                AutomationReplyMedia.comment(context, event(new Comment(), missingCover), options(true, false),
                        transport(requests, new byte[0], null)).isEmpty());
        assertTrue(requests.isEmpty());
    }

    @Test public void textCommentDoesNotLoadOriginalWorkCover() throws Exception {
        Work work = work("{\"video\":{\"cover\":{\"url_list\":[\"https://p.byteimg.com/cover.png\"]}}}");
        List<String> requests = new ArrayList<>();
        List<ChatImage> images = AutomationReplyMedia.comment(context, event(new Comment(), work), options(true, false),
                transport(requests, png(Color.BLUE), null));
        assertTrue("a text reply has no incoming image to recognize", images.isEmpty());
        assertTrue("the original work is outside the reply's visual input", requests.isEmpty());
        assertEquals("", AutomationReplyMedia.commentSourceLabel(event(new Comment(), work), options(true, false)));
    }

    @Test public void videoSwitchDoesNotLoadOrLabelOriginalWorkVideo() throws Exception {
        Work work = work("{\"video\":{\"play_addr\":{\"url_list\":[\"https://v.douyinvod.com/video.mp4\"]}}}");
        List<String> requests = new ArrayList<>();
        Set<String> before = videos();
        List<ChatImage> frames = AutomationReplyMedia.comment(context, event(new Comment(), work), options(false, true),
                transport(requests, new byte[]{1, 2, 3}, () -> registerCachedVideo(4)));
        assertTrue(frames.isEmpty());
        assertTrue("recognizing reply videos must not fetch the work's video", requests.isEmpty());
        assertEquals(before, videos());
        assertEquals("", AutomationReplyMedia.commentSourceLabel(event(new Comment(), work), options(false, true)));
    }

    @Test public void bothSwitchesIgnoreOriginalWorkImagesAndVideoForTextComment() throws Exception {
        Work work = work("""
                {"images":[{"url_list":["https://p.byteimg.com/work.png"]}],
                 "video":{"cover":{"url_list":["https://p.byteimg.com/cover.png"]},
                  "play_addr":{"url_list":["https://v.douyinvod.com/video.mp4"]}}}
                """);
        List<String> requests = new ArrayList<>();
        MediaReadCancellation cancellation = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            requests.add(uri.toString());
            return new FixtureConnection(uri, uri.getPath().endsWith(".png") ? png(Color.MAGENTA) : new byte[]{1},
                    uri.getPath().endsWith(".mp4") ? () -> registerCachedVideo(3) : null);
        });
        List<ChatImage> images = AutomationReplyMedia.comment(context, event(new Comment(), work), options(true, true), cancellation);
        assertTrue(images.isEmpty());
        assertTrue("neither enabled switch permits using original-work media", requests.isEmpty());
        assertEquals("", AutomationReplyMedia.commentSourceLabel(event(new Comment(), work), options(true, true)));
    }

    @Test public void sparseWorkVideoDoesNotBlockTextCommentWhenBothSwitchesAreOn() throws Exception {
        Work work = work("{\"video\":{}}");
        List<String> requests = new ArrayList<>();
        assertTrue(AutomationReplyMedia.comment(context, event(new Comment(), work), options(true, true),
                transport(requests, new byte[0], null)).isEmpty());
        assertEquals("", AutomationReplyMedia.commentSourceLabel(event(new Comment(), work), options(true, true)));
        assertTrue(requests.isEmpty());
    }

    @Test public void disabledIncomingImagesStayDisabledWhenVideoAndOriginalWorkMediaAreAvailable() throws Exception {
        Comment incoming = comment("{\"image_list\":[{\"origin_url\":{\"url_list\":[\"https://unrelated.test/incoming.png\"]}}]}");
        Work work = work("""
                {"images":[{"url_list":["https://p.byteimg.com/work.png"]}],
                 "video":{"play_addr":{"url_list":["https://v.douyinvod.com/video.mp4"]}}}
                """);
        List<String> requests = new ArrayList<>();
        assertTrue(AutomationReplyMedia.comment(context, event(incoming, work), options(false, true),
                transport(requests, new byte[]{1}, () -> registerCachedVideo(4))).isEmpty());
        assertEquals("", AutomationReplyMedia.commentSourceLabel(event(incoming, work), options(false, true)));
        assertTrue(requests.isEmpty());
    }

    @Test public void commentReplyNeverTraversesOriginalWorkMediaFields() throws Exception {
        Object work = new UnreadWork();
        for (LocalOptions flags : new LocalOptions[]{options(true, false), options(false, true), options(true, true)}) {
            List<String> requests = new ArrayList<>();
            assertTrue(AutomationReplyMedia.comment(context, event(new Comment(), work), flags,
                    transport(requests, new byte[0], null)).isEmpty());
            assertEquals("", AutomationReplyMedia.commentSourceLabel(event(new Comment(), work), flags));
            assertTrue(requests.isEmpty());
        }
    }

    @Test public void explicitIncomingVideoStillFeedsFrameExtractionAndDeletesItsTemporaryFile() throws Exception {
        List<String> requests = new ArrayList<>();
        Set<String> before = videos();
        List<ChatImage> frames = AutomationReplyMedia.loadIncoming(context, Collections.emptyList(),
                "https://v.douyinvod.com/video.mp4", options(false, true),
                transport(requests, new byte[]{1, 2, 3}, () -> registerCachedVideo(4)));
        assertEquals(Collections.singletonList("https://v.douyinvod.com/video.mp4"), requests);
        assertEquals(4, frames.size());
        for (int index = 0; index < frames.size(); index++) assertColor(new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}[index], frames.get(index));
        assertEquals(before, videos());
        assertThrows(UnsupportedOperationException.class, frames::clear);
    }

    @Test public void malformedIncomingImageFailsInsteadOfSubstitutingUnrelatedWorkMedia() throws Exception {
        Comment unsupported = comment("{\"image_list\":[{\"origin_url\":{\"url_list\":[\"https://unrelated.test/comment.png\"]}}]}");
        Work work = work("{\"images\":[{\"url_list\":[\"https://p.byteimg.com/work.png\"]}]}");
        List<String> requests = new ArrayList<>();
        assertTrue(AutomationReplyMedia.hasCommentImages(unsupported));
        assertThrows(IllegalArgumentException.class, () -> AutomationReplyMedia.comment(context,
                event(unsupported, work), options(true, false), transport(requests, png(Color.RED), null)));
        assertTrue(requests.isEmpty());
    }

    @Test public void encryptedImVideoSchemaIsRecognizedButNeverInventsAPlayableUrlOrUsesItsPoster() throws Exception {
        JSONObject content = new JSONObject().put("video", new JSONObject().put("vid", "v123")
                .put("tkey", "tos-key").put("skey", "secret").put("is_new_encrypt", 1))
                .put("poster", new JSONObject().put("url_list", new JSONArray().put("https://p.byteimg.com/poster.jpg")))
                .put("video_url", "https://v.douyinvod.com/guessed.mp4");
        assertTrue(AutomationReplyMedia.isVideoMessageType(30));
        assertFalse(AutomationReplyMedia.isVideoMessageType(8));
        assertFalse(AutomationReplyMedia.isVideoMessageType(27));
        assertNull(AutomationReplyMedia.incomingVideo(content));
        assertTrue(AutomationReplyMedia.incomingImages(content).isEmpty());
        JSONObject image = new JSONObject().put("resource_url", new JSONObject()
                .put("url_list", new JSONArray().put("https://p.byteimg.com/incoming.jpg")));
        assertEquals(List.of("https://p.byteimg.com/incoming.jpg"), AutomationReplyMedia.incomingImages(image));
    }

    @Test public void videoTransferCancellationDisconnectsAndDeletesThePartialFile() throws Exception {
        BlockingMediaConnection connection = new BlockingMediaConnection(false);
        MediaReadCancellation cancellation = new MediaReadCancellation(60_000, System::nanoTime, uri -> connection);
        Set<String> before = videos();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> {
                try {
                    AutomationReplyMedia.loadIncoming(context, Collections.emptyList(), "https://v.douyinvod.com/video.mp4",
                            options(false, true), cancellation);
                    fail("Cancelled video unexpectedly loaded");
                } catch (CancellationException expected) { }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertTrue(connection.blocked.await(2, TimeUnit.SECONDS));
            assertEquals(before.size() + 1, videos().size());
            cancellation.cancel();
            pending.get(2, TimeUnit.SECONDS);
            assertTrue(connection.disconnected);
            assertEquals(before, videos());
        } finally { cancellation.cancel(); worker.shutdownNow(); }
    }

    @Test public void badVideoDecoderAndUntrustedRedirectBothLeaveNoCacheFiles() throws Exception {
        Set<String> before = videos();
        List<String> requests = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> AutomationReplyMedia.loadIncoming(context,
                Collections.emptyList(), "https://v.douyinvod.com/broken.mp4", options(false, true),
                transport(requests, new byte[]{1, 2, 3}, null)));
        assertEquals(before, videos());
        MediaReadCancellation redirect = new MediaReadCancellation(60_000, System::nanoTime, uri -> new FixtureConnection(uri, new byte[0], null) {
            @Override public int getResponseCode() { return 302; }
            @Override public String getHeaderField(String key) { return "Location".equals(key) ? "https://unrelated.test/private" : null; }
        });
        assertThrows(IllegalArgumentException.class, () -> AutomationReplyMedia.loadIncoming(context,
                Collections.emptyList(), "https://v.douyinvod.com/redirect.mp4", options(false, true), redirect));
        assertEquals(before, videos());
    }

    private MediaReadCancellation transport(List<String> requests, byte[] bytes, Runnable beforeBody) {
        return new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            requests.add(uri.toString());
            return new FixtureConnection(uri, bytes, beforeBody);
        });
    }

    private void registerCachedVideo(int count) {
        File[] files = context.getCacheDir().listFiles((directory, name) -> name.startsWith("douai-comment-video-"));
        assertNotNull(files);
        assertTrue(files.length > 0);
        for (File file : files) {
            DataSource source = DataSource.toDataSource(file.getAbsolutePath());
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes");
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "8000");
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "16");
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "8");
            long[] times = ChatMediaLoader.sampleTimes(8000, count);
            for (int index = 0; index < times.length; index++) {
                Bitmap frame = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888);
                frame.eraseColor(new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}[index]);
                ShadowMediaMetadataRetriever.addScaledFrame(source, times[index], 16, 8, frame);
            }
        }
    }

    private Set<String> videos() {
        Set<String> names = new HashSet<>();
        File[] files = context.getCacheDir().listFiles((directory, name) -> name.startsWith("douai-comment-video-"));
        if (files != null) for (File file : files) names.add(file.getName());
        return names;
    }
    private static byte[] png(int color) {
        Bitmap bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return bytes.toByteArray();
    }
    private static void assertColor(int expected, ChatImage image) {
        byte[] bytes = Base64.getDecoder().decode(image.base64Data);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(decoded);
        int actual = decoded.getPixel(8, 4);
        assertTrue(Math.abs(Color.red(expected) - Color.red(actual)) < 15);
        assertTrue(Math.abs(Color.green(expected) - Color.green(actual)) < 15);
        assertTrue(Math.abs(Color.blue(expected) - Color.blue(actual)) < 15);
        decoded.recycle();
    }
    private static LocalOptions options(boolean images, boolean video) {
        return new LocalOptions(Map.of(AutomationReplyMedia.RECOGNIZE_IMAGES, images, AutomationReplyMedia.RECOGNIZE_VIDEO, video));
    }
    private static CommentReplyEvent event(Object comment, Object work) {
        return new CommentReplyEvent("notice", "work", "incoming", "root", "200", "对方", "来评", "原评", 1_780_000_000L, work, comment);
    }
    private static Comment comment(String json) { return new Gson().fromJson(json, Comment.class); }
    private static Work work(String json) { return new Gson().fromJson(json, Work.class); }
    static final class Comment { @SerializedName("image_list") List<Picture> attachments; }
    static final class Picture {
        @SerializedName("origin_url") Address original;
        @SerializedName("download_url") Address download;
        @SerializedName("medium_url") Address medium;
        @SerializedName("crop_url") Address crop;
        @SerializedName("thumb_url") Address thumb;
    }
    static final class Work { @SerializedName("images") List<Address> pictures; @SerializedName("video") Video media; }
    static final class Video { @SerializedName("cover") Address cover; @SerializedName("play_addr") Address playback; }
    static final class Address { @SerializedName("url_list") List<String> values; }
    static final class UnreadWork {
        @SerializedName("images") final Iterable<Object> pictures = () -> {
            throw new AssertionError("Original work images were traversed");
        };
        @SerializedName("video") final UnreadVideo video = new UnreadVideo();
    }
    static final class UnreadVideo {
        @SerializedName("play_addr") final UnreadAddress playback = new UnreadAddress();
    }
    static final class UnreadAddress {
        @SerializedName("url_list") final Iterable<String> values = () -> {
            throw new AssertionError("Original work video was traversed");
        };
    }
    static class FixtureConnection extends HttpURLConnection {
        final byte[] bytes;
        final Runnable beforeBody;
        FixtureConnection(URI uri, byte[] bytes, Runnable beforeBody) throws Exception {
            super(new URL(uri.toString())); this.bytes = bytes; this.beforeBody = beforeBody;
        }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public InputStream getInputStream() { if (beforeBody != null) beforeBody.run(); return new ByteArrayInputStream(bytes); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
