package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;

import org.junit.Assume;
import org.junit.Test;

import java.io.InputStream;
import java.io.BufferedInputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Verifies the actual packaged cache has no linkage to APIs unavailable on Android 9-13. */
public final class HostReflectionCacheApkContractTest {
    @Test public void packagedCacheDoesNotLinkToTheApi34ClassValueType() throws Exception {
        String path = System.getenv("DOUAI_VERIFY_RELEASE_APK");
        Assume.assumeTrue("Build Release and set DOUAI_VERIFY_RELEASE_APK", path != null && !path.isEmpty());
        boolean found = false;
        try (ZipFile apk = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                try (InputStream input = new BufferedInputStream(apk.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (!type.getType().startsWith("Lcom/douai/module/feature/HostReflectionCache")) continue;
                        found = true;
                        compatible(type.getSuperclass());
                        for (String iface : type.getInterfaces()) compatible(iface);
                        for (com.android.tools.smali.dexlib2.iface.Field field : type.getFields()) compatible(field.getType());
                        for (Method method : type.getMethods()) {
                            compatible(method.getReturnType());
                            for (CharSequence parameter : method.getParameterTypes()) compatible(parameter.toString());
                            if (method.getImplementation() == null) continue;
                            for (Instruction instruction : method.getImplementation().getInstructions()) {
                                if (instruction instanceof ReferenceInstruction) {
                                    compatible(((ReferenceInstruction) instruction).getReference().toString());
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Reflection cache was not found in the requested APK", found);
    }

    private static void compatible(String reference) {
        assertTrue("The minSdk 28 cache links to API34 ClassValue: " + reference,
                reference == null || !reference.contains("Ljava/lang/ClassValue;"));
    }
}
