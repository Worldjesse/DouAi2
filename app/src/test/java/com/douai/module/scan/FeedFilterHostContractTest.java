package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertTrue;

/** Keeps new feed-card classification assumptions tied to the selected original host APK. */
public final class FeedFilterHostContractTest {
    private static final String AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";

    @Test public void productShoppingHotspotAndLifeSpuPredicatesMatchTheOriginalHost() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        File apk = configured == null ? null : new File(configured);
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", apk != null && apk.isFile());
        HostApkTestProfile.profile(apk);

        ClassDef aweme = null;
        try (ZipFile archive = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && aweme == null) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (AWEME.equals(type.getType())) {
                            aweme = type;
                            break;
                        }
                    }
                }
            }
        }
        assertTrue("Original host has the Aweme model", aweme != null);

        assertTypePredicate(aweme, "isProductCard", 112);
        assertTypePredicate(aweme, "isShoppingCardAweme", 9001);
        assertTypePredicate(aweme, "isHotSpotRankCard", 104);

        Method nearbyAd = method(aweme, "isAdNearbyCard");
        assertTrue("Nearby ad classification reads its dedicated card struct",
                fieldNames(nearbyAd).contains("adNearbyCardStruct"));

        Method lifeSpu = method(aweme, "isLifeSpuCard");
        assertTrue("Life SPU classification reads POI D-card data", fieldNames(lifeSpu).contains("poiDcardStruct"));
        assertTrue("Life SPU classification checks the scene", fieldNames(lifeSpu).contains("scene"));
        assertTrue("Life SPU classification uses its exact scene value",
                strings(lifeSpu).contains("FeedConcern_LifeGoodsSpu"));
        assertTrue("Life SPU classification checks type 140", literals(lifeSpu).contains(140L));
    }

    private static void assertTypePredicate(ClassDef owner, String name, long type) {
        Method method = method(owner, name);
        assertTrue(name + " returns boolean", "Z".equals(method.getReturnType()));
        assertTrue(name + " reads awemeType", fieldNames(method).contains("awemeType"));
        assertTrue(name + " checks type " + type, literals(method).contains(type));
    }

    private static Method method(ClassDef owner, String name) {
        for (Method method : owner.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().isEmpty()
                    && method.getImplementation() != null) return method;
        }
        throw new AssertionError("Missing Aweme classifier: " + name);
    }

    private static Set<String> fieldNames(Method method) {
        Set<String> result = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof FieldReference) {
                result.add(((FieldReference) ((ReferenceInstruction) instruction).getReference()).getName());
            }
        }
        return result;
    }

    private static Set<Long> literals(Method method) {
        Set<Long> result = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof NarrowLiteralInstruction) {
                result.add((long) ((NarrowLiteralInstruction) instruction).getNarrowLiteral());
            }
        }
        return result;
    }

    private static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof StringReference) {
                result.add(((StringReference) ((ReferenceInstruction) instruction).getReference()).getString());
            }
        }
        return result;
    }
}
