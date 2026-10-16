package com.douai.module.feature;

import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class DownloadDocumentTreeTest {
    @org.junit.Before public void requireDrainedDownloadTasks() { DownloadTaskTestIdle.beforeTest(); }

    @org.junit.After public void finishDownloadWorkers() { DownloadTaskTestIdle.afterTest(); }

    public static final String TREE = "content://douai.documents/tree/root";

    @Test public void thirdPartyMediaAndTextUseOneChildOfTheSelectedTree() throws Exception {
        RecordingDocuments provider = register();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("video-content"));
            server.start();
            ThirdPartyDownloadPlan plan = plan(server.url("/video.mp4").toString());
            MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan, TREE);
            assertEquals(3, provider.names.size());
            String folder = provider.names.keySet().stream().filter(id -> provider.types.get(id)
                    .equals(DocumentsContract.Document.MIME_TYPE_DIR)).findFirst().orElseThrow();
            assertEquals("root", provider.parents.get(folder));
            for (String id : provider.files.keySet()) assertEquals(folder, provider.parents.get(id));
            assertEquals(2, provider.files.size());
            String metadata = provider.files.keySet().stream().filter(id -> provider.names.get(id).endsWith(".txt"))
                    .findFirst().orElseThrow();
            assertEquals("work.txt", provider.names.get(metadata));
            assertTrue(Files.readString(provider.files.get(metadata).toPath()).contains("caption"));
        }
    }

    @Test public void officialMediaWritesDirectlyToSelectedTreeWithoutLegacyStoragePermission() throws Exception {
        RecordingDocuments provider = register();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("official-content"));
            server.start();
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), server.url("/clip.mp4").toString(),
                    "official.mp4", false, TREE, null));
            awaitToast("媒体已保存到所选目录");
            assertEquals(1, provider.names.size());
            String id = provider.names.keySet().iterator().next();
            assertEquals("root", provider.parents.get(id));
            assertEquals("official.mp4", provider.names.get(id));
            assertEquals("official-content", Files.readString(provider.files.get(id).toPath()));
            android.app.DownloadManager manager = (android.app.DownloadManager)
                    RuntimeEnvironment.getApplication().getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            org.robolectric.shadows.ShadowDownloadManager shadow = org.robolectric.shadow.api.Shadow.extract(manager);
            assertEquals(0, shadow.getRequestCount());
        }
    }

    @Test public void failedBundleRemovesItsDirectoryAndDoesNotUseTheDefaultDestination() throws Exception {
        RecordingDocuments provider = register();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403));
            server.start();
            try {
                MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(),
                        plan(server.url("/clip.mp4").toString()), TREE);
                fail("HTTP failure must not use a different save location");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("403")); }
            assertTrue(provider.names.isEmpty());
            assertTrue(provider.files.isEmpty());
        }
    }

    private static ThirdPartyDownloadPlan plan(String url) {
        ThirdPartyResponse response = ThirdPartyResponse.parse("{\"data\":{\"id\":\"work\",\"text\":\"caption\",\"video\":[\""
                + url + "\"]}}");
        return ThirdPartyDownloadPlan.create(response,
                new ThirdPartyDownloadSettings(new LocalOptions(Collections.emptyMap())), 100);
    }

    public static RecordingDocuments register() {
        RecordingDocuments provider = new RecordingDocuments();
        ProviderInfo info = new ProviderInfo();
        info.authority = "douai.documents";
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        return provider;
    }

    private static void awaitToast(String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            ShadowLooper.idleMainLooper();
            if (expected.equals(ShadowToast.getTextOfLatestToast())) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
        assertEquals(expected, ShadowToast.getTextOfLatestToast());
    }

    public static final class RecordingDocuments extends DocumentsProvider {
        final Map<String, String> names = new ConcurrentHashMap<>();
        final Map<String, String> types = new ConcurrentHashMap<>();
        final Map<String, String> parents = new ConcurrentHashMap<>();
        final Map<String, File> files = new ConcurrentHashMap<>();
        int sequence;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});
        }
        @Override public Cursor queryDocument(String id, String[] projection) {
            MatrixCursor cursor = new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS});
            cursor.addRow(new Object[]{id, names.getOrDefault(id, "root"),
                    types.getOrDefault(id, DocumentsContract.Document.MIME_TYPE_DIR),
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE | DocumentsContract.Document.FLAG_SUPPORTS_WRITE});
            return cursor;
        }
        @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) {
            return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID});
        }
        @Override public boolean isChildDocument(String parent, String child) {
            return "root".equals(parent) || parent.equals(parents.get(child));
        }
        @Override public synchronized String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            String id = "file-" + (++sequence);
            names.put(id, name);
            types.put(id, mime);
            parents.put(id, parent);
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                try { files.put(id, File.createTempFile("document-", ".tmp", RuntimeEnvironment.getApplication().getCacheDir())); }
                catch (Exception failure) { throw new FileNotFoundException(); }
            }
            return id;
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancel) throws FileNotFoundException {
            return ParcelFileDescriptor.open(files.get(id), ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public void deleteDocument(String id) {
            names.remove(id);
            types.remove(id);
            parents.remove(id);
            File file = files.remove(id);
            if (file != null) file.delete();
        }
    }
}
