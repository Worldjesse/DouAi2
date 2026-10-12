package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutomationCommentMediaTest {
    @Test public void galleryUsesCapturedWorkImagesInOrderAndNeverAuthorAvatarOrDownloadVideo() {
        Work work = work("""
                {
                  "author": {"avatar_thumb": {"url_list": ["https://p.byteimg.com/author.jpg"]}},
                  "images": [
                    {"url_list": ["", "file:///private/image", "https://p.douyinpic.com/first.jpg"],
                     "download_url_list": ["https://p.douyinpic.com/watermarked.jpg"]},
                    {"download_url_list": ["https://p.byteimg.com/second.jpg"]}
                  ],
                  "video": {
                    "cover": {"url_list": ["https://p.byteimg.com/cover.jpg"]},
                    "play_addr": {"url_list": ["https://v.douyinvod.com/current.mp4"]},
                    "download_addr": {"url_list": ["https://v.douyinvod.com/download.mp4"]}
                  }
                }
                """);
        List<String> sources = AutomationCommentMedia.imageUrls(work);
        assertEquals(List.of("https://p.douyinpic.com/first.jpg", "https://p.byteimg.com/second.jpg"), sources);
        assertEquals("https://v.douyinvod.com/current.mp4", AutomationCommentMedia.videoUrl(work));
        assertThrows(UnsupportedOperationException.class, () -> sources.clear());
    }

    @Test public void galleryLimitsVisualInputsBeforeInspectingUnselectedLaterItems() {
        Work work = work("""
                {"images": [
                  {"url_list": ["https://p.byteimg.com/1.jpg"]},
                  {"url_list": ["https://p.byteimg.com/2.jpg"]},
                  {"url_list": ["https://p.byteimg.com/3.jpg"]},
                  {"url_list": ["https://p.byteimg.com/4.jpg"]},
                  {"url_list": ["https://unrelated.test/not-selected.jpg"]}
                ]}
                """);
        assertEquals(List.of("https://p.byteimg.com/1.jpg", "https://p.byteimg.com/2.jpg",
                "https://p.byteimg.com/3.jpg", "https://p.byteimg.com/4.jpg"), AutomationCommentMedia.imageUrls(work));
        assertNull("an image post has no video to fetch", AutomationCommentMedia.videoUrl(work));
    }

    @Test public void videoCoverFallbackUsesFirstAvailableCoverAndNoProfileMedia() {
        Work preferred = work("""
                {"video": {
                  "cover": {"url_list": ["https://p.byteimg.com/preferred.jpg"]},
                  "origin_cover": {"url_list": ["https://p.byteimg.com/original.jpg"]},
                  "dynamic_cover": {"url_list": ["https://p.byteimg.com/animated.webp"]}
                }}
                """);
        assertEquals(List.of("https://p.byteimg.com/preferred.jpg"), AutomationCommentMedia.imageUrls(preferred));
        preferred.obfuscatedVideo.cover = null;
        assertEquals(List.of("https://p.byteimg.com/original.jpg"), AutomationCommentMedia.imageUrls(preferred));
        preferred.obfuscatedVideo.originCover = null;
        assertEquals(List.of("https://p.byteimg.com/animated.webp"), AutomationCommentMedia.imageUrls(preferred));
        preferred.obfuscatedVideo.dynamicCover = null;
        assertTrue(AutomationCommentMedia.imageUrls(preferred).isEmpty());
        Work authorOnly = work("""
                {"author": {"avatar_thumb": {"url_list": ["https://p.byteimg.com/avatar.jpg"]}}}
                """);
        assertTrue(AutomationCommentMedia.imageUrls(authorOnly).isEmpty());
    }

    @Test public void playbackFallbackUsesRecognizedPlayAddressesWithoutUsingDownloadAddress() {
        Work work = work("""
                {"video": {
                  "play_addr_h264": {"url_list": ["https://v.douyinvod.com/h264.mp4"]},
                  "play_addr_265": {"url_list": ["https://v.iesdouyin.com/h265.mp4"]},
                  "download_addr": {"url_list": ["https://v.douyinvod.com/watermark.mp4"]}
                }}
                """);
        assertEquals("https://v.douyinvod.com/h264.mp4", AutomationCommentMedia.videoUrl(work));
        work.obfuscatedVideo.h264 = null;
        assertEquals("https://v.iesdouyin.com/h265.mp4", AutomationCommentMedia.videoUrl(work));
        work.obfuscatedVideo.h265 = null;
        assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.videoUrl(work));
    }

    @Test public void imagesAndVideoSwitchesSkipTheirOwnDisabledSourceInspection() throws Exception {
        LocalOptions neither = options(false, false);
        Work invalidBoth = work("""
                {"images": [{"url_list": ["https://unrelated.test/image.jpg"]}],
                 "video": {"play_addr": {"url_list": ["https://unrelated.test/video.mp4"]}}}
                """);
        assertTrue(AutomationCommentMedia.load(RuntimeEnvironment.getApplication(), invalidBoth, neither).isEmpty());

        Work invalidGalleryWithoutVideo = work("""
                {"images": [{"url_list": ["https://unrelated.test/image.jpg"]}]}
                """);
        assertTrue("video-only recognition must not inspect gallery images",
                AutomationCommentMedia.load(RuntimeEnvironment.getApplication(), invalidGalleryWithoutVideo,
                        options(false, true)).isEmpty());

        Work invalidPlaybackWithoutCover = work("""
                {"video": {"play_addr": {"url_list": ["https://unrelated.test/video.mp4"]}}}
                """);
        assertThrows("image-only recognition validates playback before extracting a fallback frame",
                IllegalArgumentException.class, () -> AutomationCommentMedia.load(RuntimeEnvironment.getApplication(),
                        invalidPlaybackWithoutCover, options(true, false)));
        assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.load(
                RuntimeEnvironment.getApplication(), invalidPlaybackWithoutCover, options(false, true)));
        assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.load(
                RuntimeEnvironment.getApplication(), invalidGalleryWithoutVideo, options(true, false)));
    }

    @Test public void defaultRecognitionValidatesPlaybackBeforeExtractingMissingCoverFrame() throws Exception {
        Work invalidPlaybackWithoutCover = work("""
                {"video": {"play_addr": {"url_list": ["https://unrelated.test/video.mp4"]}}}
                """);
        assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.load(RuntimeEnvironment.getApplication(),
                invalidPlaybackWithoutCover, new LocalOptions(Collections.emptyMap())));
        Work invalidGallery = work("""
                {"images": [{"url_list": ["https://unrelated.test/image.jpg"]}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.load(
                RuntimeEnvironment.getApplication(), invalidGallery, new LocalOptions(Collections.emptyMap())));
    }

    @Test public void invalidGalleryOrCoverIsRejectedInsteadOfQuietlyDroppingVisualContext() {
        for (String image : new String[]{"https://unrelated.test/image.jpg", "http://p.byteimg.com/image.jpg",
                "https://p.byteimg.com.unrelated.test/image.jpg", "https://name:secret@p.byteimg.com/image.jpg"}) {
            Work gallery = work("{\"images\":[{\"url_list\":[\"" + image + "\"]}]}");
            assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.imageUrls(gallery));
            Work cover = work("{\"video\":{\"cover\":{\"url_list\":[\"" + image + "\"]}}}");
            assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.imageUrls(cover));
        }
        assertThrows(IllegalArgumentException.class, () -> AutomationCommentMedia.imageUrls(work("{\"images\":[{}]}")));
    }

    @Test public void recognizedVideoDomainsRequireHttpsNoCredentialsAndStandardPort() {
        for (String url : new String[]{"https://v.douyinvod.com/work.mp4", "https://iesdouyin.com/work.mp4",
                "https://V.DOUYINVOD.COM/work.mp4",
                "https://v.douyinvod.com:443/work.mp4", "https://video.douyincdn.com/work.mp4"}) {
            assertTrue(url, AutomationCommentMedia.isVideoUrl(url));
        }
        for (String url : new String[]{"http://v.douyinvod.com/work.mp4", "https://v.douyinvod.com:444/work.mp4",
                "https://v.douyinvod.com.unrelated.test/work.mp4", "https://fakeiesdouyin.com/work.mp4",
                "https://name:secret@v.douyinvod.com/work.mp4", "file:///private/work.mp4", "invalid"}) {
            assertFalse(url, AutomationCommentMedia.isVideoUrl(url));
        }
        assertFalse(AutomationCommentMedia.isVideoUrl(null));
    }

    @Test public void cancelledCommentReadStopsBeforeSourceLookupEvenWithRecognitionDisabled() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> AutomationCommentMedia.load(
                    RuntimeEnvironment.getApplication(), new Object(), options(false, false)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static LocalOptions options(boolean images, boolean video) {
        return new LocalOptions(Map.of(AutomationCommentMedia.RECOGNIZE_IMAGES, images,
                AutomationCommentMedia.RECOGNIZE_VIDEO, video));
    }

    private static Work work(String json) { return new Gson().fromJson(json, Work.class); }

    // The serialized field names match host models while Java names deliberately do not.
    static final class Work {
        @SerializedName("images") List<GalleryImage> obfuscatedImages;
        @SerializedName("video") Video obfuscatedVideo;
        @SerializedName("author") Author obfuscatedAuthor;
    }
    static final class GalleryImage {
        @SerializedName("url_list") List<String> original;
        @SerializedName("download_url_list") List<String> download;
    }
    static final class Video {
        @SerializedName("cover") Address cover;
        @SerializedName("origin_cover") Address originCover;
        @SerializedName("dynamic_cover") Address dynamicCover;
        @SerializedName("play_addr") Address playback;
        @SerializedName("play_addr_h264") Address h264;
        @SerializedName("play_addr_265") Address h265;
        @SerializedName("download_addr") Address download;
    }
    static final class Address {
        @SerializedName("url_list") List<String> urls;
    }
    static final class Author {
        @SerializedName("avatar_thumb") Address avatar;
    }
}
