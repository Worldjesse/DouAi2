package com.douai.module.diagnostics;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/** Business evidence, distinct from hook invocation diagnostics. No host objects are retained. */
public final class DouAiLog {
    public enum Outcome { SUCCESS, FAILED, SKIPPED, CANCELLED, TIMED_OUT, UNCONFIRMED }
    public interface Sink { void write(int priority, String json); }
    private static final String SESSION = UUID.randomUUID().toString();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong STATE_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Operation> CONTEXT = new ThreadLocal<>();
    private static final Object LOCK = new Object();
    private static final Object DELIVERY_LOCK = new Object();
    private static final int CAPACITY = 256, ACTIVE_LIMIT = 256, FIELD_LIMIT = 1024;
    private static final ArrayDeque<Record> QUEUE = new ArrayDeque<>();
    private static final LinkedHashMap<String, Operation> ACTIVE = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Map<String, Object>> STATES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Suppression> WINDOWS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Long> LOSSES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Map<String, Object>> OBSERVATIONS = new LinkedHashMap<>(), OUTCOMES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> ALIASES = new LinkedHashMap<>();
    private static final LogLossLedger LOSS_DETAIL = new LogLossLedger();
    private static final ArrayDeque<String> RECOVERED = new ArrayDeque<>();
    private static volatile Sink framework;
    private static volatile String moduleVersion = "unknown", buildId = "unknown", hostVersion = "unknown",
            processName = "unknown", revision = "unknown";
    private static volatile int pid;
    private static volatile File journal;
    private static long dropped, suppressed, modeSuppressed, sinkFailures, journalFailures, evictedActive, clearGeneration, clearedRecords, journalEvictions;
    private static boolean writing;
    private static long journalRevision, journalPersistedRevision;
    private static final Thread WORKER;
    private static final ThreadPoolExecutor SNAPSHOTS = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), action -> { Thread thread = new Thread(action, "DouAi-LogSnapshot"); thread.setDaemon(true); return thread; });
    static {
        SNAPSHOTS.allowCoreThreadTimeOut(true);
        WORKER = new Thread(DouAiLog::drain, "DouAi-Evidence");
        WORKER.setDaemon(true);
        WORKER.start();
    }
    private DouAiLog() { }

    public static void setFrameworkSink(Sink value) { framework = value; }
    /** A completed mode change also fences already-in-flight delivery to either output. */
    static void modeChangeBarrier(boolean detailed) {
        synchronized (DELIVERY_LOCK) {
            DebugLog.flushStructuredBatch();
            if (!detailed) LogErrorSnapshot.retryUndeliveredContexts();
        }
    }
    /** Frames are independently identifiable; only the private file contains the original JSON line. */
    public static void writeFramework(Sink transport, int priority, String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length <= 1200) { transport.write(priority, json); return; }
        String eventId = extracted(json, "event_id"), traceId = extracted(json, "trace_id");
        String checksum;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(); for (byte b : hash) text.append(String.format(Locale.ROOT, "%02x", b));
            checksum = text.toString();
        } catch (Exception ignored) { checksum = "unavailable"; }
        ArrayList<String> chunks = new ArrayList<>();
        for (int start = 0; start < json.length();) {
            // At most 200 UTF-16 chars: even escaped CJK/control content fits a small frame.
            int end = Math.min(start + 200, json.length());
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))) end--;
            chunks.add(json.substring(start, end)); start = end;
        }
        for (int i = 0; i < chunks.size(); i++) {
            StringBuilder part = new StringBuilder("{");
            field(part, "event", "event_part"); field(part, "event_id", eventId); field(part, "session_id", SESSION);
            field(part, "trace_id", traceId); field(part, "seq", extracted(json, "seq"));
            field(part, "part_index", Integer.toString(i)); field(part, "part_count", Integer.toString(chunks.size()));
            field(part, "checksum_sha256", checksum); field(part, "payload", chunks.get(i));
            part.setCharAt(part.length() - 1, '}'); transport.write(priority, part.toString());
        }
    }
    private static String extracted(String json, String field) {
        String key = "\"" + field + "\":\""; int start = json.indexOf(key);
        if (start < 0) return null; start += key.length(); int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
    public static void environment(String version, String build, String host, String process, int processId) {
        moduleVersion = safe(version); buildId = safe(build); hostVersion = safe(host);
        processName = safe(process); pid = processId;
    }
    public static void configRevision(String value) { revision = safe(value); }
    public static Operation current() { return CONTEXT.get(); }

    /** A complete, typed digest of a committed snapshot, not the bounded display-reference helper. */
    public static String configurationRevision(Map<String, ?> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(SESSION.getBytes(StandardCharsets.UTF_8));
            for (String key : new java.util.TreeSet<>(values.keySet())) {
                digestText(digest, key); Object value = values.get(key);
                if (value == null) digestText(digest, "null");
                else if (value instanceof String) { digestText(digest, "string"); digestText(digest, (String) value); }
                else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double) {
                    digestText(digest, value.getClass().getName()); digestText(digest, String.valueOf(value));
                } else if (value instanceof java.util.Set<?>) {
                    java.util.TreeSet<String> ordered = new java.util.TreeSet<>();
                    for (Object item : (java.util.Set<?>) value) {
                        if (!(item instanceof String)) return "config_revision_unavailable";
                        ordered.add((String) item);
                    }
                    digestText(digest, "string_set:" + ordered.size());
                    for (String item : ordered) digestText(digest, item);
                } else return "config_revision_unavailable";
            }
            StringBuilder result = new StringBuilder("cfg_");
            for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (RuntimeException | java.security.NoSuchAlgorithmException unavailable) { return "config_revision_unavailable"; }
    }
    private static void digestText(MessageDigest digest, String value) {
        digest.update(Integer.toString(value.length()).getBytes(StandardCharsets.UTF_8)); digest.update((byte) ':');
        java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        java.nio.CharBuffer chars = java.nio.CharBuffer.wrap(value); java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(4096);
        while (true) {
            java.nio.charset.CoderResult status = encoder.encode(chars, bytes, true);
            bytes.flip(); digest.update(bytes); bytes.clear();
            if (!status.isOverflow()) break;
        }
        encoder.flush(bytes); bytes.flip(); digest.update(bytes); digest.update((byte) ';');
    }

    public static void registerFeatureAliases(String canonical, String... aliases) {
        synchronized (LOCK) {
            for (String alias : aliases) {
                if (canonical.equals(alias)) continue;
                if (ALIASES.size() >= 256 && !ALIASES.containsKey(alias)) ALIASES.remove(ALIASES.keySet().iterator().next());
                ALIASES.put(alias, canonical);
                moveLatest(OBSERVATIONS, alias, canonical); moveLatest(OUTCOMES, alias, canonical);
            }
        }
    }
    private static void moveLatest(LinkedHashMap<String, Map<String, Object>> map, String alias, String canonical) {
        Map<String, Object> old = map.remove(alias), existing = map.get(canonical);
        if (old != null && (existing == null || Long.parseLong((String)old.get("state_order"))
                > Long.parseLong((String)existing.get("state_order")))) map.put(canonical, old);
    }
    private static String canonical(String feature) { return ALIASES.getOrDefault(feature, feature); }
    private static void observed(String feature, String source, String trace) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("observed", true); values.put("last_trigger_wall_time", OffsetDateTime.now().toString());
        values.put("observed_at_ms", Long.toString(System.currentTimeMillis())); values.put("source", source);
        values.put("state_order", Long.toString(STATE_SEQUENCE.incrementAndGet()));
        values.put("source_feature_id", feature); if (trace != null) values.put("last_trace_id", trace);
        synchronized (LOCK) { putState(OBSERVATIONS, canonical(feature), values, 128); }
    }

    public static Operation begin(String feature, String trigger) {
        return begin(feature, trigger, null);
    }
    public static Operation begin(String feature, String trigger, String effectiveRevision) {
        Operation parent = current();
        Operation operation = new Operation(feature, trigger, parent, effectiveRevision);
        synchronized (LOCK) {
            if (ACTIVE.size() >= ACTIVE_LIMIT) {
                Operation removed = ACTIVE.remove(ACTIVE.keySet().iterator().next());
                evictedActive++;
                emit(5, removed.feature, "incomplete", removed, "active_capacity", "observation_evicted; business_result=unknown", null);
            }
            ACTIVE.put(operation.rootSpan, operation);
            journalRevision++; LOCK.notifyAll();
        }
        emit(4, operation.feature, "operation_started", operation, "triggered", "trigger=" + safe(trigger), null);
        return operation;
    }

    /** State changes immediately; equal observations are counted and summarized every 30s. */
    public static void event(String feature, String event, String reason, String evidence) {
        String signature = safe(reason) + " " + safe(evidence);
        Operation context = current();
        String key = safe(feature) + "/" + safe(event) + (context == null ? "" : "/" + context.rootSpan);
        synchronized (LOCK) {
            long now = System.nanoTime();
            Suppression previous = WINDOWS.get(key);
            if (previous != null && previous.signature.equals(signature) && now - previous.when < 30_000_000_000L) {
                previous.count++; suppressed++; return;
            }
            if (previous != null && previous.count > 0) {
                emit(4, feature, "window_summary", current(), "repeat_suppressed",
                        "event=" + safe(event) + "; count=" + previous.count + "; window_ms=" + (now - previous.when) / 1_000_000L, null);
            }
            if (WINDOWS.size() >= 256) WINDOWS.remove(WINDOWS.keySet().iterator().next());
            WINDOWS.put(key, new Suppression(signature, now));
        }
        if (event.equals("eligibility") || event.equals("decision") || event.equals("process_gate")
                || event.equals("predicate_result") || event.equals("view_state") || event.equals("view_applied")
                || event.equals("view_attached") || event.equals("receipt_skipped") || event.equals("callback_ignored")) {
            observed(feature, event, context == null ? null : context.trace);
        }
        emit(4, feature, event, current(), reason, evidence, null);
    }

    public static void featureState(String feature, boolean enabled, String availability, String hookState, String evidence) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled); values.put("availability", safe(availability)); values.put("hook_state", safe(hookState));
        values.put("evidence", safe(evidence)); values.put("config_revision", revision);
        boolean changed;
        synchronized (LOCK) {
            Map<String, Object> previous = STATES.get(safe(feature));
            changed = previous == null || !java.util.Objects.equals(previous.get("enabled"), enabled)
                    || !java.util.Objects.equals(previous.get("availability"), values.get("availability"))
                    || !java.util.Objects.equals(previous.get("hook_state"), values.get("hook_state"))
                    || !java.util.Objects.equals(previous.get("evidence"), values.get("evidence"));
            putState(STATES, safe(feature), values, 256);
        }
        if (!changed && !DebugLog.isEnabled()) return;
        event(feature, "feature_state", "configuration", "enabled=" + enabled + ";availability=" + safe(availability)
                + ";hook_state=" + safe(hookState) + ";" + safe(evidence));
    }
    public static void hook(String id, String feature, String phase, String evidence) {
        if (phase.equals("first_trigger") || phase.equals("returned") || phase.equals("threw")) observed(feature, "hook_" + phase, null);
        event(feature, "hook_" + safe(phase), "hook_observation", "hook_id=" + safe(id) + "; " + safe(evidence));
    }
    public static void hookDetail(String id, String feature, String phase, String args, String result, long durationUs, Throwable failure) {
        if (failure == null && !DebugLog.isEnabled()) return;
        String key = "hook_detail/" + safe(id) + "/" + safe(phase);
        synchronized (LOCK) {
            long now = System.nanoTime(); Suppression prior = WINDOWS.get(key);
            String signature = safe(args) + ";" + safe(result) + ";" + (failure == null ? "" : failure.getClass().getName());
            if (prior != null && prior.signature.equals(signature) && now - prior.when < 30_000_000_000L) {
                prior.count++; suppressed++; return;
            }
            if (prior != null && prior.count > 0) emit(4, feature, "window_summary", current(), "hook_detail_suppressed",
                    "hook_id=" + safe(id) + ";count=" + prior.count, null);
            if (WINDOWS.size() >= 256) WINDOWS.remove(WINDOWS.keySet().iterator().next());
            WINDOWS.put(key, new Suppression(signature, now));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("hook_id", safe(id)); extra.put("phase", safe(phase)); extra.put("args", safe(args)); extra.put("result", safe(result));
        extra.put("duration_us", Long.toString(durationUs)); extra.put("detail", "true");
        extra.put("sampled", "true"); extra.put("sample_policy", "first_or_changed_within_30s_window");
        emit(failure == null ? 3 : 6, feature, "hook_detail", current(), failure == null ? "hook_observed" : "hook_threw", "business_result=not_implied", failure, extra);
    }
    public static void failure(String feature, String stage, Throwable error) {
        Operation operation = current();
        emit(6, feature, "exception", operation, stage, "", error);
    }

    /** Migration adapter: arbitrary legacy payloads are private; they are not business outcomes. */
    public static void legacy(int priority, String tag, String message, Throwable error) {
        boolean detail = priority < 5 && !DebugLogPolicy.isBasic(priority, message);
        if (detail && !DebugLog.isEnabled()) return;
        synchronized (LOCK) {
            String key = "legacy/" + safe(tag) + "/" + priority;
            long now = System.nanoTime(); Suppression prior = WINDOWS.get(key);
            if (prior != null && now - prior.when < 30_000_000_000L) { prior.count++; suppressed++; return; }
            if (WINDOWS.size() >= 256) WINDOWS.remove(WINDOWS.keySet().iterator().next());
            WINDOWS.put(key, new Suppression("legacy", now));
        }
        String text = message == null ? "" : message;
        emit(Math.max(4, priority), "legacy", "legacy_diagnostic", current(), "migration_context",
                "tag=" + safe(tag) + "; source_level=" + priority + "; chars=" + text.length() + "; ref=" + reference(text), null);
        if (error != null) failure("legacy", "legacy_diagnostic", error);
    }

    /** Session salted reference. Intended for correlation, not an anonymity guarantee. */
    public static String reference(String value) {
        if (value == null || value.isEmpty()) return "none";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(SESSION.getBytes(StandardCharsets.UTF_8));
            digest.update(Integer.toString(value.length()).getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest(value.substring(0, Math.min(value.length(), 4096)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("ref_");
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            return out.toString();
        } catch (Exception ignored) { return "reference_unavailable"; }
    }

    public static final class Operation {
        private final String feature, trace, rootSpan = UUID.randomUUID().toString(), parent;
        private final String linkedTrace;
        private final boolean rootOperation;
        private final String configRevision;
        private final String configOrigin;
        private final Operation rootOwner;
        private final long started = System.nanoTime();
        private final long lossAtStart;
        private volatile String stage = "trigger", lastSpan, terminal;
        private volatile int attempt = 1;
        private long stageStarted = started;
        private long lateCount;
        private long progressSuppressed, lastProgress;
        private int stageAttempt = 1;
        private String progressEvidence = "";
        private final LinkedHashMap<String, Long> durations = new LinkedHashMap<>();
        private final java.util.concurrent.CopyOnWriteArraySet<String> errors = new java.util.concurrent.CopyOnWriteArraySet<>();
        private final java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>> selections = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile OperationView view;
        private Operation(String feature, String trigger, Operation parent, String effectiveRevision) {
            this.feature = safe(feature); this.parent = parent == null ? null : parent.rootSpan;
            rootOperation = parent == null || parent.terminal != null;
            linkedTrace = rootOperation && parent != null ? parent.trace : null;
            trace = rootOperation ? UUID.randomUUID().toString() : parent.trace; lastSpan = rootSpan;
            configRevision = effectiveRevision != null ? safe(effectiveRevision) : rootOperation ? revision : parent.configRevision;
            configOrigin = effectiveRevision != null ? "explicit_options_snapshot" : rootOperation ? "process_at_admission" : "inherited_operation";
            rootOwner = rootOperation ? this : parent.rootOwner;
            view = new OperationView(stage, lastSpan, attempt, null);
            synchronized (LOCK) { lossAtStart = dropped; }
            observed(this.feature, "operation_started", trace);
        }
        public String traceId() { return trace; }
        /** Observes a real settings-read decision without changing when business code reads settings. */
        public synchronized void configuration(String effectiveRevision, String policy) {
            if (terminal != null) { late("configuration_after_terminal", "policy=" + safe(policy)); return; }
            Map<String, Object> selected = new LinkedHashMap<>();
            selected.put("effective_config_revision", safe(effectiveRevision)); selected.put("config_selection_policy", safe(policy));
            selected.put("selected_at_stage", stage); selected.put("attempt", Integer.toString(attempt));
            if (selections.size() < 16) selections.add(java.util.Collections.unmodifiableMap(selected));
            emit(4, feature, "configuration_selected", this, "business_snapshot_selected", "settings_read_timing=unchanged", null, selected);
        }
        public synchronized void stage(String name, String evidence) {
            if (terminal != null) { late("stage_after_terminal", "received_stage=" + safe(name)); return; }
            long now = System.nanoTime();
            String next = safe(name);
            if (next.equals(stage) && stageAttempt == attempt && now - lastProgress < 1_000_000_000L) {
                progressSuppressed++; progressEvidence = safe(evidence);
                synchronized (LOCK) { suppressed++; }
                return;
            }
            if (!next.equals(stage) || stageAttempt != attempt) summarizeProgress();
            if (durations.size() < 24 || durations.containsKey(stage)) durations.merge(stage, (now - stageStarted) / 1_000_000L, Long::sum);
            stage = next; stageAttempt = attempt; stageStarted = now; lastProgress = now; lastSpan = UUID.randomUUID().toString();
            view = new OperationView(stage, lastSpan, attempt, terminal);
            synchronized (LOCK) { journalRevision++; LOCK.notifyAll(); }
            emit(4, feature, "stage", this, "progress", evidence, null);
        }
        public synchronized void attempt(int value) {
            if (terminal != null) { late("attempt_after_terminal", ""); return; }
            attempt = Math.max(1, value); stage("attempt", "attempt=" + attempt);
        }
        public synchronized void error(String name, Throwable error) {
            emit(5, feature, "exception", this, name, "attempt_failed; root_result_pending", error);
        }
        private synchronized void rememberError(String id) { if (errors.size() < 8) errors.add(id); }
        private void summarizeProgress() {
            if (progressSuppressed > 0) {
                emit(4, feature, "stage_summary", this, "progress_coalesced", "count=" + progressSuppressed + ";last=" + progressEvidence, null);
                progressSuppressed = 0; progressEvidence = "";
            }
        }
        public synchronized void finish(Outcome outcome, String reason, String evidence) {
            if (terminal != null) { late("duplicate_or_late_callback", "received=" + outcome.name().toLowerCase(Locale.ROOT)); return; }
            summarizeProgress(); terminal = outcome.name().toLowerCase(Locale.ROOT);
            view = new OperationView(stage, lastSpan, attempt, terminal);
            long now = System.nanoTime();
            if (durations.size() < 24 || durations.containsKey(stage)) durations.merge(stage, (now - stageStarted) / 1_000_000L, Long::sum);
            synchronized (LOCK) {
                ACTIVE.remove(rootSpan);
                journalRevision++; LOCK.notifyAll();
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("outcome", terminal); facts.put("trace_id", trace); facts.put("source_feature_id", feature);
                facts.put("result_scope", feature); facts.put("root_operation", rootOperation); facts.put("reason_code", safe(reason));
                facts.put("evidence", safe(evidence)); facts.put("last_stage", stage); facts.put("error_ids", new ArrayList<>(errors));
                facts.put("config_revision", configRevision); facts.put("observed_at_ms", Long.toString(System.currentTimeMillis()));
                facts.put("state_order", Long.toString(STATE_SEQUENCE.incrementAndGet()));
                putState(OUTCOMES, canonical(feature), facts, 128);
            }
            emit(outcome == Outcome.FAILED ? 6 : outcome == Outcome.TIMED_OUT || outcome == Outcome.UNCONFIRMED ? 5 : 4,
                    feature, rootOperation ? "operation_finished" : "span_finished", this, reason,
                    safe(evidence) + (DebugLog.isEnabled() ? "; stage_ms=" + durations : ""), null);
        }
        private void late(String reason, String evidence) {
            lateCount++;
            if ((lateCount & (lateCount - 1)) == 0) emit(5, feature, "late_callback", this, reason, "count=" + lateCount + "; " + safe(evidence), null);
        }
        public Runnable wrap(Runnable task) { return () -> {
            Operation previous = CONTEXT.get(); CONTEXT.set(this);
            try { task.run(); } finally { if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous); }
        }; }
        public <T> Consumer<T> wrapConsumer(Consumer<T> task) { return value -> wrap(() -> task.accept(value)).run(); }
        public <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
            Operation previous = CONTEXT.get(); CONTEXT.set(this);
            try { return task.call(); } finally { if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous); }
        }
    }

    private static final class OperationView {
        final String stage, span, terminal; final int attempt;
        OperationView(String stage, String span, int attempt, String terminal) {
            this.stage = stage; this.span = span; this.attempt = attempt; this.terminal = terminal;
        }
    }

    private static void emit(int priority, String feature, String event, Operation operation, String reason, String evidence, Throwable error) {
        emit(priority, feature, event, operation, reason, evidence, error, java.util.Collections.emptyMap());
    }
    private static void emit(int priority, String feature, String event, Operation operation, String reason, String evidence,
                             Throwable error, Map<String, Object> extra) {
        boolean detailed = DebugLog.isEnabled();
        if (!detailed && priority < 5 && LogModePolicy.detailOnly(event, operation == null || operation.rootOperation)) {
            synchronized (LOCK) { modeSuppressed++; } return;
        }
        long epoch; synchronized (LOCK) { epoch = clearGeneration; }
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            String sequence = Long.toString(SEQUENCE.incrementAndGet());
            fields.put("schema_version", "2"); fields.put("event_id", SESSION + ":" + sequence);
            fields.put("session_id", SESSION); fields.put("seq", sequence);
            long when = System.currentTimeMillis();
            if (detailed) fields.put("monotonic_ns", Long.toString(System.nanoTime()));
            fields.put("module_version", moduleVersion); if (detailed) fields.put("build_id", buildId); fields.put("host_version", hostVersion);
            fields.put("process_name", processName); fields.put("pid", Integer.toString(pid));
            fields.put("config_revision", operation == null ? revision : operation.configRevision);
            if (detailed) {
                fields.put("config_revision_source", operation == null ? "process_state_observation" : operation.configOrigin);
                fields.put("process_config_revision", revision);
            }
            fields.put("level", priority >= 6 ? "ERROR" : priority == 5 ? "WARN" : priority == 3 ? "DEBUG" : priority <= 2 ? "TRACE" : "INFO");
            fields.put("feature_id", safe(feature)); fields.put("event", safe(event)); fields.put("reason_code", safe(reason));
            fields.put("evidence", safe(evidence)); fields.put("truncated", Boolean.toString(evidence != null && evidence.length() > FIELD_LIMIT));
            if (error != null) {
                LogErrorSnapshot snapshot = LogErrorSnapshot.capture(error, detailed);
                if (operation != null) { operation.rememberError(snapshot.id); operation.rootOwner.rememberError(snapshot.id); }
                fields.put("error_id", snapshot.id); fields.put("exception_class", snapshot.type);
                fields.put("exception_message", snapshot.message);
                if (detailed) {
                    fields.put("exception_message_ref", snapshot.messageRef);
                    fields.put("exception_message_policy", "static_summary_or_exact_local_allowlist_raw_message_not_logged");
                    fields.put("error_context_truncated", Boolean.toString(snapshot.truncated));
                    fields.put("stack_frame_limit_per_cause", "8"); fields.put("cause_depth_limit", "3");
                    fields.put("error_context_first", Boolean.toString(snapshot.first));
                }
                if (snapshot.truncated) fields.put("truncated", "true");
                if (snapshot.first) { fields.put("cause_chain", snapshot.causes); fields.put("stack", snapshot.stack); }
            }
            if (operation != null) {
                OperationView view = operation.view;
                fields.put("trace_id", operation.trace);
                fields.put("root_operation", operation.rootOperation);
                fields.put("linked_trace_id", operation.linkedTrace);
                if (detailed) {
                    fields.put("span_id", view.span); fields.put("operation_span_id", operation.rootSpan);
                    fields.put("parent_span_id", view.span.equals(operation.rootSpan) ? operation.parent : operation.rootSpan);
                    fields.put("stage", view.stage); fields.put("attempt", Integer.toString(view.attempt));
                }
                fields.put("duration_ms", Long.toString((System.nanoTime() - operation.started) / 1_000_000L));
                fields.put("outcome", view.terminal); fields.put("last_stage", view.stage);
                fields.put("error_ids", java.util.Collections.unmodifiableList(new ArrayList<>(operation.errors)));
                if (detailed) {
                    if (view.terminal != null) fields.put("configuration_selections", java.util.Collections.unmodifiableList(new ArrayList<>(operation.selections)));
                    fields.put("stage_timings_truncated", Boolean.toString(operation.durations.size() >= 24));
                    synchronized (LOCK) { fields.put("local_lost_before_enqueue", Long.toString(dropped - operation.lossAtStart)); }
                    fields.put("integrity", "verify_export_manifest_and_storage_integrity");
                }
            } else fields.put("context_missing", "true");
            if (detailed) fields.putAll(extra);
            else {
                if (extra.containsKey("hook_id")) fields.put("hook_id", extra.get("hook_id"));
                if (extra.containsKey("phase")) fields.put("phase", extra.get("phase"));
            }
            enqueue(new Record(priority, safe(feature), fields, when, epoch));
        } catch (RuntimeException | LinkageError ignored) {
            synchronized (LOCK) { sinkFailures++; dropped++; }
            LOSS_DETAIL.record("event_capture_failed", SESSION, SEQUENCE.get(), safe(feature), safe(event), operation == null ? null : operation.trace);
        }
    }

    private static void enqueue(Record record) {
        synchronized (LOCK) {
            if (record.generation != clearGeneration) { clearedRecords++; LOSS_DETAIL.record("actively_cleared", SESSION, record.seq, record.feature, record.event, record.trace); return; }
            if (QUEUE.size() >= CAPACITY) {
                Record removed = null;
                for (Record queued : QUEUE) if (!queued.critical) { removed = queued; break; }
                if (removed == null || !record.critical) { lost(record, "queue_capacity"); return; }
                QUEUE.remove(removed); lost(removed, "queue_evicted_for_critical");
            }
            QUEUE.addLast(record); LOCK.notifyAll();
        }
    }
    private static void lost(Record record, String reason) {
        dropped++;
        record.contextNotDelivered();
        if (LOSSES.size() < 128 || LOSSES.containsKey(record.feature)) LOSSES.merge(record.feature, 1L, Long::sum);
        LOSS_DETAIL.record(reason, SESSION, record.seq, record.feature, record.event, record.trace);
    }
    private static void drain() {
        while (true) {
            ArrayList<Record> batch = new ArrayList<>(16);
            synchronized (LOCK) {
                while (QUEUE.isEmpty()) {
                    writing = false; LOCK.notifyAll();
                    if (journal != null && journalRevision != journalPersistedRevision
                            && System.nanoTime() - journalWrittenAt >= 1_000_000_000L) break;
                    try {
                        if (journal != null && journalRevision != journalPersistedRevision) LOCK.wait(1000);
                        else LOCK.wait();
                    } catch (InterruptedException ignored) { }
                }
                writing = true;
                while (!QUEUE.isEmpty() && batch.size() < 16) batch.add(QUEUE.removeFirst());
            }
            if (batch.isEmpty()) { writeJournal(); continue; }
            synchronized (DELIVERY_LOCK) {
                DebugLog.beginStructuredBatch();
                try {
                    for (Record record : batch) try {
                    synchronized (LOCK) {
                        if (record.generation != clearGeneration) {
                            record.contextNotDelivered(); clearedRecords++;
                            LOSS_DETAIL.record("actively_cleared", SESSION, record.seq, record.feature, record.event, record.trace);
                            continue;
                        }
                    }
                    boolean detailed = DebugLog.isEnabled();
                    if (!detailed) record.contextNotDelivered();
                    if (!detailed && record.priority < 5 && LogModePolicy.detailOnly(record.event, record.rootOperation())) {
                        synchronized (LOCK) { modeSuppressed++; }
                        LOSS_DETAIL.record("detail_mode_disabled_before_delivery", SESSION, record.seq, record.feature, record.event, record.trace);
                        continue;
                    }
                    String json = record.json(detailed);
                    DebugLog.recordStructured(record.priority, json);
                    Sink sink = framework; if (sink != null) sink.write(record.priority, json);
                    if (detailed && "true".equals(record.fields.get("error_context_first")))
                        LogErrorSnapshot.contextDelivered((String)record.fields.get("error_id"));
                    } catch (RuntimeException | LinkageError ignored) {
                        record.contextNotDelivered();
                        synchronized (LOCK) { sinkFailures++; }
                        LOSS_DETAIL.record("serialization_or_sink_failure", SESSION, record.seq, record.feature, record.event, record.trace);
                    }
                } finally { DebugLog.endStructuredBatch(); }
            }
            boolean idle; synchronized (LOCK) { idle = QUEUE.isEmpty(); }
            if (idle || System.nanoTime() - journalWrittenAt > 1_000_000_000L) writeJournal();
        }
    }

    public static void clearPending() {
        synchronized (DELIVERY_LOCK) {
            DebugLog.flushStructuredBatch();
            synchronized (LOCK) {
                for (Record record : QUEUE) {
                    record.contextNotDelivered();
                    LOSS_DETAIL.record("actively_cleared", SESSION, record.seq, record.feature, record.event, record.trace);
                }
                clearGeneration++; clearedRecords += QUEUE.size(); QUEUE.clear(); WINDOWS.clear();
            }
        }
    }

    /** The old private-file channel remains the only bridge between host and module application. */
    public static void initialize(File directory) {
        if (journal != null) return;
        synchronized (LOCK) {
            if (journal != null) return;
            File target = new File(directory, "active-" + processName.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".json");
            // Recovery is queued to the worker; no file access on a hook/main thread.
            journal = target;
            journalRevision++; LOCK.notifyAll();
        }
        event("logging", "session_started", "new_process_session", "previous_active_journal_checked_on_worker; external_transport_loss=unknown");
    }
    private static boolean recovered;
    private static long journalWrittenAt;
    private static void writeJournal() {
        long snapshotRevision; synchronized (LOCK) { snapshotRevision = journalRevision; }
        persistJournal(); journalWrittenAt = System.nanoTime();
        synchronized (LOCK) { journalPersistedRevision = snapshotRevision; }
    }
    private static void persistJournal() {
        File target = journal; if (target == null) return;
        try {
            if (!recovered) {
                recovered = true;
                if (target.isFile() && target.length() <= 524288) {
                    String previous = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
                    JSONObject old = new JSONObject(previous);
                    JSONArray operations = old.optJSONArray("open_operations");
                    if (operations != null) for (int i = 0; i < Math.min(ACTIVE_LIMIT, operations.length()); i++) {
                        JSONObject operation = operations.optJSONObject(i);
                        if (operation != null) recover(operation, old.optString("session_id", "unknown"));
                    }
                }
            }
            File parent = target.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("journal directory");
            pruneJournals(target);
            String snapshot = journalSnapshot();
            File temporary = new File(target.getPath() + ".tmp");
            Files.write(temporary.toPath(), snapshot.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { synchronized (LOCK) { journalFailures++; } }
    }

    private static String journalSnapshot() {
        synchronized (LOCK) {
            StringBuilder json = new StringBuilder("{"); field(json, "event", "active_journal"); field(json, "session_id", SESSION);
            json.append("\"open_operations\":[");
            for (Operation op : ACTIVE.values()) {
                json.append('{'); field(json, "trace_id", op.trace); field(json, "span_id", op.rootSpan);
                field(json, "parent_span_id", op.parent);
                field(json, "config_revision", op.configRevision); field(json, "config_revision_source", op.configOrigin);
                field(json, "feature_id", op.feature.substring(0, Math.min(96, op.feature.length())));
                String stage = op.stage; field(json, "last_stage", stage.substring(0, Math.min(128, stage.length())));
                field(json, "truncated", Boolean.toString(op.feature.length() > 96 || stage.length() > 128));
                json.setCharAt(json.length() - 1, '}'); json.append(',');
            }
            if (!ACTIVE.isEmpty()) json.setLength(json.length() - 1);
            return json.append("]}").toString();
        }
    }
    private static void pruneJournals(File target) {
        File[] files = target.getParentFile().listFiles((directory, name) -> name.startsWith("active-") && name.endsWith(".json"));
        if (files == null || files.length < 8) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        int remaining = files.length;
        int maximum = target.exists() ? 8 : 7;
        for (File file : files) {
            if (remaining <= maximum) break;
            if (!file.equals(target) && file.delete()) { remaining--; synchronized (LOCK) { journalEvictions++; } }
        }
    }

    private static void recover(JSONObject operation, String previousSession) {
        StringBuilder json = new StringBuilder("{");
        field(json, "schema_version", "2"); field(json, "event", "previous_session_incomplete");
        field(json, "event_id", UUID.randomUUID().toString()); field(json, "seq", Long.toString(SEQUENCE.incrementAndGet()));
        field(json, "session_id", SESSION); field(json, "wall_time", OffsetDateTime.now().toString());
        field(json, "previous_session_id", safe(previousSession)); field(json, "previous_trace_id", safe(operation.optString("trace_id")));
        field(json, "previous_span_id", safe(operation.optString("span_id", "unknown")));
        field(json, "previous_config_revision", safe(operation.optString("config_revision", "unknown")));
        field(json, "feature_id", safe(operation.optString("feature_id", "unknown")));
        field(json, "last_stage", safe(operation.optString("last_stage", "unknown")));
        field(json, "integrity", "incomplete"); field(json, "outcome", "unconfirmed");
        field(json, "reason_code", "process_or_logging_interrupted"); field(json, "level", "WARN");
        field(json, "evidence", "previous_process_journal; business_result=unknown; interruption_cause=unobserved");
        json.setCharAt(json.length() - 1, '}');
        String record = json.toString();
        synchronized (LOCK) { if (RECOVERED.size() >= ACTIVE_LIMIT) RECOVERED.removeFirst(); RECOVERED.addLast(record); }
        enqueue(new Record(5, "logging", record));
    }

    public static String manifest() {
        boolean detailed = DebugLog.isEnabled();
        Map<String, Object> report = new LinkedHashMap<>();
        synchronized (LOCK) {
            report.put("event", "integrity_manifest"); report.put("schema_version", "2"); report.put("session_id", SESSION);
            report.put("log_mode", detailed ? "detail" : "simple"); report.put("module_version", moduleVersion);
            report.put("host_version", hostVersion); report.put("process_name", processName); report.put("pid", Integer.toString(pid));
            report.put("manifest_seq", Long.toString(SEQUENCE.get())); report.put("snapshot_consistency", "best_effort_non_atomic");
            report.put("lost_count", Long.toString(dropped)); report.put("loss_by_feature", new LinkedHashMap<>(LOSSES));
            report.put("suppressed_count", Long.toString(suppressed)); report.put("sink_failures", Long.toString(sinkFailures));
            report.put("mode_suppressed_count", Long.toString(modeSuppressed));
            report.put("journal_failures", Long.toString(journalFailures)); report.put("active_evicted", Long.toString(evictedActive));
            report.put("actively_cleared_records", Long.toString(clearedRecords)); report.put("journal_files_evicted", Long.toString(journalEvictions));
            report.put("framework_truncation", "unknown"); report.put("record_complete", "not_guaranteed");
            report.put("clear_scope", "current_process_pending_and_shared_package_files_other_process_queues_unconfirmed");
            report.put("queue_size", Integer.toString(QUEUE.size())); report.put("queue_capacity", Integer.toString(CAPACITY));
            Map<String, Object> features = new LinkedHashMap<>();
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(STATES.keySet()); keys.addAll(OBSERVATIONS.keySet()); keys.addAll(OUTCOMES.keySet());
            for (String key : keys) {
                Map<String, Object> state = new LinkedHashMap<>(STATES.getOrDefault(key, java.util.Collections.emptyMap()));
                if (!detailed) state.remove("evidence");
                state.put("catalogued", STATES.containsKey(key));
                Map<String, Object> observation = OBSERVATIONS.get(key);
                if (observation == null) { observation = new LinkedHashMap<>(); observation.put("observed", false); }
                state.put("observation", observation);
                Map<String, Object> outcome = OUTCOMES.get(key);
                state.put("last_outcome", outcome == null || detailed ? outcome : LogModePolicy.project(outcome, false)); features.put(key, state);
            }
            report.put("catalogued_feature_count", STATES.size()); report.put("feature_states", features);
            if (detailed) { report.put("observations", new LinkedHashMap<>(OBSERVATIONS)); report.put("last_outcomes", new LinkedHashMap<>(OUTCOMES)); }
            ArrayList<Object> windows = new ArrayList<>();
            for (Map.Entry<String, Suppression> entry : WINDOWS.entrySet()) if (entry.getValue().count > 0) {
                Map<String, Object> window = new LinkedHashMap<>(); window.put("feature_event_context", entry.getKey());
                window.put("suppressed_count", Long.toString(entry.getValue().count));
                window.put("window_started_monotonic_ns", Long.toString(entry.getValue().when)); windows.add(window);
            }
            if (detailed) report.put("suppression_windows", windows);
            ArrayList<Object> open = new ArrayList<>();
            for (Operation op : ACTIVE.values()) {
                Map<String, Object> item = new LinkedHashMap<>(); OperationView view = op.view;
                item.put("trace_id", op.trace); item.put("span_id", op.rootSpan); item.put("parent_span_id", op.parent);
                item.put("feature_id", op.feature); item.put("last_stage", view.stage); item.put("config_revision", op.configRevision);
                item.put("integrity", "incomplete_result_pending"); open.add(item);
            }
            report.put("open_operations", open);
            ArrayList<Object> previous = new ArrayList<>();
            for (String entry : RECOVERED) previous.add(new RawJson(entry));
            report.put("previous_incomplete", previous);
        }
        report.put("loss_details", new RawJson(LOSS_DETAIL.json()));
        return encode(report);
    }
    public static void flush(Runnable callback) {
        try { SNAPSHOTS.execute(() -> { if (!awaitIdle(3000)) event("logging", "snapshot_incomplete", "flush_timeout", "pending_events_may_be_missing=true"); callback.run(); }); }
        catch (RuntimeException rejected) { event("logging", "snapshot_incomplete", "snapshot_queue_full", "pending_events_may_be_missing=true"); callback.run(); }
    }
    /** For the bounded pre-initialization replay only; already-stored history is never rewritten. */
    static void deliverStartupRecord(int priority, String json, Consumer<String> delivery) {
        synchronized (DELIVERY_LOCK) {
            String projected = replayForCurrentMode(priority, json);
            if (projected != null) delivery.accept(projected);
        }
    }
    static String replayForCurrentMode(int priority, String json) {
        Map<String, Object> fields = Record.rawFields(json); boolean detailed = DebugLog.isEnabled();
        boolean root = !Boolean.FALSE.equals(fields.get("root_operation")); String event = (String)fields.get("event");
        if (!detailed && priority < 5 && LogModePolicy.detailOnly(event, root)) {
            synchronized (LOCK) { modeSuppressed++; }
            LOSS_DETAIL.record("startup_detail_disabled", json); return null;
        }
        return encode(LogModePolicy.project(fields, detailed));
    }
    /** Bounded barrier for background export and verification; never call from a host hook. */
    public static boolean awaitIdle(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized (LOCK) {
            while (writing || !QUEUE.isEmpty()) {
                long left = until - System.nanoTime(); if (left <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedWait(LOCK, left); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
            }
            return true;
        }
    }
    private static String safe(String value) {
        if (value == null) return "";
        String bounded = value.length() <= FIELD_LIMIT ? value : value.substring(0, FIELD_LIMIT);
        boolean identifier = true;
        for (int i = 0; i < bounded.length(); i++) {
            char c = bounded.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '-' || c == '.')) { identifier = false; break; }
        }
        // These characters cannot match any text-redaction delimiter or JSON/URL grammar.
        return identifier ? bounded : DebugLogRedactor.text(bounded);
    }
    private static void putState(LinkedHashMap<String, Map<String, Object>> target, String key, Map<String, Object> value, int limit) {
        if (target.size() >= limit && !target.containsKey(key)) target.remove(target.keySet().iterator().next());
        target.put(key, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value)));
    }
    private static void field(StringBuilder out, String name, String value) {
        if (value == null) return;
        quoted(out, name); out.append(':'); quoted(out, value); out.append(',');
    }
    private static void quoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || c == 127 || c == '\u2028' || c == '\u2029') out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        out.append('"');
    }
    private static final class RawJson { final String value; RawJson(String value) { this.value = value; } }
    private static final class Record {
        final int priority; final String feature, event, trace; final long seq, when, generation;
        final boolean critical; final Map<String, Object> fields; private String encoded; private boolean encodedDetailed;
        Record(int priority, String feature, Map<String, Object> fields, long when, long generation) {
            this.priority = priority; this.feature = feature; this.fields = java.util.Collections.unmodifiableMap(fields);
            this.event = (String)fields.get("event"); this.trace = (String)fields.get("trace_id");
            this.seq = Long.parseLong((String)fields.get("seq")); this.when = when; this.generation = generation;
            critical = priority >= 5 || event.equals("operation_started") || event.equals("operation_finished")
                    || event.equals("span_finished") || event.equals("exception") || event.equals("stage")
                    || event.equals("stage_summary") || event.equals("previous_session_incomplete");
        }
        Record(int priority, String feature, String raw) {
            this(priority, feature, rawFields(raw), System.currentTimeMillis(), generationNow());
        }
        boolean rootOperation() { return !Boolean.FALSE.equals(fields.get("root_operation")); }
        void contextNotDelivered() {
            if ("true".equals(fields.get("error_context_first")))
                LogErrorSnapshot.contextNotDelivered((String)fields.get("error_id"));
        }
        String json(boolean detailed) {
            if (encoded == null || encodedDetailed != detailed) {
                Map<String, Object> complete = new LinkedHashMap<>(fields);
                complete.put("wall_time", java.time.Instant.ofEpochMilli(when).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString());
                encoded = encode(LogModePolicy.project(complete, detailed)); encodedDetailed = detailed;
            }
            return encoded;
        }
        String jsonForLoss() {
            Map<String, Object> ids = new LinkedHashMap<>(); ids.put("session_id", SESSION); ids.put("seq", Long.toString(seq));
            ids.put("feature_id", feature); ids.put("event", event); ids.put("trace_id", trace); return encode(ids);
        }
        private static Map<String, Object> rawFields(String raw) {
            try {
                JSONObject parsed = new JSONObject(raw);
                Map<String, Object> fields = new LinkedHashMap<>();
                java.util.Iterator<String> names = parsed.keys();
                while (names.hasNext()) {
                    String key = names.next(); fields.put(key, normalizedJsonValue(parsed.get(key)));
                }
                return fields;
            } catch (Exception invalid) { throw new IllegalArgumentException("Invalid internal record", invalid); }
        }
        /** Replayed JSON must use the same Java field types as live immutable records. */
        private static Object normalizedJsonValue(Object value) throws org.json.JSONException {
            if (value == null || value == JSONObject.NULL) return null;
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject)value; Map<String, Object> fields = new LinkedHashMap<>();
                java.util.Iterator<String> names = object.keys();
                while (names.hasNext()) {
                    String key = names.next(); fields.put(key, normalizedJsonValue(object.get(key)));
                }
                return java.util.Collections.unmodifiableMap(fields);
            }
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray)value; ArrayList<Object> values = new ArrayList<>();
                for (int index = 0; index < array.length(); index++) values.add(normalizedJsonValue(array.get(index)));
                return java.util.Collections.unmodifiableList(values);
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
            throw new IllegalArgumentException("Unsupported internal JSON value");
        }
    }
    private static long generationNow() { synchronized (LOCK) { return clearGeneration; } }
    private static String encode(Object value) {
        StringBuilder out = new StringBuilder(); encodeInto(out, value); return out.toString();
    }
    private static void encodeInto(StringBuilder out, Object value) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) { quoted(out, (String)value); return; }
        if (value instanceof RawJson) { out.append(((RawJson)value).value); return; }
        if (value instanceof Boolean || value instanceof Number) { out.append(value); return; }
        if (value instanceof Map<?, ?>) {
            out.append('{'); boolean comma = false;
            for (Map.Entry<?, ?> item : ((Map<?, ?>)value).entrySet()) {
                if (item.getValue() == null) continue;
                if (comma) out.append(','); comma = true;
                encodeInto(out, (String)item.getKey()); out.append(':'); encodeInto(out, item.getValue());
            }
            out.append('}'); return;
        }
        if (value instanceof Iterable<?>) {
            out.append('['); boolean comma = false;
            for (Object item : (Iterable<?>)value) { if (comma) out.append(','); comma = true; encodeInto(out, item); }
            out.append(']'); return;
        }
        throw new IllegalArgumentException("Unsupported log field type");
    }
    private static final class Suppression {
        final String signature; final long when; long count;
        Suppression(String signature, long when) { this.signature = signature; this.when = when; }
    }
}
