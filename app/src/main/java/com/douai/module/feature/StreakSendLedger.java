package com.douai.module.feature;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable send state for daily streak messages. */
public final class StreakSendLedger {
    private static final String PREFIX = "streak.";
    private static final String RECORD_SUFFIX = ".record";
    private static final String LEGACY_PENDING_SUFFIX = ".pending";
    private static final String RETRY_TOKEN_KEY = "streak.retry_unknown_token";

    public enum Status { SENDING, SENT, FAILED, UNKNOWN }

    @FunctionalInterface
    interface Committer {
        boolean commit(SharedPreferences.Editor editor);
    }

    public static final class Attempt {
        public final String token;

        private Attempt(String token) { this.token = token; }
    }

    public static final class Record {
        public final Status status;
        public final String attemptToken;
        public final long timestamp;
        private final String retryToken;

        private Record(Status status, String attemptToken, long timestamp, String retryToken) {
            this.status = status;
            this.attemptToken = attemptToken;
            this.timestamp = timestamp;
            this.retryToken = retryToken;
        }
    }

    private final SharedPreferences preferences;
    private final Committer committer;
    private final Object lock = new Object();
    private final Set<String> uncertainKeys = new HashSet<>();
    private final Map<String, String> legacyRetryTokens = new HashMap<>();
    private String retryToken;

    public StreakSendLedger(SharedPreferences preferences) {
        this(preferences, SharedPreferences.Editor::commit);
    }

    StreakSendLedger(SharedPreferences preferences, Committer committer) {
        this.preferences = preferences;
        this.committer = committer;
        retryToken = readRetryToken();
        migrateLegacyPending();
        recoverSendingRecords();
    }

    /** Persists a changed user token so each unknown record can consume it at most once. */
    public boolean updateRetryUnknownToken(String value) {
        String next = value == null ? "" : value.trim();
        synchronized (lock) {
            if (next.equals(retryToken)) return true;
            if (!commit(preferences.edit().putString(RETRY_TOKEN_KEY, next))) return false;
            retryToken = next;
            return true;
        }
    }

