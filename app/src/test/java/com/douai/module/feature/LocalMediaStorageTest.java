package com.douai.module.feature;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class LocalMediaStorageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final DownloadTransfer.Connections previousConnections = MediaStoreDownloader.connections;

    @After public void restoreConnections() {
        MediaStoreDownloader.connections = previousConnections;
    }

    @Test public void localMotionPhotoUsesSelectedTreeAndPreservesEveryEncodedByte() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        String existing = provider.createDocument("root", "image/jpeg", "same.jpg");
        Files.write(provider.files.get(existing).toPath(), new byte[]{42});
        byte[] encoded = imageBytes(70_000);
        File source = source(encoded);
        DownloadTransfer transfer = transfer();

        assertEquals("媒体已保存到所选目录", MediaStoreDownloader.saveLocalImage(
                RuntimeEnvironment.getApplication(), source, "../album\\motion.jpg", "image/jpeg",
                DownloadDocumentTreeTest.TREE, transfer));

        assertEquals(2, provider.files.size());
        String saved = provider.files.keySet().stream().filter(id -> !id.equals(existing)).findFirst().orElseThrow();
        assertEquals("root", provider.parents.get(saved));
        assertEquals("album_motion.jpg", provider.names.get(saved));
        assertEquals("image/jpeg", provider.types.get(saved));
        assertArrayEquals(encoded, Files.readAllBytes(provider.files.get(saved).toPath()));
        assertArrayEquals(encoded, Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(provider.files.get(existing).toPath()));
        assertEquals(encoded.length, transfer.bytes);
        assertEquals(encoded.length, transfer.totalBytes);
        assertEquals(1, transfer.completedFiles);
        assertFalse(transfer.hasResources());
        assertFalse("Cancellation after the final image was committed must keep the saved file", transfer.cancel());
    }

    @Test public void localCopyCancellationDeletesOnlyItsPartialDocument() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        String existing = provider.createDocument("root", "image/jpeg", "existing.jpg");
        Files.write(provider.files.get(existing).toPath(), new byte[]{42});
        File source = source(imageBytes(100_000));
        DownloadTransfer transfer = transfer();
        AtomicReference<File> partial = new AtomicReference<>();
        transfer.changed = () -> {
            if (transfer.bytes > 0 && partial.get() == null) {
                partial.set(provider.files.entrySet().stream().filter(entry -> !entry.getKey().equals(existing))
                        .map(Map.Entry::getValue).findFirst().orElseThrow());
                transfer.cancel();
            }
        };

        assertThrows(CancellationException.class, () -> MediaStoreDownloader.saveLocalImage(
                RuntimeEnvironment.getApplication(), source, "motion.jpg", "image/jpeg",
                DownloadDocumentTreeTest.TREE, transfer));

        assertTrue(transfer.bytes > 0 && transfer.bytes < source.length());
        assertNotNull(partial.get());
        assertFalse(partial.get().exists());
        assertEquals(java.util.Collections.singleton(existing), provider.files.keySet());
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(provider.files.get(existing).toPath()));
        assertTrue(source.exists());
        assertFalse(transfer.hasResources());
        assertEquals(0, transfer.completedFiles);
    }

    @Test public void existingTemporaryFileIsNeitherOpenedNorRemoved() throws Exception {
        File target = source(new byte[]{11, 22, 33});
        MediaStoreDownloader.connections = uri -> { throw new AssertionError("Existing files must reject before I/O"); };

        assertThrows(FileAlreadyExistsException.class, () -> MediaStoreDownloader.downloadTemporary(
                "https://media.test/image.jpg", target, transfer()));

        assertArrayEquals(new byte[]{11, 22, 33}, Files.readAllBytes(target.toPath()));
    }

    @Test public void temporaryDownloadTracksBytesAndLeavesTaskCompletionToCaller() throws Exception {
        byte[] encoded = imageBytes(80_000);
        MediaConnection response = new MediaConnection(encoded, encoded.length);
        MediaStoreDownloader.connections = uri -> response;
        File target = new File(temporary.getRoot(), "image.jpg");
        DownloadTransfer transfer = transfer();

        MediaStoreDownloader.downloadTemporary("https://media.test/image.jpg", target, transfer);

        assertArrayEquals(encoded, Files.readAllBytes(target.toPath()));
        assertEquals(encoded.length, transfer.bytes);
        assertEquals(encoded.length, transfer.totalBytes);
        assertEquals(0, transfer.completedFiles);
        assertTrue(response.disconnected);
        assertFalse(transfer.hasResources());
        assertTrue(transfer.cancel());
        assertTrue(target.exists());
    }

    @Test public void cancelledOrTruncatedTemporaryDownloadRemovesItsIntermediate() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            byte[] encoded = imageBytes(80_000);
            MediaConnection response = new MediaConnection(encoded, cancel ? encoded.length : encoded.length + 1);
            MediaStoreDownloader.connections = uri -> response;
            File target = new File(temporary.getRoot(), cancel ? "cancelled.jpg" : "truncated.jpg");
            DownloadTransfer transfer = transfer();
            if (cancel) transfer.changed = () -> { if (transfer.bytes > 0) transfer.cancel(); };

            if (cancel) assertThrows(CancellationException.class, () -> MediaStoreDownloader.downloadTemporary(
                    "https://media.test/image.jpg", target, transfer));
            else assertThrows(IllegalStateException.class, () -> MediaStoreDownloader.downloadTemporary(
                    "https://media.test/image.jpg", target, transfer));

            assertFalse(target.exists());
            assertTrue(response.disconnected);
            assertFalse(transfer.hasResources());
        }
    }

    @Test public void temporaryDownloadRejectsOversizedResponsesBeforeReadingBody() throws Exception {
        MediaConnection response = new MediaConnection(new byte[]{1}, 512L * 1024L * 1024L + 1);
        MediaStoreDownloader.connections = uri -> response;
        File target = new File(temporary.getRoot(), "oversized.jpg");

        assertThrows(IllegalStateException.class, () -> MediaStoreDownloader.downloadTemporary(
                "https://media.test/image.jpg", target, transfer()));

        assertFalse(target.exists());
        assertFalse(response.bodyOpened);
        assertTrue(response.disconnected);
    }

    @Test @Config(sdk = 35) public void mediaStorePublishesOnlyAfterCopyAndDeletesFailedPublication() throws Exception {
        for (boolean failPublish : new boolean[]{false, true}) {
            ImageProvider provider = registerImages(failPublish);
            byte[] encoded = imageBytes(80_000);
            File source = source(encoded);
            DownloadTransfer transfer = transfer();
            if (!failPublish) provider.beforePublish = () -> assertFalse(
                    "A closed final output must be committed against cancellation before publication", transfer.cancel());
            if (failPublish) {
                assertThrows(IllegalStateException.class, () -> MediaStoreDownloader.saveLocalImage(
                        RuntimeEnvironment.getApplication(), source, "motion.jpg", "image/jpeg", "", transfer));
                assertTrue(provider.files.isEmpty());
                assertTrue(provider.deleted);
                assertEquals(0, transfer.completedFiles);
            } else {
                assertEquals("媒体已保存到 Pictures/DouAi", MediaStoreDownloader.saveLocalImage(
                        RuntimeEnvironment.getApplication(), source, "motion.jpg", "image/jpeg", "", transfer));
                assertEquals(1, provider.files.size());
                assertArrayEquals(encoded, Files.readAllBytes(provider.files.values().iterator().next().toPath()));
                assertTrue(provider.published);
                assertEquals(1, transfer.completedFiles);
                assertFalse(transfer.cancel());
            }
            assertArrayEquals(encoded, Files.readAllBytes(source.toPath()));
            assertFalse(transfer.hasResources());
        }
    }

    @Test public void legacyLocalImageCopyDoesNotOverwriteSameNamedPublicImage() throws Exception {
        File parent = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DouAi");
        assertTrue(parent.isDirectory() || parent.mkdirs());
        File existing = File.createTempFile("motion-", ".jpg", parent);
        Files.write(existing.toPath(), new byte[]{42});
        String stem = existing.getName().substring(0, existing.getName().length() - 4);
        File copied = new File(parent, stem + "_1.jpg");
        byte[] encoded = imageBytes(80_000);
        File source = source(encoded);
        try {
            DownloadTransfer transfer = transfer();
            MediaStoreDownloader.saveLocalImage(RuntimeEnvironment.getApplication(), source,
                    existing.getName(), "image/jpeg", "", transfer);
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
            assertArrayEquals(encoded, Files.readAllBytes(copied.toPath()));
            assertArrayEquals(encoded, Files.readAllBytes(source.toPath()));
            assertFalse(transfer.cancel());
        } finally {
            existing.delete();
            copied.delete();
        }
    }

    private File source(byte[] encoded) throws Exception {
        File source = temporary.newFile();
        Files.write(source.toPath(), encoded);
        return source;
    }

    private static byte[] imageBytes(int count) {
        byte[] bytes = new byte[count];
        for (int index = 0; index < count; index++) bytes[index] = (byte) (index * 31);
        byte[] header = "JPEG-XMP-MotionPhoto-EncodedImage-AppendedMp4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static DownloadTransfer transfer() throws Exception {
        DownloadTransfer transfer = new DownloadTransfer();
        transfer.start();
        transfer.file("motion.jpg", 3);
        return transfer;
    }

    private static final class MediaConnection extends HttpURLConnection {
        final byte[] bytes;
        final long advertised;
        boolean disconnected;
        boolean bodyOpened;
        MediaConnection(byte[] bytes, long advertised) throws Exception {
            super(new URL("https://media.test/image.jpg"));
            this.bytes = bytes;
            this.advertised = advertised;
        }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return advertised; }
        @Override public String getContentType() { return "image/jpeg"; }
        @Override public InputStream getInputStream() { bodyOpened = true; return new ByteArrayInputStream(bytes); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    private static ImageProvider registerImages(boolean failPublish) {
        ImageProvider provider = new ImageProvider(failPublish);
        ProviderInfo info = new ProviderInfo();
        info.authority = MediaStore.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        return provider;
    }

    public static final class ImageProvider extends ContentProvider {
        final Map<Uri, File> files = new LinkedHashMap<>();
        final boolean failPublish;
        boolean published;
        boolean deleted;
        Runnable beforePublish = () -> { };
        ImageProvider(boolean failPublish) { this.failPublish = failPublish; }
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri collection, ContentValues values) {
            assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, collection);
            assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals("Pictures/DouAi", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            Uri target = ContentUris.withAppendedId(collection, 1);
            try { files.put(target, File.createTempFile("local-image-", ".jpg", RuntimeEnvironment.getApplication().getCacheDir())); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return target;
        }
        @Override public ParcelFileDescriptor openFile(Uri target, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(files.get(target), ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public int update(Uri target, ContentValues values, String selection, String[] args) {
            assertTrue(files.get(target).length() > 0);
            assertEquals(Integer.valueOf(0), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            beforePublish.run();
            if (failPublish) return 0;
            published = true;
            return 1;
        }
        @Override public int delete(Uri target, String selection, String[] args) {
            File file = files.remove(target);
            if (file != null) file.delete();
            deleted = true;
            return 1;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return "image/jpeg"; }
    }
}
