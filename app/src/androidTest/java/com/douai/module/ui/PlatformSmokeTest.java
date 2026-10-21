package com.douai.module.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.SettingsRepository;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyDownloadSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android contracts using only isolated fixture preferences and newly owned report files. */
@RunWith(AndroidJUnit4.class)
public final class PlatformSmokeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule public final TestWatcher metrics = new TestWatcher() {
        private long startedNanos, heapBefore;
        private String outcome;
        @Override protected void starting(Description description) {
            startedNanos = SystemClock.elapsedRealtimeNanos();
            heapBefore = heapBytes();
            outcome = "running";
        }
        @Override protected void succeeded(Description description) { outcome = "passed"; }
        @Override protected void failed(Throwable failure, Description description) { outcome = "failed"; }
        @Override protected void skipped(org.junit.AssumptionViolatedException failure, Description description) { outcome = "skipped"; }
        @Override protected void finished(Description description) {
            try {
                JSONObject record = new JSONObject().put("test", description.getMethodName()).put("status", outcome)
                        .put("sdk", Build.VERSION.SDK_INT).put("elapsedNanos", SystemClock.elapsedRealtimeNanos() - startedNanos)
                        .put("javaHeapBeforeBytes", heapBefore).put("javaHeapAfterBytes", heapBytes())
                        .put("javaHeapMaxBytes", Runtime.getRuntime().maxMemory())
                        .put("scope", "module_android_platform_fixture");
                if ("skipped".equals(outcome)) record.put("uncovered", "API_28_requires_interactive_SAF_destination");
                String run = InstrumentationRegistry.getArguments().getString("douaiSmokeRun", "manual");
                if (!run.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid smoke run identifier");
                File directory = new File(context.getFilesDir(), "platform-smoke");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create smoke metrics directory");
                try (FileOutputStream output = new FileOutputStream(new File(directory, run + ".jsonl"), true)) {
                    output.write((record + "\n").getBytes(StandardCharsets.UTF_8));
                }
                Bundle result = new Bundle(); result.putString("stream", "DOUAI_PLATFORM_METRIC=" + record + "\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
            } catch (Exception failure) { throw new AssertionError("Platform metrics could not be persisted", failure); }
        }
    };

    @Test public void redactedBackupRoundTripsUsingAndroidRegexAndIsolatedPreferences() throws Exception {
        String prefix = "douai-platform-smoke-" + UUID.randomUUID();
        SharedPreferences sourcePreferences = context.getSharedPreferences(prefix + "-source", Context.MODE_PRIVATE);
        SharedPreferences targetPreferences = context.getSharedPreferences(prefix + "-target", Context.MODE_PRIVATE);
        try {
            assertTrue(sourcePreferences.edit()
                    .putString(SpeechSettings.MINIMAX_API_KEY, "fixture-minimax-secret")
                    .putString(SpeechSettings.GET_TEMPLATE,
                            "https://example.test/tts?voice={voice}&apikey=fixture-url-secret&text={text}")
                    .putString(ThirdPartyDownloadSettings.GET_TEMPLATE,
                            "https://example.test/parse?app_id=platform-smoke&token=fixture-app-secret&url={url}")
                    .putString(SpeechSettings.HEADERS, "{\"Authorization\":\"Bearer fixture-header-secret\"}").commit());
            SettingsRepository source = new SettingsRepository(sourcePreferences), target = new SettingsRepository(targetPreferences);
            String backup = ConfigTransfer.export(source, false);
            assertFalse(new JSONObject(backup).getBoolean("includesSecrets"));
            for (String secret : Arrays.asList("fixture-minimax-secret", "fixture-url-secret", "fixture-app-secret", "fixture-header-secret"))
                assertFalse("Default backup exposed a fixture credential", backup.contains(secret));
            assertTrue("Android ICU must preserve the URL placeholder", backup.contains("{url}"));
            assertTrue(backup.contains("{voice}")); assertTrue(backup.contains("{text}"));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, backup);
            assertTrue(preview.redactedCount >= 4); assertTrue(preview.missingSecretsCount >= 4);
            ConfigTransfer.apply(target, preview);
            assertEquals("", target.getString(SpeechSettings.MINIMAX_API_KEY, "missing"));
            assertEquals("https://example.test/tts?voice={voice}&apikey=&text={text}", target.getString(SpeechSettings.GET_TEMPLATE, ""));
            assertEquals("https://example.test/parse?app_id=platform-smoke&token=&url={url}", target.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
            assertFalse(target.snapshot().toString().contains("fixture-"));
        } finally {
            boolean sourceDeleted = context.deleteSharedPreferences(prefix + "-source");
            boolean targetDeleted = context.deleteSharedPreferences(prefix + "-target");
            assertTrue("Source fixture preferences were not removed", sourceDeleted);
            assertTrue("Target fixture preferences were not removed", targetDeleted);
        }
    }

    @Test public void ownedMediaStorePngDecodesAndIsRemoved() throws Exception {
        requireMediaStore();
        ReportImageExport.Document document = report();
        ReportImageShare.Saved saved = null;
        try {
            saved = ReportImageShare.save(context, document, ReportImageExport.Format.LONG_PNG, null, () -> true);
            assertPublished(saved, "image/png");
            try (InputStream input = context.getContentResolver().openInputStream(saved.uri)) {
                assertNotNull(input);
                byte[] header = new byte[8]; int offset = 0, count;
                while (offset < header.length && (count = input.read(header, offset, header.length - offset)) > 0) offset += count;
                assertEquals(8, offset);
                assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, header);
            }
            try (InputStream input = context.getContentResolver().openInputStream(saved.uri)) {
                Bitmap decoded = BitmapFactory.decodeStream(input);
                assertNotNull("Android could not independently decode the exported PNG", decoded);
                try {
                    assertEquals(360, decoded.getWidth()); assertEquals(document.longPngHeight(), decoded.getHeight());
                    assertEquals(Color.MAGENTA, decoded.getPixel(10, decoded.getHeight() - 50));
                } finally { decoded.recycle(); }
            }
        } finally { remove(saved); }
    }

