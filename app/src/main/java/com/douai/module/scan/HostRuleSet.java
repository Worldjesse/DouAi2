package com.douai.module.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HostRuleSet {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final String EXPECTED_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String HEADER = "id\tfeature\tkind\tclass_descriptor\tclass_strings"
            + "\tmethod_name\tmethod_descriptor\tmethod_strings\tinvoke_owners\tfield_owners\topcodes"
            + "\texact_method_count\texact_field_count\tfield_types\tfield_names";

    private final int formatVersion;
    private final String targetPackage;
    private final List<HostRule> rules;
    private final String fingerprint;

    private HostRuleSet(int formatVersion, String targetPackage, List<HostRule> rules) {
        this.formatVersion = formatVersion;
        this.targetPackage = targetPackage;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.fingerprint = computeFingerprint();
    }

    public static HostRuleSet parse(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input is required");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String metadata = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty() && !line.startsWith("#")) {
                    metadata = line;
                    break;
                }
                if (line.startsWith("#douai-host-rules\t")) {
                    metadata = line.substring(1);
                    break;
                }
            }
            if (metadata == null) throw new IOException("Missing ruleset metadata");
            String[] metadataParts = metadata.split("\t", -1);
            if (metadataParts.length != 3 || !"douai-host-rules".equals(metadataParts[0])) {
                throw new IOException("Invalid ruleset metadata");
            }
            int version = parseInt(metadataParts[1], "ruleset version");
            if (version != CURRENT_FORMAT_VERSION) {
                throw new IOException("Unsupported ruleset version: " + version);
            }
            String targetPackage = metadataParts[2];
            if (!EXPECTED_PACKAGE.equals(targetPackage)) {
                throw new IOException("Ruleset package mismatch: " + targetPackage);
            }

            String header = null;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty() && !line.startsWith("#")) {
                    header = line;
                    break;
                }
            }
            if (!HEADER.equals(header)) throw new IOException("Invalid rules TSV header");

            ArrayList<HostRule> rules = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int lineNumber = 2;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                try {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 15) throw new IllegalArgumentException("expected 15 columns");
                    HostRule.Kind kind = HostRule.Kind.valueOf(fields[2].toUpperCase(Locale.ROOT));
                    HostRule rule = HostRule.builder(fields[0], fields[1], kind)
                            .classDescriptor(fields[3])
                            .classStrings(parseValues(fields[4]))
                            .methodName(fields[5])
                            .methodDescriptor(fields[6])
                            .methodStrings(parseValues(fields[7]))
                            .invokeOwners(parseValues(fields[8]))
                            .fieldOwners(parseValues(fields[9]))
                            .opcodes(parseValues(fields[10]))
                            .exactMethodCount(parseOptionalInt(fields[11]))
                            .exactFieldCount(parseOptionalInt(fields[12]))
                            .fieldTypes(parseValues(fields[13]))
                            .fieldNames(parseValues(fields[14]))
                            .build();
                    if (!ids.add(rule.getId())) throw new IllegalArgumentException("duplicate rule id");
                    rules.add(rule);
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid rule at line " + lineNumber + ": "
                            + exception.getMessage(), exception);
                }
            }
            return new HostRuleSet(version, targetPackage, rules);
        }
    }

    public static HostRuleSet of(List<HostRule> rules) {
        Set<String> ids = new HashSet<>();
        for (HostRule rule : rules) {
            if (!ids.add(rule.getId())) throw new IllegalArgumentException("duplicate rule id: " + rule.getId());
        }
        return new HostRuleSet(CURRENT_FORMAT_VERSION, EXPECTED_PACKAGE, rules);
    }

    public int getFormatVersion() { return formatVersion; }
    public String getTargetPackage() { return targetPackage; }
    public List<HostRule> getRules() { return rules; }
    public String getFingerprint() { return fingerprint; }

    private String computeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, formatVersion + "\n" + targetPackage + "\n");
            for (HostRule rule : rules) update(digest, rule.canonicalForm() + "\n");
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> parseValues(String value) {
        ArrayList<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (escaped) {
                current.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '|') {
                if (current.length() > 0) values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (escaped) current.append('\\');
        if (current.length() > 0) values.add(current.toString());
        return values;
    }

    private static int parseOptionalInt(String value) {
        return value.isEmpty() ? -1 : parseInt(value, "count");
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid " + name + ": " + value, exception);
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
}
