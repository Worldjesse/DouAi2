package com.douai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;

import com.douai.module.config.LocalOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Counts durable native send reservations until a definite failure releases them. */
public final class AutomationRateLimiter {
    private static final String FILE = "douai_automation_limits";
    private static final String PREFIX = "send.";
    private static final Object LOCK = new Object();
    private static final int MAX_DAILY_LIMIT = 1_000_000;
    private static final int MAX_INTERVAL_MINUTES = 525_600;
    private static final long RETENTION_MILLIS = MAX_INTERVAL_MINUTES * 60_000L;
    private final SharedPreferences preferences;
    private final Clock clock;
    private final Committer committer;
    private final Supplier<ZoneId> zone;

    interface Committer { boolean commit(SharedPreferences.Editor editor); }

    public static final class Reservation {
        private final String key;
        private final String value;

        private Reservation(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public AutomationRateLimiter(Context context) {
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), Clock.systemUTC(),
                SharedPreferences.Editor::commit, ZoneId::systemDefault);
    }

    AutomationRateLimiter(SharedPreferences preferences, Clock clock, Committer committer) {
        this(preferences, clock, committer, clock::getZone);
    }

    AutomationRateLimiter(SharedPreferences preferences, Clock clock, Committer committer, Supplier<ZoneId> zone) {
        this.preferences = preferences;
        this.clock = clock;
        this.committer = committer;
        this.zone = zone;
    }

    public boolean canSend(String kind, long account, String userId, LocalOptions options) {
        if (!valid(kind, account, userId, options)) return false;
        synchronized (LOCK) {
            return allowed(read(), kind, account, userId, options, clock.millis());
        }
    }

    /** A null result means the caller must not invoke the native sender. */
    public Reservation tryReserve(String kind, long account, String userId, LocalOptions options) {
        if (!valid(kind, account, userId, options)) return null;
        synchronized (LOCK) {
            long now = clock.millis();
            List<Record> records = read();
            if (!allowed(records, kind, account, userId, options, now)) return null;
            String key = PREFIX + UUID.randomUUID();
            String encoded;
            try {
                encoded = new JSONObject().put("kind", kind).put("account", account)
                        .put("user", userId).put("at", now).toString();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            SharedPreferences.Editor editor = preferences.edit().putString(key, encoded);
            LocalDate today = date(now);
            for (Record record : records) {
                if (now >= record.timestamp && now - record.timestamp >= RETENTION_MILLIS
                        && !today.equals(date(record.timestamp))) editor.remove(record.key);
            }
            return committer.commit(editor) ? new Reservation(key, encoded) : null;
        }
    }

    /** Releases only this attempt; late or repeated callbacks cannot erase another send. */
    public void release(Reservation reservation) {
        if (reservation == null) return;
        synchronized (LOCK) {
            if (reservation.value.equals(preferences.getAll().get(reservation.key))) {
                committer.commit(preferences.edit().remove(reservation.key));
            }
        }
    }

    private boolean allowed(List<Record> records, String kind, long account, String userId,
                            LocalOptions options, long now) {
        if (records == null) return false;
        int dailyLimit = Math.max(0, Math.min(MAX_DAILY_LIMIT, options.integer(kind + "_daily_limit", 0)));
        long interval = intervalMinutes(kind, options) * 60_000L;
        int count = 0;
        LocalDate today = date(now);
        for (Record record : records) {
            if (record.account != account || !kind.equals(record.kind)) continue;
            if (dailyLimit > 0 && today.equals(date(record.timestamp)) && ++count >= dailyLimit) return false;
            if (interval > 0 && userId.equals(record.user)
                    && (record.timestamp > now || now - record.timestamp < interval)) return false;
        }
        return true;
    }

    private static int intervalMinutes(String kind, LocalOptions options) {
        return Math.max(0, Math.min(MAX_INTERVAL_MINUTES, options.integer(kind + "_user_cooldown_minutes", 0)));
    }

    private static boolean valid(String kind, long account, String userId, LocalOptions options) {
        return ("comment".equals(kind) || "reply".equals(kind)) && account > 0 && options != null
                && userId != null && (!userId.trim().isEmpty() || intervalMinutes(kind, options) == 0);
    }

    private LocalDate date(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(zone.get()).toLocalDate();
    }

    private List<Record> read() {
        List<Record> records = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            if (!(entry.getValue() instanceof String)) return null;
            try {
                JSONObject object = new JSONObject((String) entry.getValue());
                String kind = object.getString("kind");
                long account = object.getLong("account");
                long timestamp = object.getLong("at");
                if (!("comment".equals(kind) || "reply".equals(kind)) || account <= 0 || timestamp < 0) return null;
                records.add(new Record(entry.getKey(), kind, account, object.getString("user"), timestamp));
            } catch (JSONException | RuntimeException failure) { return null; }
        }
        return records;
    }

    private static final class Record {
        final String key;
        final String kind;
        final long account;
        final String user;
        final long timestamp;

        Record(String key, String kind, long account, String user, long timestamp) {
            this.key = key;
            this.kind = kind;
            this.account = account;
            this.user = user;
            this.timestamp = timestamp;
        }
    }
}
