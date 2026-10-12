package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HostScannerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void emptyRulesAvoidParsingHostDexInstructions() throws Exception {
        File apk = temporaryFolder.newFile("host.apk");
        writeApk(apk, entries("classes.dex", new byte[8]));

        HostScanReport report = new HostScanner().scan(Collections.singletonList(apk),
                HostRuleSet.of(Collections.emptyList()));

        assertTrue(report.getHostIdentity().length() == 64);
        assertTrue(report.getRuleResults().isEmpty());
    }

    @Test
    public void signatureRuleSkipsBodiesAcrossLargeDex() throws Exception {
        File apk = temporaryFolder.newFile("large-host.apk");
        writeApk(apk, entries("classes.dex", dex(largeClassDef(256, 128))));
        HostRule signatureRule = HostRule.builder("target_method", "download", HostRule.Kind.METHOD)
                .classDescriptor("Lfixture/Large;")
                .methodName("findTarget")
                .methodDescriptor("()V")
                .build();
        int[] analyzedBodies = {0};

        HostScanReport report = new HostScanner((classDescriptor, methodName) -> analyzedBodies[0]++)
                .scan(Collections.singletonList(apk), HostRuleSet.of(Collections.singletonList(signatureRule)));

        assertEquals(RuleScanResult.Status.UNIQUE, report.getResults().get("target_method").getStatus());
        assertEquals("findTarget", report.getResults().get("target_method").getCandidates().get(0)
                .getMethodName());
        assertEquals(0, analyzedBodies[0]);
    }

    @Test
    public void scansAllDexEntriesAndSplitsWithUniqueAndAmbiguousOutcomes() throws Exception {
        File baseApk = temporaryFolder.newFile("base.apk");
        File splitApk = temporaryFolder.newFile("feature.apk");
        writeApk(baseApk, entries(
                "classes.dex", dex(classDef("Lfixture/A;", "fetchVideo", "dl_source_primary",
                        "Lfixture/VideoApi;")),
                "classes10.dex", dex(classDef("Lfixture/B;", "fetchVideo", "other_selector",
                        "Lfixture/VideoApi;"))));
        writeApk(splitApk, entries("classes.dex", dex(classDef("Lfixture/C;", "handleImage",
                "download_image", "Lfixture/ImageApi;"))));

        HostRule unique = HostRule.builder("video_source", "download", HostRule.Kind.METHOD)
                .methodName("fetchVideo")
                .methodStrings(Collections.singletonList("dl_source_primary"))
                .invokeOwners(Collections.singletonList("Lfixture/VideoApi;"))
                .opcodes(Arrays.asList("const-string", "invoke-static"))
                .build();
        HostRule ambiguous = HostRule.builder("video_name", "download", HostRule.Kind.METHOD)
                .methodName("fetchVideo")
                .build();
        HostRule split = HostRule.builder("image_source", "download", HostRule.Kind.METHOD)
                .methodName("handleImage")
                .methodStrings(Collections.singletonList("download_image"))
                .invokeOwners(Collections.singletonList("Lfixture/ImageApi;"))
                .build();
        HostRule missing = HostRule.builder("missing", "filter", HostRule.Kind.METHOD)
                .methodStrings(Collections.singletonList("selector_not_present"))
                .build();
        HostRuleSet rules = HostRuleSet.of(Arrays.asList(unique, ambiguous, split, missing));

        HostScanReport report = new HostScanner().scan(Arrays.asList(baseApk, splitApk), rules);

        assertEquals(RuleScanResult.Status.UNIQUE, report.getResults().get("video_source").getStatus());
        assertEquals("Lfixture/A;", report.getResults().get("video_source").getCandidates().get(0)
                .getClassDescriptor());
        assertEquals(RuleScanResult.Status.AMBIGUOUS, report.getResults().get("video_name").getStatus());
        assertEquals(2, report.getResults().get("video_name").getCandidates().size());
        assertEquals(RuleScanResult.Status.UNIQUE, report.getResults().get("image_source").getStatus());
        assertEquals("feature.apk", report.getResults().get("image_source").getCandidates().get(0)
                .getArchiveName());
        assertEquals(RuleScanResult.Status.NOT_FOUND, report.getResults().get("missing").getStatus());
    }

    @Test
    public void callOwnerConflictPreventsFalseUniqueMatch() throws Exception {
        File apk = temporaryFolder.newFile("host.apk");
        writeApk(apk, entries("classes.dex", dex(classDef("Lfixture/A;", "fetchVideo",
                "dl_source_primary", "Lfixture/VideoApi;"))));
        HostRule conflicting = HostRule.builder("conflict", "download", HostRule.Kind.METHOD)
                .methodStrings(Collections.singletonList("dl_source_primary"))
                .invokeOwners(Collections.singletonList("Lfixture/WrongApi;"))
                .build();

        HostScanReport report = new HostScanner().scan(Collections.singletonList(apk),
                HostRuleSet.of(Collections.singletonList(conflicting)));

        assertEquals(RuleScanResult.Status.NOT_FOUND, report.getResults().get("conflict").getStatus());
    }

    @Test
    public void identityIncludesDexMetadataAndRuleFingerprint() throws Exception {
        File apk = temporaryFolder.newFile("host.apk");
        byte[] firstDex = dex(classDef("Lfixture/A;", "fetchVideo", "dl_source_primary",
                "Lfixture/VideoApi;"));
        writeApk(apk, entries("classes.dex", firstDex));
        HostRule rule = HostRule.builder("one", "download", HostRule.Kind.METHOD)
                .methodName("fetchVideo")
                .build();
        HostRuleSet firstRules = HostRuleSet.of(Collections.singletonList(rule));
        HostScanner scanner = new HostScanner();
        String firstIdentity = scanner.hostIdentity(Collections.singletonList(apk), firstRules);

        HostRule changedRule = HostRule.builder("one", "download", HostRule.Kind.METHOD)
                .methodName("differentMethod")
                .build();
        String changedRulesIdentity = scanner.hostIdentity(Collections.singletonList(apk),
                HostRuleSet.of(Collections.singletonList(changedRule)));
        writeApk(apk, entries("classes.dex", dex(classDef("Lfixture/A;", "fetchVideo",
                "dl_source_changed", "Lfixture/VideoApi;"))));
        String changedDexIdentity = scanner.hostIdentity(Collections.singletonList(apk), firstRules);

        assertNotEquals(firstIdentity, changedRulesIdentity);
        assertNotEquals(firstIdentity, changedDexIdentity);
        assertEquals(64, firstIdentity.length());
    }

    @Test
    public void scanProgressReportsEachDex() throws Exception {
        File apk = temporaryFolder.newFile("progress.apk");
        writeApk(apk, entries(
                "classes.dex", dex(classDef("Lfixture/A;", "fetchVideo", "one", "Lfixture/Api;")),
                "classes2.dex", dex(classDef("Lfixture/B;", "fetchImage", "two", "Lfixture/Api;"))));
        HostRule rule = HostRule.builder("target", "download", HostRule.Kind.METHOD)
                .methodName("fetchVideo").build();
        HostRuleSet rules = HostRuleSet.of(Collections.singletonList(rule));
        List<String> progress = new ArrayList<>();
        HostScanner.ProgressListener listener = new HostScanner.ProgressListener() {
            @Override public void onCacheHit(int dexCount) { progress.add("cache:" + dexCount); }
            @Override public void onScanStarted(int dexCount) { progress.add("start:" + dexCount); }
            @Override public void onDexScanned(int completed, int total, String dexName) {
                progress.add(completed + "/" + total + ":" + dexName);
            }
        };

        new HostScanner().scan(Collections.singletonList(apk), rules, listener);
        assertEquals(Arrays.asList("start:2", "1/2:progress.apk!classes.dex",
                "2/2:progress.apk!classes2.dex"), progress);
    }

    @Test
    public void cacheSaveFailureKeepsCompletedScanReportAndNotifiesListener() throws Exception {
        File apk = temporaryFolder.newFile("uncached-host.apk");
        writeApk(apk, entries("classes.dex", dex(classDef("Lfixture/A;", "fetchVideo",
                "dl_source_primary", "Lfixture/VideoApi;"))));
        File cacheParent = temporaryFolder.newFile("cache-parent");
        HostRule rule = HostRule.builder("video_source", "download", HostRule.Kind.METHOD)
                .methodName("fetchVideo").build();
        List<Exception> cacheFailures = new ArrayList<>();
        HostScanner.ProgressListener listener = new HostScanner.ProgressListener() {
            @Override public void onCacheHit(int dexCount) { }
            @Override public void onScanStarted(int dexCount) { }
            @Override public void onDexScanned(int completed, int total, String dexName) { }
            @Override public void onCacheSaveFailed(Exception failure) { cacheFailures.add(failure); }
        };

        HostScanReport report = new HostScanner().scanCached(Collections.singletonList(apk),
                HostRuleSet.of(Collections.singletonList(rule)),
                new HostScanCache(new File(cacheParent, "host-scan.cache")), listener);

        assertEquals(RuleScanResult.Status.UNIQUE, report.getResults().get("video_source").getStatus());
        assertEquals(1, cacheFailures.size());
        assertNotNull(cacheFailures.get(0));
    }

    @Test
    public void scanFailureStillPropagatesWhenCacheCannotBeSaved() throws Exception {
        File apk = temporaryFolder.newFile("invalid-host.apk");
        writeApk(apk, entries("classes.dex", new byte[8]));
        File cacheParent = temporaryFolder.newFile("invalid-cache-parent");
        HostRule rule = HostRule.builder("target", "download", HostRule.Kind.METHOD)
                .methodName("fetchVideo").build();

        IOException failure = assertThrows(IOException.class, () -> new HostScanner().scanCached(
                Collections.singletonList(apk), HostRuleSet.of(Collections.singletonList(rule)),
                new HostScanCache(new File(cacheParent, "host-scan.cache"))));

        assertTrue(failure.getMessage().contains("Unable to parse invalid-host.apk!classes.dex"));
    }

    @Test
    public void parsesVersionedRulesAndRejectsDuplicateIds() throws Exception {
        String tsv = "#douai-host-rules\t1\tcom.ss.android.ugc.aweme\n"
                + "id\tfeature\tkind\tclass_descriptor\tclass_strings\tmethod_name\tmethod_descriptor"
                + "\tmethod_strings\tinvoke_owners\tfield_owners\topcodes\texact_method_count"
                + "\texact_field_count\tfield_types\tfield_names\n"
                + "download\tmedia\tmethod\t\t\tfetch\t()V\tneedle|secondary\tLhost/Api;"
                + "\t\tconst-string|invoke-static\t\t\t\t\n";
        HostRuleSet parsed = HostRuleSet.parse(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, parsed.getFormatVersion());
        assertEquals("com.ss.android.ugc.aweme", parsed.getTargetPackage());
        assertEquals(1, parsed.getRules().size());
        assertEquals("secondary", parsed.getRules().get(0).getMethodStrings().get(1));

        String duplicate = tsv + "download\tmedia\tmethod\t\t\tfetch\t()V\tneedle"
                + "\tLhost/Api;\t\treturn-void\t\t\t\t\n";
        boolean rejected = false;
        try {
            HostRuleSet.parse(new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    private static ImmutableClassDef classDef(String descriptor, String methodName, String literal,
                                               String invokeOwner) {
        List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> instructions = Arrays.asList(
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0,
                        new ImmutableStringReference(literal)),
                new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                        new ImmutableMethodReference(invokeOwner, "fetch", Collections.emptyList(), "V")),
                new ImmutableInstruction10x(Opcode.RETURN_VOID));
        ImmutableMethodImplementation implementation = new ImmutableMethodImplementation(
                1, instructions, Collections.emptyList(), Collections.emptyList());
        ImmutableMethod method = new ImmutableMethod(descriptor, methodName, Collections.emptyList(), "V",
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), Collections.emptySet(),
                Collections.emptySet(), implementation);
        return new ImmutableClassDef(descriptor, AccessFlags.PUBLIC.getValue(), "Ljava/lang/Object;",
                Collections.emptyList(), null, Collections.emptySet(), Collections.emptyList(),
                Collections.singletonList(method));
    }

    private static ImmutableClassDef largeClassDef(int unrelatedMethodCount, int instructionsPerMethod) {
        String descriptor = "Lfixture/Large;";
        ArrayList<ImmutableMethod> methods = new ArrayList<>(unrelatedMethodCount + 1);
        ArrayList<com.android.tools.smali.dexlib2.iface.instruction.Instruction> unrelatedInstructions =
                new ArrayList<>(instructionsPerMethod + 1);
        for (int index = 0; index < instructionsPerMethod; index++) {
            unrelatedInstructions.add(new ImmutableInstruction10x(Opcode.NOP));
        }
        unrelatedInstructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        ImmutableMethodImplementation unrelatedBody = new ImmutableMethodImplementation(
                0, unrelatedInstructions, Collections.emptyList(), Collections.emptyList());
        for (int index = 0; index < unrelatedMethodCount; index++) {
            methods.add(new ImmutableMethod(descriptor, "unrelated" + index, Collections.emptyList(), "V",
                    AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), Collections.emptySet(),
                    Collections.emptySet(), unrelatedBody));
        }
        ImmutableMethod target = new ImmutableMethod(descriptor, "findTarget", Collections.emptyList(), "V",
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), Collections.emptySet(),
                Collections.emptySet(), new ImmutableMethodImplementation(0,
                        Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        Collections.emptyList(), Collections.emptyList()));
        methods.add(target);
        return new ImmutableClassDef(descriptor, AccessFlags.PUBLIC.getValue(), "Ljava/lang/Object;",
                Collections.emptyList(), null, Collections.emptySet(), Collections.emptyList(), methods);
    }

    private static byte[] dex(ImmutableClassDef... classes) throws IOException {
        MemoryDataStore dataStore = new MemoryDataStore();
        DexPool.writeTo(dataStore, new ImmutableDexFile(Opcodes.getDefault(), Arrays.asList(classes)));
        byte[] bytes = dataStore.getData();
        dataStore.close();
        return bytes;
    }

    private static Map<String, byte[]> entries(Object... values) {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], (byte[]) values[index + 1]);
        }
        return result;
    }

    private static void writeApk(File file, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new java.io.FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
