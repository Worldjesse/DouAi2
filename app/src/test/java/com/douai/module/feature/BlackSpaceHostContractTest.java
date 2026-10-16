package com.douai.module.feature;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Checks the exact host ABI and field-binding instructions used by black-space recovery. */
public final class BlackSpaceHostContractTest {
    private static final String PANEL = "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;";
    private static final String BOTTOM = "Lcom/ss/android/ugc/aweme/feed/ui/bottom/BottomSpace;";

    @Test public void originalHostDeclaresBothSpaceCallbacksAndBindsTheirExactViews() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", path != null);
        assertFalse("DOUAI_TEST_APK must not be blank", path.trim().isEmpty());
        File apk = new File(path);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        try (ZipFile archive = new ZipFile(apk)) {
            ClassDef panel = type(archive, "classes29.dex", PANEL);
            ClassDef bottom = type(archive, "classes37.dex", BOTTOM);
            assertEquals("BottomSpace must remain a View", "Landroid/view/View;", bottom.getSuperclass());
            assertField(panel, "i", "Landroid/view/View;");
            assertField(panel, "j", BOTTOM);
            assertCallback(panel, "LLLFF()V");
            Method created = assertCallback(panel, "onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V");
            assertNotNull("onViewCreated must have a real field-binding implementation", created.getImplementation());

            Map<String, Integer> writes = new HashMap<>();
            for (Instruction instruction : created.getImplementation().getInstructions()) {
                if (instruction.getOpcode() != Opcode.IPUT_OBJECT || !(instruction instanceof ReferenceInstruction)) continue;
                Object reference = ((ReferenceInstruction) instruction).getReference();
                if (!(reference instanceof FieldReference)) continue;
                FieldReference field = (FieldReference) reference;
                if (!PANEL.equals(field.getDefiningClass())) continue;
                if (("i".equals(field.getName()) && "Landroid/view/View;".equals(field.getType()))
                        || ("j".equals(field.getName()) && BOTTOM.equals(field.getType()))) {
                    writes.merge(field.getName(), 1, Integer::sum);
                }
            }
            assertEquals("onViewCreated must bind the top and bottom fields before its after-hook runs",
                    Map.of("i", 1, "j", 1), writes);
        }
    }

    private static ClassDef type(ZipFile archive, String dexName, String descriptor) throws Exception {
        ZipEntry entry = archive.getEntry(dexName);
        assertNotNull("Missing original host DEX: " + dexName, entry);
        ClassDef match = null;
        try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
            for (ClassDef candidate : dex.getClasses()) {
                if (!descriptor.equals(candidate.getType())) continue;
                assertNull("Host class must be unique in " + dexName, match);
                match = candidate;
            }
        }
        assertNotNull(dexName + " is missing " + descriptor, match);
        return match;
    }

    private static void assertField(ClassDef type, String name, String descriptor) {
        Field match = null;
        for (Field field : type.getFields()) {
            if (!name.equals(field.getName()) || !descriptor.equals(field.getType())) continue;
            assertNull("Ambiguous host field: " + name, match);
            match = field;
        }
        assertNotNull(type.getType() + " is missing field " + name + ':' + descriptor, match);
        assertTrue(name + " must remain public", AccessFlags.PUBLIC.isSet(match.getAccessFlags()));
        assertFalse(name + " must remain an instance field", AccessFlags.STATIC.isSet(match.getAccessFlags()));
    }

    private static Method assertCallback(ClassDef type, String signature) {
        Method match = null;
        for (Method method : type.getMethods()) {
            StringBuilder actual = new StringBuilder(method.getName()).append('(');
            for (CharSequence parameter : method.getParameterTypes()) actual.append(parameter);
            actual.append(')').append(method.getReturnType());
            if (!signature.contentEquals(actual)) continue;
            assertNull("Ambiguous host callback: " + signature, match);
            match = method;
        }
        assertNotNull(type.getType() + " is missing " + signature, match);
        assertTrue(signature + " must remain public", AccessFlags.PUBLIC.isSet(match.getAccessFlags()));
        assertFalse(signature + " must remain an instance method", AccessFlags.STATIC.isSet(match.getAccessFlags()));
        return match;
    }
}
