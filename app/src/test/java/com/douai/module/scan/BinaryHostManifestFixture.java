package com.douai.module.scan;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic binary Manifests exercise original APK identity without real host fixtures. */
public final class BinaryHostManifestFixture {
    private BinaryHostManifestFixture() { }

    public static File apk(String pkg, String version, int code, long build) throws IOException {
        File apk = Files.createTempFile("host-build-", ".apk").toFile();
        apk.deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(manifest(pkg, version, code, true, build));
            zip.closeEntry();
        }
        return apk;
    }

    public static byte[] manifest(String pkg, String version, int code, boolean utf8, long build) throws IOException {
        List<String> strings = Arrays.asList("http://schemas.android.com/apk/res/android", "manifest",
                "package", "versionName", "versionCode", pkg, version, "40.8.0", "application",
                "meta-data", "name", "value", "UPDATE_VERSION_CODE", "40809900", "activity");
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = contents.size();
            String text = strings.get(i);
            byte[] encoded = text.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            if (utf8) { contents.write(text.length()); contents.write(encoded.length); }
            else { contents.write(text.length() & 255); contents.write(text.length() >>> 8); }
            contents.write(encoded); contents.write(0);
            if (!utf8) contents.write(0);
        }
        while ((contents.size() & 3) != 0) contents.write(0);
        int pool = 28 + strings.size() * 4 + contents.size();
        int extra = build < 0 ? 0 : 36 + 76 + 24 + 24 + 24;
        ByteBuffer data = ByteBuffer.allocate(8 + pool + 96 + extra).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 3).putShort((short) 8).putInt(data.capacity());
        data.putShort((short) 1).putShort((short) 28).putInt(pool);
        data.putInt(strings.size()).putInt(0).putInt(utf8 ? 0x100 : 0).putInt(28 + strings.size() * 4).putInt(0);
        for (int offset : offsets) data.putInt(offset);
        data.put(contents.toByteArray());
        start(data, 1, 3);
        attribute(data, -1, 2, 5, 3, 5);
        attribute(data, 0, 3, 6, 3, 6);
        attribute(data, 0, 4, -1, 0x10, code);
        if (build >= 0) {
            start(data, 8, 0); start(data, 9, 2);
            attribute(data, 0, 10, 12, 3, 12);
            attribute(data, 0, 11, -1, 0x10, (int) build);
            end(data, 9); end(data, 8); end(data, 1);
        }
        return data.array();
    }

    private static void start(ByteBuffer data, int name, int count) {
        data.putShort((short) 0x0102).putShort((short) 16).putInt(36 + 20 * count);
        data.putInt(1).putInt(-1).putInt(-1).putInt(name);
        data.putShort((short) 20).putShort((short) 20).putShort((short) count);
        data.putShort((short) 0).putShort((short) 0).putShort((short) 0);
    }
    private static void end(ByteBuffer data, int name) {
        data.putShort((short) 0x0103).putShort((short) 16).putInt(24);
        data.putInt(1).putInt(-1).putInt(-1).putInt(name);
    }
    private static void attribute(ByteBuffer data, int ns, int name, int raw, int kind, int value) {
        data.putInt(ns).putInt(name).putInt(raw).putShort((short) 8).put((byte) 0).put((byte) kind).putInt(value);
    }
}
