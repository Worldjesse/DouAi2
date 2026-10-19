package com.douai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Clock;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable, account-scoped claims for incoming comment IDs. Never evicts uncertain sends. */
final class CommentReplyLedger {
    private static final String FILE = "douai_comment_reply_ledger";
    private static final String PREFIX = "incoming.";
    private static final String WATERMARK = "watermark.";
    private static final String BASELINE = "baseline.";
    private static final String INBOX_MODEL = "inbox_model.";
    static final int MAX_ENTRIES = 10_000;
    static final int MAX_ATTEMPTS = 3;
    static final int INBOX_MODEL_VERSION = 4;
    static final long RETRY_DELAY_MILLIS = 60_000L;
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final Clock clock;
    private final Committer committer;
    private boolean persistenceFailed;

    interface Committer { boolean commit(SharedPreferences.Editor editor); }

    CommentReplyLedger(Context context) {
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), Clock.systemUTC(),
                SharedPreferences.Editor::commit);
    }

    CommentReplyLedger(SharedPreferences preferences, Clock clock, Committer committer) {
        this.preferences = preferences;
        this.clock = clock;
        this.committer = committer;
        recoverPending();
    }

    boolean initialized(long account) {
        synchronized (LOCK) { return !persistenceFailed && account > 0 && preferences.contains(WATERMARK + account); }
    }

    long watermark(long account) {
        synchronized (LOCK) {
            try { return Math.max(0, preferences.getLong(WATERMARK + account, 0)); }
            catch (ClassCastException ignored) { return 0; }
        }
    }

    long baseline(long account) {
        synchronized (LOCK) {
            try { return Math.max(0, preferences.getLong(BASELINE + account, watermark(account))); }
            catch (ClassCastException ignored) { return watermark(account); }
        }
    }

    /** Replays the original inbox range once after a model change, without releasing any claim. */
    boolean ensureInboxModelVersion(long account) {
        synchronized (LOCK) {
            if (persistenceFailed || account <= 0) return false;
            try {
                if (!preferences.contains(WATERMARK + account)) return false;
                long watermark = preferences.getLong(WATERMARK + account, 0);
                long baseline = preferences.getLong(BASELINE + account, watermark);
                if (watermark < 0 || baseline < 0 || baseline > watermark) {
                    persistenceFailed = true;
                    return false;
                }
                int version = preferences.getInt(INBOX_MODEL + account, 0);
                if (version >= INBOX_MODEL_VERSION) return true;
                return commit(preferences.edit().putLong(WATERMARK + account, baseline)
                        .putInt(INBOX_MODEL + account, INBOX_MODEL_VERSION));
            } catch (RuntimeException ignored) {
                persistenceFailed = true;
                return false;
            }
        }
    }

    /** Establishes the first scan baseline. A failed commit leaves the account uninitialized. */
    void initialize(long account, long sinceSeconds) {
        initialize(account, sinceSeconds, Collections.emptyList());
    }

    /** Atomically remembers the baseline and comments already present, including their shared second. */
    boolean initialize(long account, long sinceSeconds, List<String> baselineIds) {
        if (account <= 0 || sinceSeconds < 0 || baselineIds == null) return false;
        synchronized (LOCK) {
            if (persistenceFailed) return false;
            if (initialized(account)) return true;
            Set<String> unique = new HashSet<>();
            for (String id : baselineIds) {
                if (!validId(id)) return false;
                unique.add(id);
            }
            Map<String, ?> all = preferences.getAll();
            int count = 0;
            for (String stored : all.keySet()) if (stored.startsWith(PREFIX)) count++;
            for (String id : unique) if (!all.containsKey(key(account, id))) count++;
            if (count > MAX_ENTRIES) return false;
            SharedPreferences.Editor editor = preferences.edit().putLong(WATERMARK + account, sinceSeconds)
                    .putLong(BASELINE + account, sinceSeconds)
                    .putInt(INBOX_MODEL + account, INBOX_MODEL_VERSION);
            for (String id : unique) {
                if (!all.containsKey(key(account, id))) {
                    editor.putString(key(account, id), encode(new Record("", "ignored", 1, clock.millis())));
                }
            }
            return commit(editor);
        }
    }

    void advance(long account, long seconds) {
        if (account <= 0 || seconds < 0) return;
        synchronized (LOCK) {
            if (seconds > watermark(account)) commit(preferences.edit().putLong(WATERMARK + account, seconds));
        }
    }

    /** Writes a pending reservation synchronously; false means generation/sending must not begin. */
    boolean claim(long account, String incomingId, String rootId, int threadLimit) {
        if (account <= 0 || !validId(incomingId) || !validId(rootId)) return false;
        synchronized (LOCK) {
            if (persistenceFailed) return false;
            Map<String, ?> all = preferences.getAll();
            String key = key(account, incomingId);
            Record previous = record(all.get(key));
            long now = clock.millis();
            boolean deferred = previous != null && "deferred".equals(previous.status);
            if (previous != null && !deferred && (!retryable(previous.status) || previous.attempts >= MAX_ATTEMPTS
                    || previous.timestamp > now || now - previous.timestamp < RETRY_DELAY_MILLIS)) return false;
            int count = 0;
            int threadCount = 0;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (!entry.getKey().startsWith(PREFIX)) continue;
                count++;
                Record stored = record(entry.getValue());
                // Corrupt reservations may represent a send. Stop until the data can be inspected.
                if (stored == null || (stored.rootId.isEmpty() && !"ignored".equals(stored.status))) return false;
                if (entry.getKey().startsWith(PREFIX + account + ".") && rootId.equals(stored.rootId)
                        && consumesThread(stored.status)) threadCount++;
            }
            if ((!all.containsKey(key) && count >= MAX_ENTRIES)
                    || (threadLimit > 0 && threadCount >= threadLimit)) return false;
            Record next = new Record(rootId, "pending", previous == null ? 1
                    : deferred ? previous.attempts : previous.attempts + 1, now);
            return save(key, next);
        }
    }

    /** Call BEFORE invoking the native sender. False means the native request must not be made. */
    boolean submitted(long account, String incomingId) {
        synchronized (LOCK) {
            if (persistenceFailed) return false;
            String key = key(account, incomingId);
            Record previous = record(preferences.getAll().get(key));
            if (previous == null || !"pending".equals(previous.status)) return false;
            return save(key, new Record(previous.rootId, "submitted", previous.attempts, clock.millis()));
        }
    }

    /** Pauses a claim before submission without spending an attempt on foreground/queue/limit changes. */
    void defer(long account, String incomingId) {
        if (account <= 0 || !validId(incomingId)) return;
        synchronized (LOCK) {
            String key = key(account, incomingId);
            Record previous = record(preferences.getAll().get(key));
            if (previous == null || !"pending".equals(previous.status)) return;
            save(key, new Record(previous.rootId, "deferred", previous.attempts, clock.millis()));
        }
    }

    /** Definite failure permits a bounded retry. Cancellation is terminal. Unknown results stay claimed. */
    void finish(long account, String incomingId, String status) {
        if (!"sent".equals(status) && !"failed".equals(status) && !"cancelled".equals(status)
                && !"unknown".equals(status) && !"sending".equals(status)) return;
        synchronized (LOCK) {
            String key = key(account, incomingId);
            Record previous = record(preferences.getAll().get(key));
            if (previous == null || "sent".equals(previous.status) || "cancelled".equals(previous.status)
                    || "ignored".equals(previous.status)
                    || retryable(previous.status)) return;
            // A timeout may later resolve to success, but cannot become a definite failure accidentally.
            if ("unknown".equals(previous.status) && !"sent".equals(status)) return;
            save(key, new Record(previous.rootId, "sending".equals(status) ? "unknown" : status,
                    previous.attempts, clock.millis()));
        }
    }

    /** True also for failed IDs: claim, rather than contains, decides whether a retry is due. */
    boolean contains(long account, String incomingId) {
        synchronized (LOCK) { return account > 0 && validId(incomingId) && preferences.contains(key(account, incomingId)); }
    }

    /** Whether another attempt remains, independently of the retry delay. */
    boolean retryable(long account, String incomingId) {
        if (account <= 0 || !validId(incomingId)) return false;
        synchronized (LOCK) {
            Record record = record(preferences.getAll().get(key(account, incomingId)));
            return record != null && ("deferred".equals(record.status)
                    || retryable(record.status) && record.attempts < MAX_ATTEMPTS);
        }
    }

    /** A durable thread cap is terminal; pending reservations may still fail and release their slot. */
    boolean threadExhausted(long account, String rootId, int limit) {
        if (account <= 0 || !validId(rootId) || limit <= 0) return false;
        synchronized (LOCK) {
            int count = 0;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(PREFIX + account + ".")) continue;
                Record record = record(entry.getValue());
                if (record != null && rootId.equals(record.rootId)
                        && ("submitted".equals(record.status) || "unknown".equals(record.status)
                        || "sent".equals(record.status)) && ++count >= limit) return true;
            }
            return false;
        }
    }

    private boolean save(String key, Record record) {
        return commit(preferences.edit().putString(key, encode(record)));
    }

    private static String encode(Record record) {
        try {
            return new JSONObject().put("root", record.rootId).put("status", record.status)
                    .put("attempts", record.attempts).put("at", record.timestamp).toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A durable pending record proves the sender had not crossed the submitted-before-send barrier. */
    private void recoverPending() {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            boolean changed = false;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(PREFIX)) continue;
                Record previous = record(entry.getValue());
                if (previous == null || !"pending".equals(previous.status)) continue;
                editor.putString(entry.getKey(), encode(new Record(previous.rootId, "deferred", previous.attempts,
                        clock.millis())));
                changed = true;
            }
            if (changed) commit(editor);
        }
    }

    private boolean commit(SharedPreferences.Editor editor) {
        if (persistenceFailed) return false;
        try {
            if (committer.commit(editor)) return true;
        } catch (RuntimeException ignored) { }
        persistenceFailed = true;
        return false;
    }

    private static boolean validId(String id) { return id != null && !id.trim().isEmpty() && id.length() <= 512; }
    private static boolean retryable(String status) { return "failed".equals(status) || "deferred".equals(status); }
    private static boolean consumesThread(String status) {
        return "pending".equals(status) || "submitted".equals(status) || "unknown".equals(status)
                || "sent".equals(status);
    }
    private static String key(long account, String incomingId) { return PREFIX + account + "." + incomingId; }

    private static Record record(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String) {
            try {
                JSONObject json = new JSONObject((String) raw);
                String status = json.getString("status");
                String root = json.getString("root");
                int attempts = json.getInt("attempts");
                long at = json.getLong("at");
                if ((validId(root) || (root.isEmpty() && "ignored".equals(status))) && attempts > 0 && at >= 0
                        && ("pending".equals(status) || "submitted".equals(status) || "sent".equals(status)
                        || "unknown".equals(status) || "cancelled".equals(status) || "ignored".equals(status)
                        || retryable(status))) {
                    return new Record(root, status, attempts, at);
                }
            } catch (JSONException ignored) { }
        }
        return new Record("", "unknown", MAX_ATTEMPTS, 0);
    }

    private static final class Record {
        final String rootId;
        final String status;
        final int attempts;
        final long timestamp;

        Record(String rootId, String status, int attempts, long timestamp) {
            this.rootId = rootId;
            this.status = status;
            this.attempts = attempts;
            this.timestamp = timestamp;
        }
    }
}
