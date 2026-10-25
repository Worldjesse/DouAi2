package com.douai.module.config;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Durable send reservations. Reserve on a worker before calling the host; uncertain sends stay claimed. */
public final class SubmissionLedger {
    private static final Object LOCK = new Object();
    private static final Map<SharedPreferences, Boolean> FAILED = new WeakHashMap<>();
    private static final String PREFIX = "send.";
    private static final String SESSION = java.util.UUID.randomUUID().toString();
    private static final long RECOVERY_DELAY_MILLIS = 120_000;
    public static final String COMMENT_FILE = "douai_comment_submissions";
    public static final String REPLY_FILE = "douai_reply_submissions";
    public static final int DEFAULT_CAPACITY = 1000;
    private final SharedPreferences preferences;
    private final int capacity;
    private final Committer committer;

    interface Committer { boolean commit(SharedPreferences.Editor editor); }
    public enum ReserveResult { RESERVED, DUPLICATE, FULL, STORAGE_FAILED, INVALID }

    public static String accountScope(String uid) {
        if (uid == null || !uid.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Missing account");
        return identity("account:" + uid);
    }

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
        return reserve(identity, attempt, now, "") == ReserveResult.RESERVED;
    }

    public ReserveResult reserve(String identity, String attempt, long now, String scope) {
        if (!validIdentity(identity) || !validAttempt(attempt) || now < 0
                || scope == null || !scope.isEmpty() && !validIdentity(scope)) return ReserveResult.INVALID;
        synchronized (LOCK) {
            if (failed()) return ReserveResult.STORAGE_FAILED;
            try {
                Map<String, ?> all = preferences.getAll();
                if (all.containsKey(PREFIX + identity)) return ReserveResult.DUPLICATE;
                int count = 0;
                String oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (Map.Entry<String, ?> stored : all.entrySet()) {
                    if (!stored.getKey().startsWith(PREFIX)) continue;
                    count++;
                }
                if (count > capacity) return ReserveResult.FULL;
                // Decoding all reservations is only needed when a full ledger needs eviction.
                if (count == capacity) for (Map.Entry<String, ?> stored : all.entrySet()) {
                    if (!stored.getKey().startsWith(PREFIX)) continue;
                    Record record = decode(stored.getValue());
                    // A corrupt or uncertain record might represent a successful host send.
                    if (record != null && "sent".equals(record.status) && record.timestamp < oldestTime) {
                        oldest = stored.getKey();
                        oldestTime = record.timestamp;
                    }
                }
                if (count >= capacity && (oldest == null || count > capacity)) return ReserveResult.FULL;
                SharedPreferences.Editor editor = preferences.edit();
                if (count >= capacity) editor.remove(oldest);
                return commit(editor.putString(PREFIX + identity, encode(attempt, "submitted", now, scope, SESSION)))
                        ? ReserveResult.RESERVED : ReserveResult.STORAGE_FAILED;
            } catch (RuntimeException failure) { fail(); return ReserveResult.STORAGE_FAILED; }
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
                        : editor.putString(PREFIX + identity, encode(attempt, outcome, now, previous.scope, previous.session)));
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

    /** Legacy records may be attributed only through account-checked local history identities. */
    public Snapshot inspect(String scope, Set<String> legacyIdentities, long now) {
        if (!validIdentity(scope) || legacyIdentities == null) throw new IllegalArgumentException("Missing scope");
        synchronized (LOCK) {
            List<Entry> entries = new ArrayList<>();
            int total = 0, uncertain = 0, corrupt = 0, unassigned = 0;
            try {
                for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
                    if (!stored.getKey().startsWith(PREFIX)) continue;
                    total++;
                    String id = stored.getKey().substring(PREFIX.length());
                    if (!validIdentity(id)) { corrupt++; continue; }
                    Record record = decode(stored.getValue());
                    if (record == null) { corrupt++; continue; }
                    if (!"sent".equals(record.status)) uncertain++;
                    if (record.scope.isEmpty() && !legacyIdentities.contains(id)) { unassigned++; continue; }
                    if (!scope.equals(record.scope) && !(record.scope.isEmpty() && legacyIdentities.contains(id))) continue;
                    entries.add(new Entry(preferences, id, record, (String) stored.getValue(), scope,
                            recoverable(record, now)));
                }
                entries.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                return new Snapshot(entries, total, capacity, uncertain, corrupt, unassigned, failed());
            } catch (RuntimeException failure) {
                fail();
                return new Snapshot(Collections.emptyList(), total, capacity, uncertain, corrupt, unassigned, true);
            }
        }
    }

