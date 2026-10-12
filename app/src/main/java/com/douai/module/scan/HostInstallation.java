package com.douai.module.scan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Installation stamps supplement DEX/rule identity, including module-only updates. */
public final class HostInstallation {
    private HostInstallation() { }

    public static String identity(String moduleVersion, File moduleApk, List<File> hostArchives)
            throws IOException {
        if (moduleVersion == null || hostArchives == null || hostArchives.isEmpty()) {
            throw new IOException("Installation metadata unavailable");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("douai-installation-v1\n" + moduleVersion + "\n").getBytes(StandardCharsets.UTF_8));
            stamp(digest, moduleApk);
            List<File> archives = new ArrayList<>(hostArchives);
            archives.sort(Comparator.comparing(File::getAbsolutePath));
            for (File archive : archives) stamp(digest, archive);
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void stamp(MessageDigest digest, File archive) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("Installed APK unavailable");
        String value = archive.getCanonicalPath() + "|" + archive.length() + "|" + archive.lastModified() + "\n";
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
