package com.douai.module.config;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.WeakHashMap;

/** Durable send reservations. Reserve on a worker before calling the host; uncertain sends stay claimed. */
public final class SubmissionLedger {
    private static final Object LOCK = new Object();
    private static final Map<SharedPreferences, Boolean> FAILED = new WeakHashMap<>();
    private static final String PREFIX = "send.";
    private final SharedPreferences preferences;
    private final int capacity;
    private final Committer committer;

    interface Committer { boolean commit(SharedPreferences.Editor editor); }

    public SubmissionLedger(SharedPreferences preferences, int capacity) {
        this(preferences, capacity, SharedPreferences.Editor::commit);
    }

    SubmissionLedger(SharedPreferences preferences, int capacity, Committer committer) {
        if (preferences == null || capacity < 1 || committer == null) throw new IllegalArgumentException("Invalid ledger");
        this.preferences = preferences;
        this.capacity = capacity;
        this.committer = committer;
    }

    /** Only hashes of account-scoped host IDs are stored. Never pass message text or credentials. */
    public static String identity(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing send identity");
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) {
                result.append(Character.forDigit((item >>> 4) & 15, 16));
                result.append(Character.forDigit(item & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A false result forbids invoking the host, including when storage is unavailable or full. */
    public boolean reserve(String identity, String attempt, long now) {
        if (!validIdentity(identity) || !validAttempt(attempt) || now < 0) return false;
        synchronized (LOCK) {
            if (failed()) return false;
            try {
                Map<String, ?> all = preferences.getAll();
                if (all.containsKey(PREFIX + identity)) return false;
                int count = 0;
                String oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (Map.Entry<String, ?> stored : all.entrySet()) {
                    if (!stored.getKey().startsWith(PREFIX)) continue;
                    count++;
                    Record record = decode(stored.getValue());
                    // A corrupt or uncertain record might represent a successful host send.
                    if (record != null && "sent".equals(record.status) && record.timestamp < oldestTime) {
                        oldest = stored.getKey();
                        oldestTime = record.timestamp;
                    }
                }
                if (count >= capacity && (oldest == null || count > capacity)) return false;
                SharedPreferences.Editor editor = preferences.edit();
                if (count >= capacity) editor.remove(oldest);
                return commit(editor.putString(PREFIX + identity, encode(attempt, "submitted", now)));
            } catch (RuntimeException failure) { fail(); return false; }
        }
    }

    public boolean contains(String identity) {
        if (!validIdentity(identity)) return true;
        synchronized (LOCK) {
            if (failed()) return true;
            try { return preferences.contains(PREFIX + identity); }
            catch (RuntimeException failure) { fail(); return true; }
        }
    }

    /** Final pre-invocation check; a stale attempt may never use a replacement reservation. */
    public boolean isReserved(String identity, String attempt) {
        synchronized (LOCK) {
            Record record = current(identity, attempt);
            return record != null && "submitted".equals(record.status);
        }
    }

    /** Only a definite rejection may release a submission. Unknown results can only resolve to success. */
    public boolean complete(String identity, String attempt, String outcome, long now) {
        if (now < 0 || !("sent".equals(outcome) || "failed".equals(outcome)
                || "unknown".equals(outcome) || "submitted".equals(outcome))) return false;
        synchronized (LOCK) {
            Record previous = current(identity, attempt);
            if (previous == null) return false;
            if ("sent".equals(previous.status)) return "sent".equals(outcome);
            if ("unknown".equals(previous.status) && !"sent".equals(outcome)) return false;
            try {
                SharedPreferences.Editor editor = preferences.edit();
                return commit("failed".equals(outcome) ? editor.remove(PREFIX + identity)
                        : editor.putString(PREFIX + identity, encode(attempt, outcome, now)));
            } catch (RuntimeException failure) { fail(); return false; }
        }
    }

    /** Caller must prove that the host was never invoked. Cancellation after invocation must not call this. */
    public boolean abandon(String identity, String attempt) {
        synchronized (LOCK) {
            Record previous = current(identity, attempt);
            if (previous == null || !"submitted".equals(previous.status)) return false;
            try { return commit(preferences.edit().remove(PREFIX + identity)); }
            catch (RuntimeException failure) { fail(); return false; }
        }
    }

    private Record current(String identity, String attempt) {
        if (failed() || !validIdentity(identity) || !validAttempt(attempt)) return null;
        try {
            Record record = decode(preferences.getAll().get(PREFIX + identity));
            return record != null && attempt.equals(record.attempt) ? record : null;
        } catch (RuntimeException failure) { fail(); return null; }
    }

    private boolean commit(SharedPreferences.Editor editor) {
        try {
            if (committer.commit(editor)) return true;
        } catch (RuntimeException ignored) { }
        // Failed commits may still mutate SharedPreferences in memory. All instances must stop sending.
        fail();
        return false;
    }

    private boolean failed() { return FAILED.containsKey(preferences); }
    private void fail() { FAILED.put(preferences, Boolean.TRUE); }
    private static boolean validIdentity(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean validAttempt(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,100}");
    }

    private static String encode(String attempt, String status, long timestamp) {
        try { return new JSONObject().put("version", 1).put("attempt", attempt)
                .put("status", status).put("timestamp", timestamp).toString(); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Record decode(Object value) {
        if (!(value instanceof String)) return null;
        try {
            JSONObject json = new JSONObject((String) value);
            String attempt = json.getString("attempt"), status = json.getString("status");
            long timestamp = json.getLong("timestamp");
            if (json.getInt("version") != 1 || !validAttempt(attempt) || timestamp < 0
                    || !("sent".equals(status) || "submitted".equals(status) || "unknown".equals(status))) return null;
            return new Record(attempt, status, timestamp);
        } catch (org.json.JSONException | RuntimeException ignored) { return null; }
    }

    private static final class Record {
        final String attempt, status;
        final long timestamp;
        Record(String attempt, String status, long timestamp) {
            this.attempt = attempt; this.status = status; this.timestamp = timestamp;
        }
    }
}