    /** Explicit human reconciliation only; never invokes the host or retries a send. */
    public boolean reconcile(Entry expected, String scope, boolean confirmedSent, long now) {
        return reconcile(expected, scope, confirmedSent, now, () -> true);
    }

    public boolean reconcile(Entry expected, String scope, boolean confirmedSent, long now,
                             java.util.function.BooleanSupplier beforeChange) {
        if (expected == null || expected.owner != preferences || !validIdentity(scope) || !scope.equals(expected.scope)) return false;
        synchronized (LOCK) {
            if (failed()) return false;
            try {
                Object raw = storedValue(PREFIX + expected.identity);
                if (!expected.encoded.equals(raw)) return false;
                Record record = decode(raw);
                if (record == null || !recoverable(record, now)) return false;
                if (beforeChange == null || !beforeChange.getAsBoolean()) return false;
                return commit(confirmedSent
                        ? preferences.edit().putString(PREFIX + expected.identity,
                        encode(record.attempt, "sent", now, scope, record.session))
                        : preferences.edit().remove(PREFIX + expected.identity));
            } catch (RuntimeException failure) { fail(); return false; }
        }
    }

    private static boolean recoverable(Record record, long now) {
        return !"sent".equals(record.status) && !SESSION.equals(record.session)
                && now >= record.timestamp && now - record.timestamp >= RECOVERY_DELAY_MILLIS;
    }

    public static final class Entry {
        public final String identity, status;
        public final long timestamp;
        public final boolean recoverable;
        private final String encoded, scope;
        private final SharedPreferences owner;
        private Entry(SharedPreferences owner, String identity, Record record, String encoded, String scope, boolean recoverable) {
            this.owner = owner;
            this.identity = identity; this.status = record.status; this.timestamp = record.timestamp;
            this.encoded = encoded; this.scope = scope; this.recoverable = recoverable;
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final int total, capacity, uncertain, corrupt, unassigned;
        public final boolean storageFailed;
        private Snapshot(List<Entry> entries, int total, int capacity, int uncertain, int corrupt, int unassigned, boolean failed) {
            this.entries = Collections.unmodifiableList(entries); this.total = total; this.capacity = capacity;
            this.uncertain = uncertain; this.corrupt = corrupt; this.unassigned = unassigned; storageFailed = failed;
        }
    }

    private Record current(String identity, String attempt) {
        if (failed() || !validIdentity(identity) || !validAttempt(attempt)) return null;
        try {
            Record record = decode(storedValue(PREFIX + identity));
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

    private String storedValue(String key) {
        try { return preferences.getString(key, null); }
        catch (ClassCastException malformed) { return null; }
    }

    private boolean failed() { return FAILED.containsKey(preferences); }
    private void fail() { FAILED.put(preferences, Boolean.TRUE); }
    private static boolean validIdentity(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean validAttempt(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,100}");
    }

    private static String encode(String attempt, String status, long timestamp, String scope, String session) {
        try { return new JSONObject().put("version", 2).put("attempt", attempt)
                .put("status", status).put("timestamp", timestamp).put("scope", scope)
                .put("session", session.isEmpty() ? SESSION : session).toString(); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Record decode(Object value) {
        if (!(value instanceof String)) return null;
        try {
            JSONObject json = new JSONObject((String) value);
            String attempt = json.getString("attempt"), status = json.getString("status");
            long timestamp = json.getLong("timestamp");
            int version = json.getInt("version");
            String scope = version == 1 ? "" : json.getString("scope");
            String session = version == 1 ? "" : json.getString("session");
            if ((version != 1 && version != 2) || !validAttempt(attempt) || timestamp < 0
                    || !scope.isEmpty() && !validIdentity(scope) || version == 2 && !validAttempt(session)
                    || !("sent".equals(status) || "submitted".equals(status) || "unknown".equals(status))) return null;
            return new Record(attempt, status, timestamp, scope, session);
        } catch (org.json.JSONException | RuntimeException ignored) { return null; }
    }

    private static final class Record {
        final String attempt, status, scope, session;
        final long timestamp;
        Record(String attempt, String status, long timestamp, String scope, String session) {
            this.attempt = attempt; this.status = status; this.timestamp = timestamp;
            this.scope = scope; this.session = session;
        }
    }
}
