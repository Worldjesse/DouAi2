package com.douai.module.scan;

import org.junit.Test;

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

import static org.junit.Assert.*;

public final class HostApkProfileTest {
    @Test public void readsBothReleasedProfilesFromUtf8AndUtf16BinaryManifests() throws Exception {
        assertSame(HostAdaptation.PROFILE_406, HostApkProfile.read(apk(manifest(
                HostAdaptation.TARGET_PACKAGE, "40.6.0", 400601, true))));
        assertSame(HostAdaptation.PROFILE_408, HostApkProfile.read(apk(manifest(
                HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, false))));
    }

    @Test public void unknownVersionsMismatchedCodesAndDifferentPackagesNeverSelectTheLatestProfile() throws Exception {
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400801, true))));
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400601, true))));
        assertNull(HostApkProfile.read(apk(manifest("other.application", "40.8.0", 400801, true))));
    }

    @Test public void invalidChunkAttributeAndStringOffsetsFailAsIoErrors() throws Exception {
        byte[] valid = manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true);
        assertInvalid(Arrays.copyOf(valid, valid.length - 1));

        byte[] badChunk = valid.clone();
        buffer(badChunk).putInt(12, Integer.MAX_VALUE);
        assertInvalid(badChunk);

        byte[] badString = valid.clone();
        buffer(badString).putInt(8 + 28, Integer.MAX_VALUE);
        assertInvalid(badString);

        byte[] badAttribute = valid.clone();
        int element = 8 + buffer(valid).getInt(12);
        buffer(badAttribute).putShort(element + 28, (short) 0xffff);
        assertInvalid(badAttribute);

        assertInvalid(new byte[16 * 1024 * 1024 + 1]);
    }

    @Test public void versionAttributesRequireTheAndroidNamespace() throws Exception {
        byte[] xml = manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true);
        int element = 8 + buffer(xml).getInt(12);
        buffer(xml).putInt(element + 36 + 20, -1);
        assertInvalid(xml);
    }

    @Test public void uncompiledRawTextCannotOverrideTheActualTypedVersion() throws Exception {
        byte[] xml = manifest(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400801, true);
        int element = 8 + buffer(xml).getInt(12);
        buffer(xml).putInt(element + 36 + 20 + 8, 7); // Raw text says 40.8.0; the compiled value remains 40.7.0.
        assertNull(HostApkProfile.read(apk(xml)));
    }

    private static void assertInvalid(byte[] manifest) {
        try {
            HostApkProfile.parseManifest(manifest);
            fail("Malformed Manifest must fail explicitly");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static File apk(byte[] manifest) throws IOException {
        // Its misleading name ensures profile selection is bound to Manifest contents.
        File apk = Files.createTempFile("douyin-40.8.0-", ".apk").toFile();
        apk.deleteOnExit();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            output.write(manifest);
            output.closeEntry();
        }
        return apk;
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    private static byte[] manifest(String packageName, String versionName, int versionCode, boolean utf8) throws IOException {
        List<String> strings = Arrays.asList("http://schemas.android.com/apk/res/android", "manifest",
                "package", "versionName", "versionCode", packageName, versionName, "40.8.0");
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = contents.size();
            String text = strings.get(i);
            byte[] encoded = text.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            if (utf8) { contents.write(text.length()); contents.write(encoded.length); }
            else { contents.write(text.length() & 255); contents.write(text.length() >>> 8); }
            contents.write(encoded);
            contents.write(0);
            if (!utf8) contents.write(0);
        }
        while ((contents.size() & 3) != 0) contents.write(0);
        int poolSize = 28 + strings.size() * 4 + contents.size();
        int elementSize = 36 + 3 * 20;
        ByteBuffer data = ByteBuffer.allocate(8 + poolSize + elementSize).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 3).putShort((short) 8).putInt(data.capacity());
        data.putShort((short) 1).putShort((short) 28).putInt(poolSize);
        data.putInt(strings.size()).putInt(0).putInt(utf8 ? 0x100 : 0).putInt(28 + strings.size() * 4).putInt(0);
        for (int offset : offsets) data.putInt(offset);
        data.put(contents.toByteArray());
        data.putShort((short) 0x0102).putShort((short) 16).putInt(elementSize);
        data.putInt(1).putInt(-1).putInt(-1).putInt(1);
        data.putShort((short) 20).putShort((short) 20).putShort((short) 3);
        data.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        attribute(data, -1, 2, 5, 3, 5);
        attribute(data, 0, 3, 6, 3, 6);
        attribute(data, 0, 4, -1, 0x10, versionCode);
        return data.array();
    }

    private static void attribute(ByteBuffer data, int namespace, int name, int raw, int type, int value) {
        data.putInt(namespace).putInt(name).putInt(raw).putShort((short) 8);
        data.put((byte) 0).put((byte) type).putInt(value);
    }
}