    public boolean reset(String token) {
        synchronized (lock) {
            Object handled = preferences.getAll().get("streak.reset_token");
            if (token.equals(handled)) return true;
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(PREFIX) && !RETRY_TOKEN_KEY.equals(key)) editor.remove(key);
            }
            if (!commit(editor.putString("streak.reset_token", token))) return false;
            uncertainKeys.clear();
            return true;
        }
    }

    public boolean canBegin(String key) {
        synchronized (lock) {
            return canBegin(readLocked(key));
        }
    }

    /** Writes SENDING synchronously; a null result means no send may be attempted. */
    public Attempt begin(String key, long now) {
        synchronized (lock) {
            Record current = readLocked(key);
            if (!canBegin(current)) return null;
            String token = UUID.randomUUID().toString();
            Record sending = new Record(Status.SENDING, token, now, retryToken);
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(recordKey(key), encode(sending))
                    .remove(key + LEGACY_PENDING_SUFFIX);
            if (!commit(editor)) return null;
            uncertainKeys.remove(key);
            return new Attempt(token);
        }
    }

    public boolean markUnknown(String key, String attemptToken, long now) {
        synchronized (lock) {
            Record current = readLocked(key);
            if (!matches(current, attemptToken) || current.status != Status.SENDING) return false;
            if (save(key, new Record(Status.UNKNOWN, current.attemptToken, now, current.retryToken))) {
                uncertainKeys.remove(key);
                return true;
            }
            uncertainKeys.add(key);
            return false;
        }
    }

    /** A callback may resolve SENDING or UNKNOWN only for its current attempt. */
    public boolean complete(String key, String attemptToken, boolean success, long now) {
        synchronized (lock) {
            Record current = readLocked(key);
            if (!matches(current, attemptToken)
                    || (current.status != Status.SENDING && current.status != Status.UNKNOWN)) return false;
            Status status = success ? Status.SENT : Status.FAILED;
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(recordKey(key), encode(new Record(status, current.attemptToken, now, current.retryToken)))
                    .remove(key + LEGACY_PENDING_SUFFIX);
            if (success) editor.putBoolean(key, true);
            else editor.remove(key);
            if (commit(editor)) {
                uncertainKeys.remove(key);
                return true;
            }
            uncertainKeys.add(key);
            return false;
        }
    }

    public Record read(String key) {
        synchronized (lock) { return readLocked(key); }
    }

    public int unknownCount() {
        return countUnknown(preferences);
    }

    public static int countUnknown(SharedPreferences preferences) {
        Set<String> keys = new HashSet<>();
        Map<String, ?> all = preferences.getAll();
        for (String stored : all.keySet()) {
            if (stored.startsWith(PREFIX) && stored.endsWith(RECORD_SUFFIX)) {
                String key = stored.substring(0, stored.length() - RECORD_SUFFIX.length());
                Object raw = all.get(stored);
                if (raw instanceof String) {
                    Record record = decode((String) raw);
                    if (record.status == Status.UNKNOWN || record.status == Status.SENDING) keys.add(key);
                } else if (raw != null) {
                    keys.add(key);
                }
            } else if (stored.startsWith(PREFIX) && stored.endsWith(LEGACY_PENDING_SUFFIX)
                    && Boolean.TRUE.equals(all.get(stored))) {
                String key = stored.substring(0, stored.length() - LEGACY_PENDING_SUFFIX.length());
                if (Boolean.TRUE.equals(all.get(key))) continue;
                Object raw = all.get(recordKey(key));
                if (!(raw instanceof String)) keys.add(key);
                else {
                    Record record = decode((String) raw);
                    if (record.status == Status.UNKNOWN || record.status == Status.SENDING) keys.add(key);
                }
            }
        }
        return keys.size();
    }

    private boolean canBegin(Record current) {
        if (current == null || current.status == Status.FAILED) return true;
        if (current.status != Status.UNKNOWN || retryToken.isEmpty()) return false;
        return !retryToken.equals(current.retryToken);
    }

    private boolean matches(Record record, String attemptToken) {
        return record != null && attemptToken != null && attemptToken.equals(record.attemptToken);
    }

    private Record readLocked(String key) {
        if (Boolean.TRUE.equals(preferences.getAll().get(key))) {
            return new Record(Status.SENT, "", 0, retryToken);
        }
        Object raw = preferences.getAll().get(recordKey(key));
        if (raw instanceof String) {
            Record record = decode((String) raw);
            if (record.status == Status.SENDING && uncertainKeys.contains(key)) {
                return new Record(Status.UNKNOWN, record.attemptToken, record.timestamp, record.retryToken);
            }
            return record;
        }
        if (raw != null) return new Record(Status.UNKNOWN, "", 0, retryToken);
        if (Boolean.TRUE.equals(preferences.getAll().get(key + LEGACY_PENDING_SUFFIX))) {
            return new Record(Status.UNKNOWN, "", 0, legacyRetryTokens.getOrDefault(key, retryToken));
        }
        return null;
    }

    private void migrateLegacyPending() {
        synchronized (lock) {
            Map<String, ?> all = preferences.getAll();
            SharedPreferences.Editor editor = preferences.edit();
            boolean changed = false;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String stored = entry.getKey();
                if (!stored.startsWith(PREFIX) || !stored.endsWith(LEGACY_PENDING_SUFFIX)
                        || !Boolean.TRUE.equals(entry.getValue())) continue;
                String key = stored.substring(0, stored.length() - LEGACY_PENDING_SUFFIX.length());
                legacyRetryTokens.putIfAbsent(key, retryToken);
                if (!Boolean.TRUE.equals(all.get(key)) && !(all.get(recordKey(key)) instanceof String)) {
                    editor.putString(recordKey(key), encode(new Record(Status.UNKNOWN,
                            "", now, legacyRetryTokens.get(key))));
                }
                editor.remove(stored);
                changed = true;
            }
            if (changed && commit(editor)) legacyRetryTokens.clear();
        }
    }

    private void recoverSendingRecords() {
        synchronized (lock) {
            Map<String, ?> all = preferences.getAll();
            SharedPreferences.Editor editor = preferences.edit();
            boolean changed = false;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String stored = entry.getKey();
                if (!stored.startsWith(PREFIX) || !stored.endsWith(RECORD_SUFFIX)
                        || !(entry.getValue() instanceof String)) continue;
                Record record = decode((String) entry.getValue());
                if (record != null && record.status == Status.SENDING) {
                    String key = stored.substring(0, stored.length() - RECORD_SUFFIX.length());
                    uncertainKeys.add(key);
                    editor.putString(stored, encode(new Record(Status.UNKNOWN, record.attemptToken,
                            record.timestamp, record.retryToken)));
                    editor.remove(key + LEGACY_PENDING_SUFFIX);
                    changed = true;
                }
            }
            if (changed && commit(editor)) uncertainKeys.clear();
        }
    }

    private boolean save(String key, Record record) {
        return commit(preferences.edit().putString(recordKey(key), encode(record))
                .remove(key + LEGACY_PENDING_SUFFIX));
    }

    private String readRetryToken() {
        try { return preferences.getString(RETRY_TOKEN_KEY, ""); }
        catch (ClassCastException ignored) { return ""; }
    }

    private boolean commit(SharedPreferences.Editor editor) {
        try { return committer.commit(editor); }
        catch (RuntimeException ignored) { return false; }
    }

    private static String recordKey(String key) { return key + RECORD_SUFFIX; }

    private static String encode(Record record) {
        JSONObject json = new JSONObject();
        try {
            json.put("status", record.status.name().toLowerCase(java.util.Locale.ROOT));
            json.put("attempt", record.attemptToken);
            json.put("timestamp", record.timestamp);
            json.put("retry_token", record.retryToken);
            return json.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Record decode(String value) {
        try {
            JSONObject json = new JSONObject(value);
            Status status = Status.valueOf(json.optString("status", "unknown").toUpperCase(java.util.Locale.ROOT));
            return new Record(status, json.optString("attempt", ""), json.optLong("timestamp", 0),
                    json.optString("retry_token", ""));
        } catch (JSONException | IllegalArgumentException failure) {
            return new Record(Status.UNKNOWN, "", 0, "");
        }
    }
}