    @Test public void ownedMediaStorePdfParsesAndIsRemoved() throws Exception {
        requireMediaStore();
        ReportImageExport.Document document = report();
        ReportImageShare.Saved saved = null;
        try {
            saved = ReportImageShare.save(context, document, ReportImageExport.Format.PDF, null, () -> true);
            assertPublished(saved, "application/pdf");
            try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(saved.uri, "r")) {
                assertNotNull(file);
                try (PdfRenderer pdf = new PdfRenderer(file)) {
                    assertEquals(document.pdfPageCount(), pdf.getPageCount());
                    assertTrue("The fixture must exercise pagination", pdf.getPageCount() > 1);
                    try (PdfRenderer.Page page = pdf.openPage(pdf.getPageCount() - 1)) {
                        Bitmap raster = Bitmap.createBitmap(150, 212, Bitmap.Config.ARGB_8888);
                        try {
                            raster.eraseColor(Color.WHITE);
                            page.render(raster, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            boolean hasInk = false;
                            for (int y = 0; y < raster.getHeight(); y += 2) for (int x = 0; x < raster.getWidth(); x += 2)
                                hasInk |= raster.getPixel(x, y) != Color.WHITE;
                            assertTrue("The last PDF page did not render any report content", hasInk);
                        } finally { raster.recycle(); }
                    }
                }
            }
        } finally { remove(saved); }
    }

    private static long heapBytes() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }

    private static void requireMediaStore() {
        Assume.assumeTrue("API 28 requires an interactive SAF destination; unattended storage smoke is pending", Build.VERSION.SDK_INT >= 29);
    }

    private ReportImageExport.Document report() {
        AtomicReference<ReportImageExport.Document> document = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LinearLayout report = new LinearLayout(context); report.setOrientation(LinearLayout.VERTICAL);
            int bandHeight = Math.round(100 * context.getResources().getDisplayMetrics().density);
            for (int index = 0; index < 8; index++) {
                View band = new View(context); band.setBackgroundColor(index == 7 ? Color.MAGENTA : Color.WHITE);
                report.addView(band, new LinearLayout.LayoutParams(-1, bandHeight));
            }
            document.set(ReportImageExport.capture(report, "Platform smoke fixture", false, 360, 900, 360));
        });
        return document.get();
    }

    private void assertPublished(ReportImageShare.Saved saved, String mime) {
        assertEquals(mime, context.getContentResolver().getType(saved.uri));
        try (Cursor cursor = context.getContentResolver().query(saved.uri,
                new String[]{MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE}, null, null, null)) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0)); assertTrue(cursor.getLong(1) > 0);
        }
    }

    private void remove(ReportImageShare.Saved saved) {
        if (saved == null) return;
        saved.delete();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        boolean exists;
        do {
            try (Cursor cursor = context.getContentResolver().query(saved.uri, new String[]{"_id"}, null, null, null)) {
                exists = cursor != null && cursor.moveToFirst();
            }
            if (!exists) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        // Keep a failed production-cleanup contract from leaving its owned fixture behind.
        context.getContentResolver().delete(saved.uri, null, null);
        fail("Asynchronous report cleanup did not remove the fixture within 5 seconds");
    }
}
