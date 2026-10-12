package com.douai.module.scan;

import java.util.Objects;

public final class HostCandidate {
    private final String archiveName;
    private final String dexEntry;
    private final String classDescriptor;
    private final String methodName;
    private final String methodDescriptor;

    public HostCandidate(String archiveName, String dexEntry, String classDescriptor,
                         String methodName, String methodDescriptor) {
        this.archiveName = Objects.requireNonNull(archiveName);
        this.dexEntry = Objects.requireNonNull(dexEntry);
        this.classDescriptor = Objects.requireNonNull(classDescriptor);
        this.methodName = methodName == null ? "" : methodName;
        this.methodDescriptor = methodDescriptor == null ? "" : methodDescriptor;
    }

    public String getArchiveName() { return archiveName; }
    public String getDexEntry() { return dexEntry; }
    public String getClassDescriptor() { return classDescriptor; }
    public String getMethodName() { return methodName; }
    public String getMethodDescriptor() { return methodDescriptor; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HostCandidate)) return false;
        HostCandidate candidate = (HostCandidate) other;
        return archiveName.equals(candidate.archiveName)
                && dexEntry.equals(candidate.dexEntry)
                && classDescriptor.equals(candidate.classDescriptor)
                && methodName.equals(candidate.methodName)
                && methodDescriptor.equals(candidate.methodDescriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(archiveName, dexEntry, classDescriptor, methodName, methodDescriptor);
    }

    @Override
    public String toString() {
        return archiveName + "!" + dexEntry + ":" + classDescriptor
                + (methodName.isEmpty() ? "" : "->" + methodName + methodDescriptor);
    }
}
