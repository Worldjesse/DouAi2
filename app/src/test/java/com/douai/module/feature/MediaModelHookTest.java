package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public final class MediaModelHookTest {
    public static final class Control {
        public Boolean allowDownload = false;
        public Boolean downloadIgnoreVisibility = false;
        public int preventDownloadType = 3;
    }
    public static final class Item {
        public Control videoControl = new Control();
        public Video video;
    }
    public static final class Video {
        public Url play_addr = new Url();
    }
    public static final class Url {
        public List<String> url_list = Arrays.asList("https://media.example/video.mp4");
    }

    @Test public void onlyExistingMediaEnablesLocalFlagsAndDisableRestoresThem() throws Exception {
        MediaModelHook hook = new MediaModelHook(CommentEnhancementsHookTest.options("remove_download_limits", true), ignored -> { });
        Item missing = new Item();
        hook.apply(missing);
        assertFalse(missing.videoControl.allowDownload);
        Item readable = new Item();
        readable.video = new Video();
        hook.apply(readable);
        assertTrue(readable.videoControl.allowDownload);
        assertTrue(readable.videoControl.downloadIgnoreVisibility);
        assertEquals(0, readable.videoControl.preventDownloadType);
        hook.setOptions(new LocalOptions(Collections.emptyMap()));
        assertFalse(readable.videoControl.allowDownload);
        assertFalse(readable.videoControl.downloadIgnoreVisibility);
        assertEquals(3, readable.videoControl.preventDownloadType);
        hook.close();
    }

    @Test public void restoringDoesNotOverwriteLaterHostUpdates() throws Exception {
        MediaModelHook hook = new MediaModelHook(CommentEnhancementsHookTest.options("remove_download_limits", true), ignored -> { });
        Item readable = new Item();
        readable.video = new Video();
        hook.apply(readable);
        readable.videoControl.preventDownloadType = 9;
        hook.close();
        assertEquals(9, readable.videoControl.preventDownloadType);
        assertFalse(readable.videoControl.allowDownload);
    }
}
