package com.douai.module.scan;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Checks the shipped feed identifiers against the original host resource table. */
public final class HostResourceTableContractTest {
    @Test public void feedResourceIdentifiersRetainTheirVerifiedEntryNamesInTheOriginalHost() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        byte[] bytes;
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry table = archive.getEntry("resources.arsc");
            assertNotNull("Original host resources.arsc", table);
            try (InputStream input = archive.getInputStream(table)) { bytes = input.readAllBytes(); }
        }
        Map<Integer, String> names = idNames(bytes);
        assertFalse("Original host must expose its application ID resource type", names.isEmpty());
        for (Map.Entry<Integer, String> expected : HostApkTestProfile.profile(apk).getFeedResources().entrySet()) {
            assertEquals("Host ID 0x" + Integer.toHexString(expected.getKey()),
                    expected.getValue(), names.get(expected.getKey()));
        }
    }

    private static Map<Integer, String> idNames(byte[] bytes) {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("Android resource table", 0x0002, u16(data, 0));
        Map<Integer, String> names = new LinkedHashMap<>();
        for (int offset = u16(data, 2), limit = data.getInt(4); offset + 8 <= limit; ) {
            int size = chunkSize(data, offset, limit);
            if (u16(data, offset) == 0x0200 && data.getInt(offset + 8) == 0x7f) {
                String[] keys = readStrings(data, offset + data.getInt(offset + 276));
                int typeOffset = u16(data, offset + 2) >= 288 ? data.getInt(offset + 284) : 0;
                for (int child = offset + u16(data, offset + 2); child + 8 <= offset + size; ) {
                    int childSize = chunkSize(data, child, offset + size);
                    if (u16(data, child) == 0x0201
                            && (data.get(child + 8) & 255) + typeOffset == 0x0a) {
                        int flags = data.get(child + 9) & 255;
                        int count = data.getInt(child + 12);
                        int entries = child + data.getInt(child + 16);
                        int offsets = child + u16(data, child + 2);
                        for (int i = 0; i < count; i++) {
                            int index = i;
                            int relative;
                            if ((flags & 1) != 0) {
                                index = u16(data, offsets + i * 4);
                                relative = u16(data, offsets + i * 4 + 2) * 4;
                            } else if ((flags & 2) != 0) {
                                int value = u16(data, offsets + i * 2);
                                relative = value == 0xffff ? -1 : value * 4;
                            } else relative = data.getInt(offsets + i * 4);
                            if (relative == -1) continue;
                            int entry = entries + relative;
                            assertTrue("Resource entry must stay inside its type chunk",
                                    entry >= entries && entry + 8 <= child + childSize);
                            int key = data.getInt(entry + 4);
                            assertTrue("Resource key must exist", key >= 0 && key < keys.length);
                            int id = 0x7f0a0000 | index;
                            String previous = names.put(id, keys[key]);
                            if (previous != null) assertEquals("ID names must agree across configurations", previous, keys[key]);
                        }
                    }
                    child += childSize;
                }
            }
            offset += size;
        }
        return names;
    }

    private static int chunkSize(ByteBuffer data, int offset, int limit) {
        int header = u16(data, offset + 2), size = data.getInt(offset + 4);
        assertTrue("Resource chunk must remain in its parent", header >= 8 && size >= header
                && offset + size <= limit && limit <= data.capacity());
        return size;
    }

    private static String[] readStrings(ByteBuffer data, int chunk) {
        assertEquals("Resource string pool", 0x0001, u16(data, chunk));
        int count = data.getInt(chunk + 8), flags = data.getInt(chunk + 16);
        int start = chunk + data.getInt(chunk + 20), offsets = chunk + u16(data, chunk + 2);
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            int offset = start + data.getInt(offsets + i * 4);
            if ((flags & 0x100) != 0) {
                int[] chars = length8(data, offset), length = length8(data, chars[1]);
                byte[] value = new byte[length[0]];
                for (int j = 0; j < value.length; j++) value[j] = data.get(length[1] + j);
                result[i] = new String(value, StandardCharsets.UTF_8);
            } else {
                int length = u16(data, offset); offset += 2;
                if ((length & 0x8000) != 0) {
                    length = ((length & 0x7fff) << 16) | u16(data, offset); offset += 2;
                }
                char[] value = new char[length];
                for (int j = 0; j < length; j++) value[j] = (char) u16(data, offset + j * 2);
                result[i] = new String(value);
            }
        }
        return result;
    }

    private static int[] length8(ByteBuffer data, int offset) {
        int value = data.get(offset++) & 255;
        if ((value & 128) != 0) value = ((value & 127) << 8) | (data.get(offset++) & 255);
        return new int[]{value, offset};
    }

    private static int u16(ByteBuffer data, int offset) { return data.getShort(offset) & 0xffff; }
}
