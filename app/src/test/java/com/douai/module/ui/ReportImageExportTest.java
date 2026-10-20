package com.douai.module.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ReportImageExportTest {
    private Context context;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); }

    @Test public void anOversizedLongImageSuggestsPdfBeforeWritingAnyOutput() throws Exception {
        ReportImageExport.Document document = bands(context, 90, 1080);
        assertTrue(document.pdfPageCount() > 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IllegalArgumentException failure = assertThrows(ReportImageExport.LongPngLimitException.class,
                () -> document.write(ReportImageExport.Format.LONG_PNG, output, () -> true));
        assertTrue(failure.getMessage().contains("PDF")); assertEquals(0, output.size());
    }

    @Test public void failedPngStreamCanBeRetriedAndCancellationWritesNothing() throws Exception {
        ReportImageExport.Document document = bands(context, 8, 360);
        assertThrows(IOException.class, () -> document.write(ReportImageExport.Format.LONG_PNG, new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("fixture private stream detail"); }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException { throw new IOException("fixture private stream detail"); }
        }, () -> true));
        for (ReportImageExport.Format format : ReportImageExport.Format.values()) {
            ByteArrayOutputStream cancelled = new ByteArrayOutputStream();
            assertThrows(CancellationException.class, () -> document.write(format, cancelled, () -> false));
            assertEquals(0, cancelled.size());
        }
        ByteArrayOutputStream retry = new ByteArrayOutputStream(); document.write(ReportImageExport.Format.LONG_PNG, retry, () -> true);
        Bitmap decoded = BitmapFactory.decodeByteArray(retry.toByteArray(), 0, retry.size());
        assertNotNull(decoded); assertEquals(Color.MAGENTA, decoded.getPixel(20, 790)); decoded.recycle();
    }

    @Test public void cancelledPendingWriteAndFailedChooserCleanupRunOffMainForEitherFormat() throws Exception {
        for (ReportImageExport.Format format : ReportImageExport.Format.values()) {
            MediaProvider provider = provider(context); AtomicBoolean valid = new AtomicBoolean(true);
            provider.onUpdate = () -> valid.set(false);
            assertThrows(CancellationException.class, () -> ReportImageShare.save(context, bands(context, 8, 360), format, null, valid::get));
            provider.awaitDeleted(1); assertEquals(1, provider.updates); assertFalse(provider.deletedOnMain);
            assertTrue(provider.files.isEmpty());
            provider = provider(context);
            ReportImageShare.Saved saved = ReportImageShare.save(context, bands(context, 8, 360), format, null, () -> true);
            saved.handOff(); saved.failedHandOff(); provider.awaitDeleted(1);
            assertFalse(provider.deletedOnMain); assertTrue(provider.files.isEmpty());
        }
    }

    @Test public void providerWriteFailureDeletesTheOnePendingFileWithoutPublishingIt() throws Exception {
        for (ReportImageExport.Format format : ReportImageExport.Format.values()) {
            MediaProvider provider = provider(context); provider.failOpenAt = 1;
            assertThrows(IOException.class, () -> ReportImageShare.save(context, bands(context, 8, 360), format, null, () -> true));
            provider.awaitDeleted(1); assertEquals(1, provider.inserts); assertEquals(0, provider.updates); assertFalse(provider.deletedOnMain);
        }
    }

    @Test @Config(sdk = 28) public void legacyFormatsUseOneSelectedDocumentAndNoPersistentGrant() throws Exception {
        for (ReportImageExport.Format format : ReportImageExport.Format.values()) {
            DocumentProvider provider = new DocumentProvider(); ProviderInfo info = new ProviderInfo(); info.authority = "report.docs";
            provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal("report.docs", provider);
            Uri uri = DocumentsContract.buildDocumentUri("report.docs", "selected-report." + format.extension);
            provider.files.put(uri, File.createTempFile("douai-selected-report", "." + format.extension, context.getCacheDir()));
            ReportImageShare.Saved saved = ReportImageShare.save(context, bands(context, 8, 360), format, uri, () -> true);
            assertEquals(0, provider.inserts); assertEquals(uri, saved.uri);
            Intent chooser = saved.chooser(), send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(Intent.ACTION_SEND, send.getAction()); assertEquals(format.mimeType, send.getType());
            assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM)); assertEquals(1, send.getClipData().getItemCount());
            assertNotEquals(0, chooser.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            assertTrue(context.getContentResolver().getPersistedUriPermissions().isEmpty());
            saved.delete(); provider.awaitDeleted(1);
        }
    }

    static ReportImageExport.Document bands(Context context, int count, int outputWidth) {
        LinearLayout report = new LinearLayout(context); report.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < count; index++) {
            View band = new View(context); band.setBackgroundColor(index == count - 1 ? Color.MAGENTA : Color.WHITE);
            report.addView(band, new LinearLayout.LayoutParams(-1, 100));
        }
        return ReportImageExport.capture(report, "最近 7 天", false, outputWidth, ReportImageExport.PAGE_HEIGHT, 360);
    }

    static File artifacts() throws IOException {
        File root = new File(System.getProperty("user.dir")).getCanonicalFile();
        while (root != null && !new File(root, "gradlew.bat").isFile()) root = root.getParentFile();
        if (root == null) throw new IOException("Project directory missing");
        File directory = new File(root, "artifacts/diagnostics-0.2.4/report-files");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create report artifacts");
        return directory;
    }
    static MediaProvider provider(Context context) {
        MediaProvider provider = new MediaProvider(); ProviderInfo info = new ProviderInfo(); info.authority = "media";
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal("media", provider); return provider;
    }
    public static class MediaProvider extends ContentProvider {
        final Map<Uri, File> files = new LinkedHashMap<>();
        volatile int inserts, updates, deletes;
        int opens, failOpenAt; volatile boolean deletedOnMain;
        boolean failMainTypeQuery, allPagesWrittenBeforePublish = true;
        Runnable onUpdate;
        Uri lastCollection; String lastMime, lastRelativePath;
        @Override public boolean onCreate() { return true; }
        @Override public synchronized Uri insert(Uri collection, ContentValues values) {
            lastMime = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
            assertTrue("image/png".equals(lastMime) || "application/pdf".equals(lastMime));
            lastRelativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
            assertEquals("application/pdf".equals(lastMime) ? "Download/DouAi" : "Pictures/DouAi", lastRelativePath);
            assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            lastCollection = collection; Uri uri = android.content.ContentUris.withAppendedId(collection, ++inserts);
            try { files.put(uri, File.createTempFile("douai-report-fixture", "application/pdf".equals(lastMime) ? ".pdf" : ".png", getContext().getCacheDir())); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
            return uri;
        }
        @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (++opens == failOpenAt) throw new FileNotFoundException("private provider fixture failure");
            return ParcelFileDescriptor.open(files.get(uri), ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            assertEquals(Integer.valueOf(0), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            for (File file : files.values()) allPagesWrittenBeforePublish &= file.length() > 0;
            updates++; if (onUpdate != null) onUpdate.run(); return 1;
        }
        @Override public synchronized int delete(Uri uri, String selection, String[] args) {
            deletedOnMain |= Looper.myLooper() == Looper.getMainLooper();
            File file = files.remove(uri); if (file == null) return 0; deletes++; file.delete(); notifyAll(); return 1;
        }
        synchronized void awaitDeleted(int expected) throws InterruptedException {
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (deletes < expected && System.nanoTime() < end) wait(50);
            assertEquals("Every unshared report must be removed", expected, deletes);
        }
        @Override public String getType(Uri uri) {
            if (failMainTypeQuery && Looper.myLooper() == Looper.getMainLooper()) throw new AssertionError("Chooser must not query provider on main");
            return lastMime == null ? "image/png" : lastMime;
        }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) { return null; }
    }
    public static final class DocumentProvider extends MediaProvider {
        @Override public Bundle call(String method, String argument, Bundle extras) {
            if ("android:deleteDocument".equals(method)) { delete(extras.getParcelable("uri"), null, null); return new Bundle(); }
            return super.call(method, argument, extras);
        }
    }
}
