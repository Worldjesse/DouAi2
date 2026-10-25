package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class HostScanner {
    private static final String DEX_PATTERN = "classes(?:[2-9]|[1-9][0-9]+)?\\.dex";
    private static final int MAX_DEX_BYTES = 256 * 1024 * 1024;
    // Bump when matching/decoding semantics change. Unrelated module version changes are not a scan input.
    private static final String SCANNER_SCHEMA = "douai-host-identity-v2\n";
    private static final DecodeObserver NO_OP_DECODE_OBSERVER = (classDescriptor, methodName) -> { };
    private final DecodeObserver decodeObserver;
    private final ArchiveInspectionObserver inspectionObserver;

    public HostScanner() {
        this(NO_OP_DECODE_OBSERVER);
    }

    HostScanner(DecodeObserver decodeObserver) {
        this(decodeObserver, dexCount -> { });
    }

    HostScanner(DecodeObserver decodeObserver, ArchiveInspectionObserver inspectionObserver) {
        this.decodeObserver = decodeObserver == null ? NO_OP_DECODE_OBSERVER : decodeObserver;
        this.inspectionObserver = inspectionObserver == null ? dexCount -> { } : inspectionObserver;
    }

    public HostScanReport scan(List<File> apkArchives, HostRuleSet rules) throws IOException {
        return scan(apkArchives, rules, null);
    }

    public HostScanReport scan(List<File> apkArchives, HostRuleSet rules,
                               ProgressListener progressListener) throws IOException {
        List<ArchiveDex> dexEntries = inspectArchives(apkArchives);
        if (dexEntries.isEmpty()) throw new IOException("No classes*.dex entries found in host APK archives");
        String identity = createIdentity(dexEntries, rules);
        notifyScanStarted(progressListener, dexEntries.size());
        return scanEntries(dexEntries, rules, identity, progressListener);
    }

    public HostScanReport scanCached(List<File> apkArchives, HostRuleSet rules,
                                     HostScanCache cache) throws IOException {
        return scanCached(apkArchives, rules, cache, null);
    }

    public HostScanReport scanCached(List<File> apkArchives, HostRuleSet rules,
                                     HostScanCache cache, ProgressListener progressListener)
            throws IOException {
        return scanCached(apkArchives, rules, cache, progressListener, "");
    }

    public HostScanReport scanCached(List<File> apkArchives, HostRuleSet rules,
                                     HostScanCache cache, ProgressListener progressListener,
                                     String installationIdentity) throws IOException {
        return scanCached(prepare(apkArchives, rules, installationIdentity), cache, progressListener);
    }

    /** Prepares one verified DEX inventory for both early cache validation and the background scan. */
    public PreparedScan prepare(List<File> apkArchives, HostRuleSet rules) throws IOException {
        return prepare(apkArchives, rules, "");
    }

    PreparedScan prepare(List<File> apkArchives, HostRuleSet rules, String installationIdentity) throws IOException {
        String before = HostInstallation.hostIdentity(apkArchives);
        List<ArchiveDex> dexEntries = inspectArchives(apkArchives);
        if (dexEntries.isEmpty()) throw new IOException("No classes*.dex entries found in host APK archives");
        if (!before.equals(HostInstallation.hostIdentity(apkArchives))) {
            throw new IOException("Host installation changed while preparing DEX inventory");
        }
        return new PreparedScan(apkArchives, rules, dexEntries,
                createIdentity(dexEntries, rules, installationIdentity), before, installationIdentity);
    }

    public HostScanReport scanCached(PreparedScan prepared, HostScanCache cache,
                                     ProgressListener progressListener) throws IOException {
        if (prepared == null) throw new IllegalArgumentException("Prepared scan is required");
        if (!prepared.isCurrent()) prepared = prepare(prepared.archives, prepared.rules, prepared.installationIdentity);
        List<ArchiveDex> dexEntries = prepared.dexEntries;
        HostRuleSet rules = prepared.rules;
        String identity = prepared.identity;
        HostScanReport reusable = prepared.reusableReport;
        if (reusable != null) {
            notifyCacheHit(progressListener, dexEntries.size());
            return reusable;
        }
        if (cache != null) {
            try {
                HostScanReport cached = prepared.loadCached(cache);
                if (cached != null) {
                    notifyCacheHit(progressListener, dexEntries.size());
                    return cached;
                }
            } catch (IOException | RuntimeException exception) {
                try {
                    cache.delete();
                } catch (RuntimeException ignored) {
                    // A broken cache must not prevent a fresh host scan.
                }
            }
        }
        if (!prepared.isCurrent()) throw new IOException("Host installation changed while validating scan cache");
        notifyScanStarted(progressListener, dexEntries.size());
        HostScanReport report = scanEntries(dexEntries, rules, identity, progressListener);
        if (!prepared.isCurrent()) throw new IOException("Host installation changed during DEX scan");
        if (cache != null) {
            try {
                cache.save(report);
            } catch (IOException | RuntimeException exception) {
                notifyCacheSaveFailed(progressListener, exception);
            }
        }
        prepared.rememberVerified(report);
        return report;
    }

    public String hostIdentity(List<File> apkArchives, HostRuleSet rules) throws IOException {
        return hostIdentity(apkArchives, rules, "");
    }

    public String hostIdentity(List<File> apkArchives, HostRuleSet rules,
                               String installationIdentity) throws IOException {
        List<ArchiveDex> dexEntries = inspectArchives(apkArchives);
        if (dexEntries.isEmpty()) throw new IOException("No classes*.dex entries found in host APK archives");
        return createIdentity(dexEntries, rules, installationIdentity);
    }

    private List<ArchiveDex> inspectArchives(List<File> archives) throws IOException {
        List<ArchiveDex> entries = collectDexEntries(archives);
        inspectionObserver.onArchivesInspected(entries.size());
        return entries;
    }

    public static final class PreparedScan {
        private final List<File> archives;
        private final HostRuleSet rules;
        private final List<ArchiveDex> dexEntries;
        private final String identity;
        private final String hostInstallation;
        private final String installationIdentity;
        private final Object cacheValidationLock = new Object();
        private boolean cacheChecked;
        private volatile HostScanReport reusableReport;

        private PreparedScan(List<File> archives, HostRuleSet rules, List<ArchiveDex> dexEntries,
                             String identity, String hostInstallation, String installationIdentity) {
            this.archives = Collections.unmodifiableList(new ArrayList<>(archives));
            this.rules = rules;
            this.dexEntries = Collections.unmodifiableList(new ArrayList<>(dexEntries));
            this.identity = identity;
            this.hostInstallation = hostInstallation;
            this.installationIdentity = installationIdentity;
        }

        public String getIdentity() { return identity; }

        public boolean matches(List<File> archives, HostRuleSet rules) throws IOException {
            return this.rules.getFingerprint().equals(rules.getFingerprint())
                    && hostInstallation.equals(HostInstallation.hostIdentity(archives));
        }

        boolean isCurrent() throws IOException { return hostInstallation.equals(HostInstallation.hostIdentity(archives)); }
        HostRuleSet rules() { return rules; }

        HostScanReport loadCached(HostScanCache cache) throws IOException {
            // The startup and scanner threads share this short I/O section, never a full DEX scan.
            synchronized (cacheValidationLock) {
                if (reusableReport != null) return reusableReport;
                if (cacheChecked) return null;
                try {
                    HostScanReport cached = cache.load(identity);
                    if (!StartupHostCache.matchesRules(cached, rules) || !isCurrent()) return null;
                    rememberVerified(cached);
                    return reusableReport;
                } catch (IOException | RuntimeException corrupt) {
                    // AtomicFile can retain a corrupt backup. Remove every cache generation before
                    // the background scan writes its replacement, even when startup observed the error first.
                    try { cache.delete(); } catch (RuntimeException ignored) { }
                    throw corrupt;
                } finally { cacheChecked = true; }
            }
        }

        void rememberVerified(HostScanReport report) {
            if (!identity.equals(report.getHostIdentity()) || !StartupHostCache.matchesRules(report, rules)) {
                throw new IllegalArgumentException("Completed scan does not match its prepared inputs");
            }
            synchronized (cacheValidationLock) {
                reusableReport = report.isCacheHit() ? report
                        : new HostScanReport(identity, report.getRuleResults(), true);
                cacheChecked = true;
            }
        }
    }

    interface ArchiveInspectionObserver { void onArchivesInspected(int dexCount); }

    private HostScanReport scanEntries(List<ArchiveDex> dexEntries, HostRuleSet rules,
                                       String identity, ProgressListener progressListener)
            throws IOException {
        if (rules.getRules().isEmpty()) {
            return new HostScanReport(identity, Collections.emptyList(), false);
        }
        ArrayList<ArrayList<HostCandidate>> candidates = new ArrayList<>(rules.getRules().size());
        for (int index = 0; index < rules.getRules().size(); index++) candidates.add(new ArrayList<>());
        List<HostRule> hostRules = rules.getRules();
        int completedDexFiles = 0;
        for (ArchiveDex dexEntry : dexEntries) {
            byte[] bytes = readDex(dexEntry);
            DexBackedDexFile dex;
            try {
                dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), new ByteArrayInputStream(bytes));
            } catch (RuntimeException exception) {
                throw new IOException("Unable to parse " + dexEntry.archiveName + "!" + dexEntry.entryName, exception);
            }
            for (ClassDef classDef : dex.getClasses()) {
                String classDescriptor = classDef.getType();
                if (!hasPotentialDescriptorMatch(hostRules, classDescriptor)) continue;
                ClassFacts classFacts = ClassFacts.from(classDef, decodeObserver);
                for (int ruleIndex = 0; ruleIndex < hostRules.size(); ruleIndex++) {
                    HostRule rule = hostRules.get(ruleIndex);
                    if (!matchesClassStructure(rule, classFacts)) continue;
                    if (!matchesClassStrings(rule, classFacts)) continue;
                    if (rule.getKind() == HostRule.Kind.CLASS) {
                        candidates.get(ruleIndex).add(new HostCandidate(dexEntry.archiveName,
                                dexEntry.entryName, classDef.getType(), "", ""));
                    } else {
                        for (MethodInfo methodInfo : classFacts.methods) {
                            if (!matchesMethodSignature(rule, methodInfo)) continue;
                            if (requiresMethodCode(rule)
                                    && !matchesMethodCode(rule, classFacts.analyze(methodInfo))) {
                                continue;
                            }
                            candidates.get(ruleIndex).add(new HostCandidate(dexEntry.archiveName,
                                    dexEntry.entryName, classDef.getType(), methodInfo.name,
                                    methodInfo.descriptor));
                        }
                    }
                }
            }
            completedDexFiles++;
            notifyDexScanned(progressListener, completedDexFiles, dexEntries.size(),
                    dexEntry.archiveName + "!" + dexEntry.entryName);
        }
        ArrayList<RuleScanResult> results = new ArrayList<>(rules.getRules().size());
        for (int index = 0; index < rules.getRules().size(); index++) {
            HostRule rule = rules.getRules().get(index);
            ArrayList<HostCandidate> matches = candidates.get(index);
            Collections.sort(matches, CANDIDATE_ORDER);
            results.add(new RuleScanResult(rule.getId(), rule.getFeature(), matches));
        }
        return new HostScanReport(identity, results, false);
    }

    private static void notifyCacheHit(ProgressListener listener, int dexCount) {
        if (listener == null) return;
        try {
            listener.onCacheHit(dexCount);
        } catch (RuntimeException ignored) {
            // UI progress must not affect the host scan.
        }
    }

    private static void notifyScanStarted(ProgressListener listener, int dexCount) {
        if (listener == null) return;
        try {
            listener.onScanStarted(dexCount);
        } catch (RuntimeException ignored) {
            // UI progress must not affect the host scan.
        }
    }

    private static void notifyDexScanned(ProgressListener listener, int completed, int total,
                                         String dexName) {
        if (listener == null) return;
        try {
            listener.onDexScanned(completed, total, dexName);
        } catch (RuntimeException ignored) {
            // UI progress must not affect the host scan.
        }
    }

    private static void notifyCacheSaveFailed(ProgressListener listener, Exception exception) {
        if (listener == null) return;
        try {
            listener.onCacheSaveFailed(exception);
        } catch (RuntimeException ignored) {
            // UI diagnostics must not affect the completed host scan.
        }
    }

    private static boolean hasPotentialDescriptorMatch(List<HostRule> rules, String classDescriptor) {
        for (HostRule rule : rules) {
            if (rule.getClassDescriptor() == null || rule.getClassDescriptor().equals(classDescriptor)) return true;
        }
        return false;
    }

    private static boolean matchesClassStructure(HostRule rule, ClassFacts facts) {
        if (rule.getClassDescriptor() != null && !rule.getClassDescriptor().equals(facts.descriptor)) return false;
        if (rule.getExactMethodCount() >= 0 && rule.getExactMethodCount() != facts.methods.size()) return false;
        if (rule.getExactFieldCount() >= 0 && rule.getExactFieldCount() != facts.fieldCount) return false;
        if (!facts.fieldTypes.containsAll(rule.getFieldTypes())) return false;
        return facts.fieldNames.containsAll(rule.getFieldNames());
    }

    private static boolean matchesClassStrings(HostRule rule, ClassFacts facts) {
        return rule.getClassStrings().isEmpty()
                || facts.classStrings().containsAll(rule.getClassStrings());
    }

    private static boolean matchesMethodSignature(HostRule rule, MethodInfo method) {
        if (rule.getMethodName() != null && !rule.getMethodName().equals(method.name)) return false;
        return rule.getMethodDescriptor() == null || rule.getMethodDescriptor().equals(method.descriptor);
    }

    private static boolean requiresMethodCode(HostRule rule) {
        return !rule.getMethodStrings().isEmpty() || !rule.getInvokeOwners().isEmpty()
                || !rule.getFieldOwners().isEmpty() || !rule.getOpcodes().isEmpty();
    }

    private static boolean matchesMethodCode(HostRule rule, MethodFacts facts) {
        if (!facts.strings.containsAll(rule.getMethodStrings())) return false;
        if (!facts.invokeOwners.containsAll(rule.getInvokeOwners())) return false;
        if (!facts.fieldOwners.containsAll(rule.getFieldOwners())) return false;
        return containsSequence(facts.opcodes, rule.getOpcodes());
    }

    private static boolean containsSequence(List<String> values, List<String> sequence) {
        if (sequence.isEmpty()) return true;
        if (sequence.size() > values.size()) return false;
        for (int start = 0; start <= values.size() - sequence.size(); start++) {
            boolean match = true;
            for (int offset = 0; offset < sequence.size(); offset++) {
                if (!values.get(start + offset).equalsIgnoreCase(sequence.get(offset))) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static List<ArchiveDex> collectDexEntries(List<File> apkArchives) throws IOException {
        if (apkArchives == null || apkArchives.isEmpty()) throw new IOException("No host APK archives supplied");
        ArrayList<File> archives = new ArrayList<>(apkArchives);
        for (File archive : archives) {
            if (archive == null || !archive.isFile()) throw new IOException("Host APK is missing: " + archive);
        }
        Collections.sort(archives, Comparator.comparing(File::getName));
        ArrayList<ArchiveDex> entries = new ArrayList<>();
        for (File archive : archives) {
            try (ZipFile zip = new ZipFile(archive)) {
                ArrayList<ZipEntry> dexFiles = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (entry.getName().matches(DEX_PATTERN) && !entry.isDirectory()) dexFiles.add(entry);
                }
                Collections.sort(dexFiles, (left, right) -> Integer.compare(
                        dexIndex(left.getName()), dexIndex(right.getName())));
                for (ZipEntry entry : dexFiles) {
                    byte[] header = readHeader(zip.getInputStream(entry));
                    if (header.length < 8) throw new IOException("Truncated DEX header: " + entry.getName());
                    entries.add(new ArchiveDex(archive.getName(), archive, entry.getName(),
                            entry.getSize(), entry.getCrc(), header));
                }
            } catch (IOException exception) {
                throw new IOException("Unable to inspect APK archive " + archive, exception);
            }
        }
        Collections.sort(entries, Comparator.comparing((ArchiveDex value) -> value.archiveName)
                .thenComparingInt(value -> dexIndex(value.entryName)));
        return entries;
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        try (InputStream stream = input) {
            byte[] header = new byte[8];
            int offset = 0;
            while (offset < header.length) {
                int count = stream.read(header, offset, header.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return offset == header.length ? header : Arrays.copyOf(header, offset);
        }
    }

    private static int dexIndex(String entryName) {
        if ("classes.dex".equals(entryName)) return 1;
        int dot = entryName.indexOf('.');
        try {
            return Integer.parseInt(entryName.substring("classes".length(), dot));
        } catch (RuntimeException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static String createIdentity(List<ArchiveDex> dexEntries, HostRuleSet rules) {
        return createIdentity(dexEntries, rules, "");
    }

    private static String createIdentity(List<ArchiveDex> dexEntries, HostRuleSet rules,
                                         String installationIdentity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, SCANNER_SCHEMA);
            update(digest, rules.getTargetPackage() + "\n");
            update(digest, "rules-sha256=" + rules.getFingerprint() + "\n");
            if (installationIdentity != null && !installationIdentity.isEmpty()) {
                update(digest, "installation=" + installationIdentity + "\n");
            }
            for (ArchiveDex entry : dexEntries) {
                update(digest, entry.archiveName + "!" + entry.entryName + "|" + entry.size + "|"
                        + entry.crc + "|" + toHex(entry.header) + "\n");
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] readDex(ArchiveDex entry) throws IOException {
        if (entry.size > MAX_DEX_BYTES) throw new IOException("DEX is larger than scanner limit: " + entry.entryName);
        try (ZipFile zip = new ZipFile(entry.archive);
             InputStream input = zip.getInputStream(zip.getEntry(entry.entryName))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(entry.size, 8192));
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_DEX_BYTES) throw new IOException("DEX is larger than scanner limit: " + entry.entryName);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IOException("Unable to read " + entry.archiveName + "!" + entry.entryName, exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static final Comparator<HostCandidate> CANDIDATE_ORDER =
            Comparator.comparing(HostCandidate::getArchiveName)
                    .thenComparing(HostCandidate::getDexEntry)
                    .thenComparing(HostCandidate::getClassDescriptor)
                    .thenComparing(HostCandidate::getMethodName)
                    .thenComparing(HostCandidate::getMethodDescriptor);

    private static final class ArchiveDex {
        final String archiveName;
        final File archive;
        final String entryName;
        final long size;
        final long crc;
        final byte[] header;

        ArchiveDex(String archiveName, File archive, String entryName, long size, long crc, byte[] header) {
            this.archiveName = archiveName;
            this.archive = archive;
            this.entryName = entryName;
            this.size = size;
            this.crc = crc;
            this.header = header;
        }
    }

    private static final class ClassFacts {
        final String descriptor;
        final int fieldCount;
        final Set<String> fieldTypes;
        final Set<String> fieldNames;
        final List<MethodInfo> methods = new ArrayList<>();
        private final Set<String> fieldStrings = new HashSet<>();
        private final DecodeObserver decodeObserver;
        private final java.util.Map<MethodInfo, MethodFacts> decodedMethods = new java.util.IdentityHashMap<>();
        private Set<String> classStrings;

        private ClassFacts(ClassDef classDef, DecodeObserver decodeObserver) {
            descriptor = classDef.getType();
            this.decodeObserver = decodeObserver;
            fieldTypes = new HashSet<>();
            fieldNames = new HashSet<>();
            int fields = 0;
            for (Field field : classDef.getFields()) {
                fields++;
                fieldTypes.add(field.getType());
                fieldNames.add(field.getName());
                EncodedValue initialValue = field.getInitialValue();
                if (initialValue instanceof StringEncodedValue) {
                    fieldStrings.add(((StringEncodedValue) initialValue).getValue());
                }
            }
            fieldCount = fields;
            for (Method method : classDef.getMethods()) {
                methods.add(new MethodInfo(method));
            }
        }

        static ClassFacts from(ClassDef classDef, DecodeObserver decodeObserver) {
            return new ClassFacts(classDef, decodeObserver);
        }

        MethodFacts analyze(MethodInfo method) {
            MethodFacts facts = decodedMethods.get(method);
            if (facts == null) {
                decodeObserver.onMethodBodyAnalyzed(descriptor, method.name);
                facts = MethodFacts.from(method.method);
                decodedMethods.put(method, facts);
            }
            return facts;
        }

        Set<String> classStrings() {
            if (classStrings == null) {
                classStrings = new HashSet<>(fieldStrings);
                for (MethodInfo method : methods) classStrings.addAll(analyze(method).strings);
            }
            return classStrings;
        }
    }

    private static final class MethodInfo {
        final Method method;
        final String name;
        final String descriptor;

        MethodInfo(Method method) {
            this.method = method;
            name = method.getName();
            descriptor = MethodFacts.methodDescriptor(method);
        }
    }

    private static final class MethodFacts {
        final String name;
        final String descriptor;
        final Set<String> strings = new HashSet<>();
        final Set<String> invokeOwners = new HashSet<>();
        final Set<String> fieldOwners = new HashSet<>();
        final List<String> opcodes = new ArrayList<>();

        private MethodFacts(Method method) {
            name = method.getName();
            descriptor = methodDescriptor(method);
            MethodImplementation implementation = method.getImplementation();
            if (implementation == null) return;
            for (Instruction instruction : implementation.getInstructions()) {
                opcodes.add(instruction.getOpcode().name);
                if (!(instruction instanceof ReferenceInstruction)) continue;
                Reference reference = ((ReferenceInstruction) instruction).getReference();
                if (reference instanceof StringReference) {
                    strings.add(((StringReference) reference).getString());
                } else if (reference instanceof MethodReference) {
                    invokeOwners.add(((MethodReference) reference).getDefiningClass());
                } else if (reference instanceof FieldReference) {
                    fieldOwners.add(((FieldReference) reference).getDefiningClass());
                }
            }
        }

        static MethodFacts from(Method method) { return new MethodFacts(method); }

        static String methodDescriptor(Method method) {
            StringBuilder descriptor = new StringBuilder("(");
            for (CharSequence parameter : method.getParameterTypes()) descriptor.append(parameter);
            return descriptor.append(')').append(method.getReturnType()).toString();
        }
    }

    interface DecodeObserver {
        void onMethodBodyAnalyzed(String classDescriptor, String methodName);
    }

    public interface ProgressListener {
        void onCacheHit(int dexCount);
        void onScanStarted(int dexCount);
        void onDexScanned(int completed, int total, String dexName);
        default void onCacheSaveFailed(Exception exception) { }
    }
}
