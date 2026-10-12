package com.douai.module.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class HostRule {
    public enum Kind { CLASS, METHOD }

    private final String id;
    private final String feature;
    private final Kind kind;
    private final String classDescriptor;
    private final List<String> classStrings;
    private final String methodName;
    private final String methodDescriptor;
    private final List<String> methodStrings;
    private final List<String> invokeOwners;
    private final List<String> fieldOwners;
    private final List<String> opcodes;
    private final int exactMethodCount;
    private final int exactFieldCount;
    private final List<String> fieldTypes;
    private final List<String> fieldNames;

    private HostRule(Builder builder) {
        id = requireText(builder.id, "id");
        feature = requireText(builder.feature, "feature");
        kind = Objects.requireNonNull(builder.kind, "kind");
        classDescriptor = emptyToNull(builder.classDescriptor);
        classStrings = immutable(builder.classStrings);
        methodName = emptyToNull(builder.methodName);
        methodDescriptor = emptyToNull(builder.methodDescriptor);
        methodStrings = immutable(builder.methodStrings);
        invokeOwners = immutable(builder.invokeOwners);
        fieldOwners = immutable(builder.fieldOwners);
        opcodes = immutable(builder.opcodes);
        exactMethodCount = builder.exactMethodCount;
        exactFieldCount = builder.exactFieldCount;
        fieldTypes = immutable(builder.fieldTypes);
        fieldNames = immutable(builder.fieldNames);

        if (exactMethodCount < -1 || exactFieldCount < -1) {
            throw new IllegalArgumentException("Exact counts must be -1 or non-negative");
        }
        if (kind == Kind.CLASS && classDescriptor == null && classStrings.isEmpty()
                && exactMethodCount < 0 && exactFieldCount < 0 && fieldTypes.isEmpty()
                && fieldNames.isEmpty()) {
            throw new IllegalArgumentException("Class rules need at least one class-level selector");
        }
        if (kind == Kind.METHOD && methodName == null && methodDescriptor == null
                && methodStrings.isEmpty() && invokeOwners.isEmpty() && fieldOwners.isEmpty()
                && opcodes.isEmpty()) {
            throw new IllegalArgumentException("Method rules need at least one method-level selector");
        }
    }

    public static Builder builder(String id, String feature, Kind kind) {
        return new Builder(id, feature, kind);
    }

    public String getId() { return id; }
    public String getFeature() { return feature; }
    public Kind getKind() { return kind; }
    public String getClassDescriptor() { return classDescriptor; }
    public List<String> getClassStrings() { return classStrings; }
    public String getMethodName() { return methodName; }
    public String getMethodDescriptor() { return methodDescriptor; }
    public List<String> getMethodStrings() { return methodStrings; }
    public List<String> getInvokeOwners() { return invokeOwners; }
    public List<String> getFieldOwners() { return fieldOwners; }
    public List<String> getOpcodes() { return opcodes; }
    public int getExactMethodCount() { return exactMethodCount; }
    public int getExactFieldCount() { return exactFieldCount; }
    public List<String> getFieldTypes() { return fieldTypes; }
    public List<String> getFieldNames() { return fieldNames; }

    String canonicalForm() {
        return id + '\t' + feature + '\t' + kind.name() + '\t'
                + nullToEmpty(classDescriptor) + '\t' + join(classStrings) + '\t'
                + nullToEmpty(methodName) + '\t' + nullToEmpty(methodDescriptor) + '\t'
                + join(methodStrings) + '\t' + join(invokeOwners) + '\t' + join(fieldOwners) + '\t'
                + join(opcodes) + '\t' + exactMethodCount + '\t' + exactFieldCount + '\t'
                + join(fieldTypes) + '\t' + join(fieldNames);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append('|');
            result.append(value.replace("\\", "\\\\").replace("|", "\\|"));
        }
        return result.toString();
    }

    public static final class Builder {
        private final String id;
        private final String feature;
        private final Kind kind;
        private String classDescriptor;
        private final List<String> classStrings = new ArrayList<>();
        private String methodName;
        private String methodDescriptor;
        private final List<String> methodStrings = new ArrayList<>();
        private final List<String> invokeOwners = new ArrayList<>();
        private final List<String> fieldOwners = new ArrayList<>();
        private final List<String> opcodes = new ArrayList<>();
        private int exactMethodCount = -1;
        private int exactFieldCount = -1;
        private final List<String> fieldTypes = new ArrayList<>();
        private final List<String> fieldNames = new ArrayList<>();

        private Builder(String id, String feature, Kind kind) {
            this.id = id;
            this.feature = feature;
            this.kind = kind;
        }

        public Builder classDescriptor(String value) { classDescriptor = value; return this; }
        public Builder classStrings(List<String> values) { classStrings.addAll(values); return this; }
        public Builder methodName(String value) { methodName = value; return this; }
        public Builder methodDescriptor(String value) { methodDescriptor = value; return this; }
        public Builder methodStrings(List<String> values) { methodStrings.addAll(values); return this; }
        public Builder invokeOwners(List<String> values) { invokeOwners.addAll(values); return this; }
        public Builder fieldOwners(List<String> values) { fieldOwners.addAll(values); return this; }
        public Builder opcodes(List<String> values) { opcodes.addAll(values); return this; }
        public Builder exactMethodCount(int value) { exactMethodCount = value; return this; }
        public Builder exactFieldCount(int value) { exactFieldCount = value; return this; }
        public Builder fieldTypes(List<String> values) { fieldTypes.addAll(values); return this; }
        public Builder fieldNames(List<String> values) { fieldNames.addAll(values); return this; }
        public HostRule build() { return new HostRule(this); }
    }
}
