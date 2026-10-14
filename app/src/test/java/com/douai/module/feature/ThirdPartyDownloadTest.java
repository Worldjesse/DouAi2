package com.douai.module.feature;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ThirdPartyDownloadTest {
    @Test public void selectedFieldsControlFilesAndOrderedNamesWhileOtherValuesBecomeMetadata() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("{\"data\":{"
                + "\"id\":\"7696151596120622001\",\"text\":\"caption\","
                + "\"author\":{\"nickname\":\"author/name\"},"
                + "\"video\":[\"https://media.test/video.mp4\"],"
                + "\"audio\":[\"https://media.test/song.mp3\"],\"other\":{\"digg_count\":1944}}}");
        ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(response, settings(
                Arrays.asList("data.video"), Arrays.asList("data.id", "data.author.nickname")), 100);
        assertEquals("7696151596120622001_author_name", plan.baseName);
        assertEquals(1, plan.assets.size());
        assertTrue(plan.assets.get(0).name.startsWith(plan.baseName));
        assertTrue(plan.assets.get(0).name.endsWith(".mp4"));
        assertFalse(plan.assets.get(0).name.contains("/"));
        assertEquals("video/mp4", plan.assets.get(0).mimeType);
        assertTrue(plan.metadata.contains("caption"));
        assertTrue(plan.metadata.contains("1944"));
        assertTrue(plan.metadata.contains("https://media.test/song.mp3"));
        assertFalse(plan.metadata.contains("https://media.test/video.mp4"));
    }

    @Test public void longNamesRetainUniqueMediaSuffixesAndMissingFieldsFailClearly() {
        String title = String.join("", java.util.Collections.nCopies(200, "a"));
        ThirdPartyResponse response = ThirdPartyResponse.parse("{\"title\":\"" + title
                + "\",\"images\":[\"https://media.test/a.png\",\"https://media.test/b.png\"]}");
        ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(response,
                settings(Arrays.asList("images"), Arrays.asList("title")), 100);
        assertEquals(2, plan.assets.size());
        assertNotEquals(plan.assets.get(0).name, plan.assets.get(1).name);
        assertTrue(plan.assets.get(1).name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 181);
        try {
            ThirdPartyDownloadPlan.create(response, settings(Arrays.asList("data.video"), Arrays.asList("title")), 100);
            fail("missing configured media must not silently save unrelated URLs");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("字段")); }
    }

    @Test public void mediaAndMetadataPublishTogetherInOneDownloadsDirectory() throws Exception {
        RecordingProvider provider = provider();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("video-bytes"));
            server.enqueue(new MockResponse().addHeader("Content-Type", "image/png").setBody("image-bytes"));
            server.start();
            ThirdPartyResponse response = ThirdPartyResponse.parse("{\"id\":\"work\",\"text\":\"caption\","
                    + "\"video\":[\"" + server.url("/video.mp4") + "\"],\"cover\":\""
                    + server.url("/cover.png") + "\"}");
            ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(response,
                    settings(Arrays.asList("video", "cover"), Arrays.asList("id")), 100);
            MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan);
            assertEquals(3, provider.values.size());
            String path = null;
            for (Map.Entry<Uri, ContentValues> entry : provider.values.entrySet()) {
                ContentValues row = entry.getValue();
                assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
                if (path == null) path = row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                assertEquals(path, row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
                assertTrue(path.startsWith("Download/DouAi/work_"));
                String bytes = Files.readString(provider.files.get(entry.getKey()).toPath());
                if (row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".txt")) {
                    assertEquals("work.txt", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
                    assertTrue(bytes.contains("caption"));
                    assertFalse(bytes.contains(server.url("/video.mp4").toString()));
                } else assertTrue(bytes.endsWith("-bytes"));
            }
            assertEquals(3, provider.published.size());
        }
    }

    @Test public void failedMediaRemovesEarlierPendingFilesAndDoesNotPublishMetadata() throws Exception {
        RecordingProvider provider = provider();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("video-bytes"));
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("{\"error\":true}"));
            server.start();
            ThirdPartyResponse response = ThirdPartyResponse.parse("{\"video\":[\""
                    + server.url("/one.mp4") + "\",\"" + server.url("/two.mp4") + "\"]}");
            ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(response,
                    settings(Arrays.asList("video"), java.util.Collections.emptyList()), 100);
            try {
                MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan);
                fail("JSON returned from a media URL must fail the bundle");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("非媒体")); }
            assertEquals(1, provider.deleted.size());
            assertTrue(provider.values.isEmpty());
            assertTrue(provider.published.isEmpty());
        }
    }

    @Test public void shareUrlUsesHostShareLinkThenStableWorkIdAndRejectsMissingInput() {
        Work work = new Work();
        work.aweme_id = "7696151596120622001";
        assertEquals("https://www.douyin.com/video/7696151596120622001", ThirdPartyDownload.shareUrl(work));
        work.share_url = "https://v.douyin.com/XVo-IAGSo2s/";
        assertEquals(work.share_url, ThirdPartyDownload.shareUrl(work));
        work.share_url = "";
        work.aweme_type = 68;
        assertEquals("https://www.douyin.com/note/7696151596120622001", ThirdPartyDownload.shareUrl(work));
        try { ThirdPartyDownload.shareUrl(new Object()); fail("missing share link"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test @Config(sdk = 28)
    public void androidNineSavesMediaAndTextInOnePublicDirectory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("legacy-video"));
            server.start();
            ThirdPartyResponse response = ThirdPartyResponse.parse("{\"id\":\"legacy\",\"text\":\"caption\",\"video\":\""
                    + server.url("/clip.mp4") + "\"}");
            ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(response,
                    settings(Arrays.asList("video"), Arrays.asList("id")), 100);
            MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan);
            File parent = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "DouAi");
            File[] folders = parent.listFiles(file -> file.getName().startsWith("legacy_"));
            assertNotNull(folders);
            assertEquals(1, folders.length);
            File[] files = folders[0].listFiles();
            assertNotNull(files);
            assertEquals(2, files.length);
            assertEquals("legacy-video", Files.readString(new File(folders[0], plan.assets.get(0).name).toPath()));
            assertTrue(Files.readString(new File(folders[0], "legacy.txt").toPath()).contains("caption"));
        }
    }

    public static final class Work {
        public String aweme_id;
        public String share_url;
        public int aweme_type;
    }

    private static ThirdPartyDownloadSettings settings(List<String> media, List<String> names) {
        Map<String, Object> options = new HashMap<>();
        options.put(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, ThirdPartyDownloadSettings.encodeFields(media));
        options.put(ThirdPartyDownloadSettings.NAMING_FIELDS, ThirdPartyDownloadSettings.encodeFields(names));
        return new ThirdPartyDownloadSettings(new LocalOptions(options));
    }

    private static RecordingProvider provider() {
        RecordingProvider provider = new RecordingProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = MediaStore.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider);
        return provider;
    }

    public static final class RecordingProvider extends ContentProvider {
        final Map<Uri, ContentValues> values = new LinkedHashMap<>();
        final Map<Uri, File> files = new LinkedHashMap<>();
        final List<Uri> published = new ArrayList<>();
        final List<Uri> deleted = new ArrayList<>();
        long nextId;
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues input) {
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, uri);
            assertEquals(Integer.valueOf(1), input.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            Uri result = ContentUris.withAppendedId(uri, ++nextId);
            values.put(result, new ContentValues(input));
            try { files.put(result, File.createTempFile("bundle", ".tmp", RuntimeEnvironment.getApplication().getCacheDir())); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return result;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(files.get(uri), ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public int update(Uri uri, ContentValues input, String selection, String[] args) {
            assertEquals(3, values.size());
            values.get(uri).putAll(input);
            published.add(uri);
            return 1;
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            values.remove(uri);
            deleted.add(uri);
            File file = files.remove(uri);
            if (file != null) file.delete();
            return 1;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
    }
}
