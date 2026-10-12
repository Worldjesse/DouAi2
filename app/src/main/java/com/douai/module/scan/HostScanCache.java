package com.douai.module.scan;

import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public final class HostScanCache {
    private static final int MAGIC = 0x44414953;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_RULES = 10_000;
    private static final int MAX_CANDIDATES_PER_RULE = 100_000;
    private final AtomicFile file;

    public HostScanCache(File path) {
        file = new AtomicFile(path);
    }

    public HostScanReport load(String expectedIdentity) throws IOException {
        File baseFile = file.getBaseFile();
        if (!baseFile.isFile()) return null;
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null;
            String identity = input.readUTF();
            if (!expectedIdentity.equals(identity)) return null;
            int resultCount = input.readInt();
            if (resultCount < 0 || resultCount > MAX_RULES) throw new IOException("Invalid rule count");
            ArrayList<RuleScanResult> results = new ArrayList<>(resultCount);
            for (int resultIndex = 0; resultIndex < resultCount; resultIndex++) {
                String ruleId = input.readUTF();
                String feature = input.readUTF();
                int statusOrdinal = input.readInt();
                int candidateCount = input.readInt();
                if (statusOrdinal < 0 || statusOrdinal >= RuleScanResult.Status.values().length
                        || candidateCount < 0 || candidateCount > MAX_CANDIDATES_PER_RULE) {
                    throw new IOException("Invalid cached rule result");
                }
                ArrayList<HostCandidate> candidates = new ArrayList<>(candidateCount);
                for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                    candidates.add(new HostCandidate(input.readUTF(), input.readUTF(), input.readUTF(),
                            input.readUTF(), input.readUTF()));
                }
                RuleScanResult result = new RuleScanResult(ruleId, feature, candidates);
                if (result.getStatus().ordinal() != statusOrdinal) {
                    throw new IOException("Cached status does not match candidate count");
                }
                results.add(result);
            }
            if (input.read() != -1) throw new IOException("Unexpected trailing cache data");
            return new HostScanReport(identity, results, true);
        }
    }

    public void save(HostScanReport report) throws IOException {
        File baseFile = file.getBaseFile();
        File parent = baseFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create cache directory: " + parent);
        }
        FileOutputStream raw = file.startWrite();
        try {
            DataOutputStream output = new DataOutputStream(raw);
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeUTF(report.getHostIdentity());
            output.writeInt(report.getRuleResults().size());
            for (RuleScanResult result : report.getRuleResults()) {
                output.writeUTF(result.getRuleId());
                output.writeUTF(result.getFeature());
                output.writeInt(result.getStatus().ordinal());
                output.writeInt(result.getCandidates().size());
                for (HostCandidate candidate : result.getCandidates()) {
                    output.writeUTF(candidate.getArchiveName());
                    output.writeUTF(candidate.getDexEntry());
                    output.writeUTF(candidate.getClassDescriptor());
                    output.writeUTF(candidate.getMethodName());
                    output.writeUTF(candidate.getMethodDescriptor());
                }
            }
            output.flush();
            file.finishWrite(raw);
        } catch (IOException | RuntimeException exception) {
            file.failWrite(raw);
            throw exception;
        }
    }

    public void delete() {
        file.delete();
    }
}
