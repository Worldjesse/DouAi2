package com.douai.module.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ChatMediaLoaderTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private Context context;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        ShadowMediaMetadataRetriever.reset();
    }

    @Test public void selectedUtf8TextPreservesUnicodeAndFormattingWithoutBom() throws Exception {
        String text = "\u4f5c\u54c1\u5206\u6790\nLine two\t\ud83c\udfac\n";
        File file = writeFile("notes.md", ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));
        ChatMediaLoader.Document document = ChatMediaLoader.loadDocument(context, Uri.fromFile(file));
        assertEquals("notes.md", document.name);
        assertEquals(text, document.text);
        assertTrue(document.images.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> document.images.add(image(Color.RED)));
    }

    @Test public void attachmentEntryPointAllowsTextAtFullVisualBudgetAndBoundsImageInput() throws Exception {
        File text = writeFile("message.txt", "selected file text".getBytes(StandardCharsets.UTF_8));
        ChatMediaLoader.Document document = ChatMediaLoader.loadAttachment(context, Uri.fromFile(text),
                ChatMediaLoader.Kind.DOCUMENT, 0);
        assertEquals("message.txt", document.name);
        assertEquals("selected file text", document.text);
        assertTrue(document.images.isEmpty());

        File image = writeFile("selected.png", Base64.getDecoder().decode(image(Color.BLUE).base64Data));
        ChatMediaLoader.Document picture = ChatMediaLoader.loadAttachment(context, Uri.fromFile(image),
                ChatMediaLoader.Kind.IMAGE, 1);
        assertEquals("selected.png", picture.name);
        assertTrue(picture.text.isEmpty());
        assertEquals(1, picture.images.size());
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadAttachment(context,
                Uri.fromFile(image), ChatMediaLoader.Kind.IMAGE, 0));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadAttachment(context,
                Uri.fromFile(image), ChatMediaLoader.Kind.VIDEO, 0));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadAttachment(context,
                Uri.fromFile(text), ChatMediaLoader.Kind.DOCUMENT, 5));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadAttachment(context,
                Uri.parse("https://example.test/image.png"), ChatMediaLoader.Kind.IMAGE, 1));
    }

    @Test public void actualDocxReadsBodyRunsParagraphsTabsAndBreaksOnly() throws Exception {
        String body = "<w:p><w:r><w:t xml:space=\"preserve\">First </w:t></w:r>"
                + "<w:r><w:t>\u4f5c\u54c1</w:t><w:tab/><w:t>column</w:t><w:br/><w:t>next</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>";
        File file = writeFile("prompt.docx", docx(body, "not body text"));
        ChatMediaLoader.Document document = ChatMediaLoader.loadDocument(context, Uri.fromFile(file));
        assertEquals("prompt.docx", document.name);
        assertEquals("First \u4f5c\u54c1\tcolumn\nnext\nSecond paragraph\n", document.text);
        assertFalse(document.text.contains("not body text"));
        assertTrue(document.images.isEmpty());
    }

    @Test public void docxStandardEscapedCharactersRemainReadableText() throws Exception {
        File file = writeFile("escaped.docx", docx(
                "<w:p><w:r><w:t>salt &amp; pepper &lt;3 &quot;quoted&quot;</w:t></w:r></w:p>", null));
        assertEquals("salt & pepper <3 \"quoted\"\n",
                ChatMediaLoader.loadDocument(context, Uri.fromFile(file)).text);
    }

    @Test public void rejectsUnreadableTextAndOversizedSelectedFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> loadText("broken.txt", new byte[]{(byte) 0xC3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> loadText("binary.txt", new byte[]{65, 0, 66}));
        assertThrows(IllegalArgumentException.class, () -> loadText("empty.txt", " \n\t".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> loadText("overlong.txt",
                "a".repeat(ChatMediaLoader.MAX_DOCUMENT_CHARS + 1).getBytes(StandardCharsets.UTF_8)));
        IllegalArgumentException bytes = assertThrows(IllegalArgumentException.class,
                () -> loadText("oversized.txt", new byte[1024 * 1024 + 1]));
        assertTrue(bytes.getMessage().contains("\u5927\u5c0f"));
        assertThrows(IllegalArgumentException.class, () -> loadText("archive.zip", new byte[]{1, 2, 3}));
    }

    @Test public void docxRejectsMissingBodyEntitiesAndLargeExpandedBody() throws Exception {
        ByteArrayOutputStream missing = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(missing)) {
            zip.putNextEntry(new ZipEntry("word/header1.xml"));
            zip.write("header".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        File noBody = writeFile("missing.docx", missing.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadDocument(context, Uri.fromFile(noBody)));
        File entity = writeFile("entity.docx", docx("<w:p><w:r><w:t>&unknown;</w:t></w:r></w:p>", null));
        assertThrows(Exception.class, () -> ChatMediaLoader.loadDocument(context, Uri.fromFile(entity)));
        File largeBody = writeFile("large-body.docx", docx("<w:p><w:r><w:t>"
                + "a".repeat(2 * 1024 * 1024 + 1) + "</w:t></w:r></w:p>", null));
        IllegalArgumentException large = assertThrows(IllegalArgumentException.class,
                () -> ChatMediaLoader.loadDocument(context, Uri.fromFile(largeBody)));
        assertTrue(large.getMessage().contains("\u6b63\u6587"));
    }

    @Test public void docxArchiveExpansionLimitIncludesNonDocumentEntries() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/media/huge.bin"));
            byte[] chunk = new byte[8192];
            for (int index = 0; index < 4097; index++) zip.write(chunk);
            zip.closeEntry();
        }
        assertTrue("the compressed archive should be comfortably below the input limit", bytes.size() < 1024 * 1024);
        File file = writeFile("expansion.docx", bytes.toByteArray());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ChatMediaLoader.loadDocument(context, Uri.fromFile(file)));
        assertTrue(failure.getMessage().contains("32 MB"));
    }

    @Test public void remoteAndUnsupportedUrisNeverReachLocalReaders() {
        for (String value : new String[]{"https://example.test/file.txt", "data:text/plain,hello", "ftp://example.test/a.docx"}) {
            assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadDocument(context, Uri.parse(value)));
            assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadVideo(context, Uri.parse(value), 2));
        }
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.validateLocalUri(null));
    }

    @Test public void videoFramesUseInteriorSampleTimesAndEncodeInOrder() throws Exception {
        File video = writeFile("selected.mp4", new byte[]{1, 2, 3});
        int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        List<Bitmap> originals = registerVideo(video, 8000, new long[]{1_000_000, 3_000_000, 5_000_000, 7_000_000}, colors);
        List<ChatImage> frames = ChatMediaLoader.loadVideoFile(video, 4);
        assertEquals(4, frames.size());
        for (int index = 0; index < frames.size(); index++) {
            assertEquals("image/jpeg", frames.get(index).mimeType);
            Bitmap decoded = decode(frames.get(index));
            assertEquals(1280, decoded.getWidth());
            assertEquals(640, decoded.getHeight());
            assertColorClose(colors[index], decoded.getPixel(640, 320));
            decoded.recycle();
            assertTrue("decoded source frame must be recycled", originals.get(index).isRecycled());
        }
        assertThrows(UnsupportedOperationException.class, () -> frames.clear());
    }

    @Test public void videoAttachmentKeepsDurationOrderedSamplesAndRecognitionLimitsWithFrames() throws Exception {
        File video = writeFile("clip.mp4", new byte[]{1, 2, 3});
        registerVideo(video, 8000, new long[]{2_000_000, 6_000_000}, new int[]{Color.RED, Color.BLUE});
        ChatMediaLoader.Document document = ChatMediaLoader.loadVideoDocumentFile(video,
                "clip.mp4", 2, null);
        assertEquals("clip.mp4", document.name);
        assertEquals(2, document.images.size());
        assertTrue(document.text.contains("8.000"));
        assertTrue(document.text.contains("2.000 秒、6.000 秒"));
        assertTrue(document.text.contains("关键帧"));
        assertTrue(document.text.contains("不包含音频或语音"));
        assertTrue(document.text.contains("未提供其余视频片段"));
        assertThrows(UnsupportedOperationException.class, () -> document.images.clear());
    }

    @Test public void subsecondVideoSamplesOneMiddleFrameAndAvoidsDuplicateFrames() throws Exception {
        File video = writeFile("short.mp4", new byte[]{1});
        registerVideo(video, 750, new long[]{375_000}, new int[]{Color.MAGENTA});
        assertEquals(1, ChatMediaLoader.loadVideoFile(video, 4).size());
        assertArrayEquals(new long[]{500_000, 1_500_000}, ChatMediaLoader.sampleTimes(2000, 4));
        assertArrayEquals(new long[]{43_200_000_000L}, ChatMediaLoader.sampleTimes(86_400_000, 1));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.sampleTimes(0, 1));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.sampleTimes(86_400_001, 1));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.sampleTimes(1000, 0));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.sampleTimes(1000, 5));
    }

    @Test public void malformedVideoMetadataAndMissingFramesFailWithoutPartialResults() throws Exception {
        File video = writeFile("malformed.mp4", new byte[]{1});
        ShadowMediaMetadataRetriever.addMetadata(video.getAbsolutePath(), MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "no");
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadVideoFile(video, 1));
        ShadowMediaMetadataRetriever.addMetadata(video.getAbsolutePath(), MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes");
        ShadowMediaMetadataRetriever.addMetadata(video.getAbsolutePath(), MediaMetadataRetriever.METADATA_KEY_DURATION, "unknown");
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadVideoFile(video, 1));
        ShadowMediaMetadataRetriever.addMetadata(video.getAbsolutePath(), MediaMetadataRetriever.METADATA_KEY_DURATION, "1000");
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadVideoFile(video, 1));
        File tooLarge = files.newFile("huge.mp4");
        try (RandomAccessFile source = new RandomAccessFile(tooLarge, "rw")) {
            source.setLength((long) ChatMediaLoader.MAX_VIDEO_BYTES + 1);
        }
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadVideoFile(tooLarge, 1));
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadVideoFile(files.newFile("empty.mp4"), 1));
    }

    @Test public void boundedCopyAcceptsLimitRejectsOverflowAndStopsWhenInterrupted() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, ChatMediaLoader.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        assertThrows(IllegalArgumentException.class,
                () -> ChatMediaLoader.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3));
        assertThrows(IllegalArgumentException.class,
                () -> ChatMediaLoader.readBounded(new ByteArrayInputStream(new byte[0]), 3));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream interrupted = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                int count = super.read(target, offset, length);
                Thread.currentThread().interrupt();
                return count;
            }
        };
        try {
            assertThrows(CancellationException.class, () -> ChatMediaLoader.copyBounded(interrupted, output, 3));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("a cancelled chunk must not be forwarded", 0, output.size());
        } finally { Thread.interrupted(); }
    }

    @Test @Config(shadows = InterruptingRetriever.class)
    public void interruptionDuringVideoExtractionRecyclesFrameAndReturnsNoResult() throws Exception {
        File video = writeFile("cancelled.mp4", new byte[]{1});
        List<Bitmap> frames = registerVideo(video, 1000, new long[]{500_000}, new int[]{Color.RED});
        try {
            assertThrows(CancellationException.class, () -> ChatMediaLoader.loadVideoFile(video, 1));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(frames.get(0).isRecycled());
        } finally { Thread.interrupted(); }
    }

    @Test @Config(shadows = {FixturePdfRenderer.class, FixturePdfPage.class})
    public void selectedPdfCopiesRealFileLimitsPagesAndCleansCacheAfterRendering() throws Exception {
        FixturePdfRenderer.reset();
        File pdf = writeFile("pages.pdf", pdf(6));
        List<String> before = temporaryMediaNames();
        ChatMediaLoader.Document document = ChatMediaLoader.loadDocument(context, Uri.fromFile(pdf));
        assertEquals("pages.pdf", document.name);
        assertEquals(4, document.images.size());
        assertTrue(document.text.contains("4"));
        assertTrue(document.text.contains("6"));
        assertEquals(List.of(0, 1, 2, 3), FixturePdfRenderer.renderedPages);
        assertEquals(4, FixturePdfRenderer.closedPages);
        assertTrue(FixturePdfRenderer.closed);
        assertNotEquals("the renderer must open the bounded cache copy", pdf.getAbsolutePath(), FixturePdfRenderer.openedPath);
        assertFalse(new File(FixturePdfRenderer.openedPath).exists());
        assertEquals(before, temporaryMediaNames());
        Bitmap decoded = decode(document.images.get(0));
        assertEquals(1280, decoded.getWidth());
        assertEquals(640, decoded.getHeight());
        assertColorClose(Color.RED, decoded.getPixel(640, 320));
        decoded.recycle();
    }

    @Test @Config(shadows = {FixturePdfRenderer.class, FixturePdfPage.class})
    public void pdfAttachmentUsesRemainingVisualBudgetAndRetainsTotalPageCount() throws Exception {
        FixturePdfRenderer.reset();
        File pdf = writeFile("long.pdf", pdf(6));
        ChatMediaLoader.Document document = ChatMediaLoader.loadAttachment(context, Uri.fromFile(pdf),
                ChatMediaLoader.Kind.DOCUMENT, 2);
        assertEquals(2, document.images.size());
        assertTrue(document.text.contains("前 2 页"));
        assertTrue(document.text.contains("共 6 页"));
        assertEquals(List.of(0, 1), FixturePdfRenderer.renderedPages);
        assertTrue(FixturePdfRenderer.closed);
        assertFalse(new File(FixturePdfRenderer.openedPath).exists());
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadAttachment(context,
                Uri.fromFile(pdf), ChatMediaLoader.Kind.DOCUMENT, 0));
    }

    @Test @Config(shadows = {FixturePdfRenderer.class, FixturePdfPage.class})
    public void invalidPdfAndCancelledPdfLeaveNoCachedFiles() throws Exception {
        List<String> before = temporaryMediaNames();
        File invalid = writeFile("invalid.pdf", new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> ChatMediaLoader.loadDocument(context, Uri.fromFile(invalid)));
        assertEquals(before, temporaryMediaNames());
        File valid = writeFile("cancelled.pdf", pdf(1));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> ChatMediaLoader.loadDocument(context, Uri.fromFile(valid)));
        } finally { Thread.interrupted(); }
        assertEquals(before, temporaryMediaNames());
    }

    private ChatMediaLoader.Document loadText(String name, byte[] content) throws Exception {
        return ChatMediaLoader.loadDocument(context, Uri.fromFile(writeFile(name, content)));
    }

    private File writeFile(String name, byte[] content) throws Exception {
        File file = files.newFile(name);
        Files.write(file.toPath(), content);
        return file;
    }

    private static byte[] docx(String body, String header) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (header != null) {
                zip.putNextEntry(new ZipEntry("word/header1.xml"));
                zip.write(header.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\""
                    + "http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static List<Bitmap> registerVideo(File file, long duration, long[] times, int[] colors) {
        DataSource source = DataSource.toDataSource(file.getAbsolutePath());
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, Long.toString(duration));
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1600");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "800");
        List<Bitmap> frames = new ArrayList<>();
        for (int index = 0; index < times.length; index++) {
            Bitmap bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(colors[index]);
            ShadowMediaMetadataRetriever.addScaledFrame(source, times[index], 1280, 640, bitmap);
            frames.add(bitmap);
        }
        return frames;
    }

    private static ChatImage image(int color) {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return new ChatImage("image/png", bytes.toByteArray());
    }

    private static Bitmap decode(ChatImage image) {
        byte[] bytes = Base64.getDecoder().decode(image.base64Data);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(decoded);
        return decoded;
    }

    private static void assertColorClose(int expected, int actual) {
        assertEquals(Color.red(expected), Color.red(actual), 8);
        assertEquals(Color.green(expected), Color.green(actual), 8);
        assertEquals(Color.blue(expected), Color.blue(actual), 8);
    }

    private List<String> temporaryMediaNames() {
        String[] names = context.getCacheDir().list((directory, name) -> name.startsWith("douai-media-"));
        List<String> result = new ArrayList<>(names == null ? List.of() : List.of(names));
        result.sort(String::compareTo);
        return result;
    }

    private static byte[] pdf(int pages) throws Exception {
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int page = 0; page < pages; page++) kids.append(3 + page * 2).append(" 0 R ");
        objects.add("<< /Type /Pages /Count " + pages + " /Kids [" + kids + "] >>");
        for (int page = 0; page < pages; page++) {
            int contentId = 4 + page * 2;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 2400 1200] /Resources << >> /Contents " + contentId + " 0 R >>");
            String paint = "1 0 0 rg 0 0 2400 1200 re f\n";
            objects.add("<< /Length " + paint.length() + " >>\nstream\n" + paint + "endstream");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(bytes.size());
            bytes.write(((index + 1) + " 0 obj\n" + objects.get(index) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = bytes.size();
        bytes.write(("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) bytes.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        bytes.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    @Implements(MediaMetadataRetriever.class)
    public static final class InterruptingRetriever extends ShadowMediaMetadataRetriever {
        @Implementation protected Bitmap getScaledFrameAtTime(long time, int option, int width, int height) {
            Bitmap frame = super.getScaledFrameAtTime(time, option, width, height);
            Thread.currentThread().interrupt();
            return frame;
        }
    }

    // Robolectric 4.14 has no Pdfium bridge. This replaces only the platform rendering boundary.
    @Implements(PdfRenderer.class)
    public static final class FixturePdfRenderer {
        static final List<Integer> renderedPages = new ArrayList<>();
        static int closedPages;
        static boolean closed;
        static String openedPath;
        private final List<int[]> sizes = new ArrayList<>();

        static void reset() { renderedPages.clear(); closedPages = 0; closed = false; openedPath = null; }

        @Implementation protected void __constructor__(ParcelFileDescriptor descriptor) throws Exception {
            File[] cached = RuntimeEnvironment.getApplication().getCacheDir().listFiles(
                    (directory, name) -> name.startsWith("douai-media-") && name.endsWith(".pdf"));
            assertNotNull(cached);
            assertEquals("exactly one selected PDF cache copy should be open", 1, cached.length);
            openedPath = cached[0].getAbsolutePath();
            assertEquals(cached[0].length(), descriptor.getStatSize());
            String pdf = Files.readString(cached[0].toPath(), StandardCharsets.US_ASCII);
            if (!pdf.startsWith("%PDF-") || !pdf.contains("%%EOF")) throw new IllegalArgumentException("Invalid PDF fixture");
            Matcher pages = Pattern.compile("/Type /Page /Parent 2 0 R /MediaBox \\[0 0 (\\d+) (\\d+)\\]").matcher(pdf);
            while (pages.find()) sizes.add(new int[]{Integer.parseInt(pages.group(1)), Integer.parseInt(pages.group(2))});
            Matcher count = Pattern.compile("/Type /Pages /Count (\\d+)").matcher(pdf);
            if (!count.find() || sizes.size() != Integer.parseInt(count.group(1))) throw new IllegalArgumentException("Invalid page tree");
        }

        @Implementation protected int getPageCount() { return sizes.size(); }
        @Implementation protected PdfRenderer.Page openPage(int index) {
            PdfRenderer.Page page = Shadow.newInstanceOf(PdfRenderer.Page.class);
            FixturePdfPage state = Shadow.extract(page);
            state.index = index;
            state.width = sizes.get(index)[0];
            state.height = sizes.get(index)[1];
            return page;
        }
        @Implementation protected void close() { closed = true; }
    }

    @Implements(PdfRenderer.Page.class)
    public static final class FixturePdfPage {
        int index;
        int width;
        int height;
        @Implementation protected int getWidth() { return width; }
        @Implementation protected int getHeight() { return height; }
        @Implementation protected void render(Bitmap target, Rect clip, Matrix matrix, int mode) {
            assertEquals(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, mode);
            target.eraseColor(Color.RED);
            FixturePdfRenderer.renderedPages.add(index);
        }
        @Implementation protected void close() { FixturePdfRenderer.closedPages++; }
    }
}
