package com.douai.module.analytics;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Opt-in analytics in the host's no-backup directory. No network or shared preferences.
 * Daily aggregates and hashed work/day identities expire after 365 local calendar days;
 * only 30 recent event labels are kept. The bounded writer never blocks feed hooks.
 */
public final class LocalAnalytics implements AutoCloseable {
    public static final int RETENTION_DAYS = 365;
    public static final int VISIT_RETENTION_DAYS = 30;
    private static final long MAX_VISIBLE_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_VISITS = 20_000;
    private static final int QUEUE_CAPACITY = 256;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd");
    private static final Map<String, LocalAnalytics> INSTANCES = new HashMap<>();
    private static volatile LocalAnalytics active;
    private static long automaticAdvanceAt = -1;

    private final File directory;
    private final AtomicFile enabledFile;
    private final Clock clock;
    private final Supplier<ZoneId> zone;
    private final ThreadPoolExecutor writer;
    private final Object stateLock = new Object();
    private final Object databaseLock = new Object();
    private volatile boolean enabled;
    private volatile boolean closed;
    private volatile long generation;
    private volatile long storageEpoch;
    private String lastWorkHash;
    private LocalDate lastWorkDay;
    private SQLiteDatabase database;
    private LocalDate lastPrunedDay;
    private final AtomicLong queueDropped = new AtomicLong();
    private Visit currentVisit;

    private static final class Visit {
        final String token, workHash;
        final long generation, started;
        long visibleMillis, persistedMillis;
        Visit(String token, String workHash, long generation, long started) {
            this.token = token; this.workHash = workHash; this.generation = generation; this.started = started;
        }
    }

    public static LocalAnalytics get(Context context) {
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        File directory = new File(application.getNoBackupFilesDir(), "douai-analytics");
        synchronized (INSTANCES) {
            String key = directory.getAbsolutePath();
            LocalAnalytics instance = INSTANCES.get(key);
            if (instance == null || instance.closed) {
                instance = new LocalAnalytics(directory, Clock.systemUTC(), ZoneId::systemDefault);
                INSTANCES.put(key, instance);
            }
            return instance;
        }
    }

    public static void initialize(Context context) { active = get(context); }

    public static synchronized void markAutomaticAdvance() { automaticAdvanceAt = android.os.SystemClock.elapsedRealtime(); }
    public static synchronized void cancelAutomaticAdvance() { automaticAdvanceAt = -1; }
    public static synchronized String consumeAdvanceSource() {
        long elapsed = android.os.SystemClock.elapsedRealtime() - automaticAdvanceAt;
        boolean automatic = automaticAdvanceAt >= 0 && elapsed >= 0 && elapsed <= 3000;
        automaticAdvanceAt = -1;
        return automatic ? "automatic" : "unknown";
    }

    /** Counts the action itself; only an explicit matching current work can establish a joint observation. */
    public static void action(String workId, String key, String label, String source, String stage) {
        LocalAnalytics instance = active;
        if (instance != null) instance.recordAction(instance.generation, workId, key, label, source, stage);
    }

    /** Call only when a module operation actually runs; merely enabling a setting is not usage. */
    public static void feature(String key, String label) {
        LocalAnalytics instance = active;
        if (instance != null) instance.recordFeature(key, label);
    }

    LocalAnalytics(File directory, Clock clock) { this(directory, clock, clock::getZone); }

    private LocalAnalytics(File directory, Clock clock, Supplier<ZoneId> zone) {
        this.directory = directory;
        this.enabledFile = new AtomicFile(new File(directory, "enabled"));
        this.clock = clock;
        this.zone = zone;
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "douai-local-analytics");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        try {
            enabled = "1".equals(new String(enabledFile.readFully(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { enabled = false; }
    }

    public boolean isEnabled() { return enabled && !closed; }

    /** Captured by deferred host observations; pass it to the tokenized recordView overload. */
    public long generation() { return generation; }

    /** Stops acceptance immediately; queued events from an earlier generation are discarded. */
    public void setEnabled(boolean value) {
        synchronized (stateLock) {
            if (closed) return;
            enabled = false;
            generation++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            cancelAutomaticAdvance();
            try {
                persistEnabled(value);
                enabled = value;
                long token = storageEpoch;
                long timestamp = clock.millis();
                enqueue(() -> recordCollectionBoundary(token, timestamp, value));
            } catch (Exception ignored) {
                try { enabledFile.delete(); } catch (Exception ignoredDelete) { /* Fail closed. */ }
            }
        }
    }

    /**
     * Counts consecutive distinct visible works. A,A,B,A is three visits and two unique works.
     * durationMillis is the known content length, not watched duration (nonpositive = unknown).
     */
    public void recordView(String workId, String type, String authorId, String authorName,
            List<String> topics, boolean advertisement, long durationMillis) {
        recordView(generation, workId, type, authorId, authorName, topics, advertisement, durationMillis);
    }

    /** Rejects observations posted before a clear or enable/disable transition. */
    public void recordView(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, boolean advertisement, long durationMillis) {
        recordViewInternal(expectedGeneration, workId, type, authorId, authorName, topics, advertisement,
                durationMillis, null);
    }

    public String beginVisit(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, boolean advertisement, long contentDuration, String source) {
        return recordViewInternal(expectedGeneration, workId, type, authorId, authorName, topics,
                advertisement, contentDuration, normalizeSource(source));
    }

    private String recordViewInternal(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, boolean advertisement, long durationMillis, String source) {
        if (!isEnabled() || workId == null || workId.trim().isEmpty()) return null;
        try {
            String workHash = hash(workId.trim());
            String typeKey = normalizeType(type);
            String authorLabel = clean(authorName, 120);
            if (authorLabel.isEmpty()) authorLabel = "未识别作者";
            String authorKey = hash(authorId != null && !authorId.trim().isEmpty()
                    ? "id:" + authorId.trim() : "name:" + authorLabel);
            Set<String> uniqueTopics = new LinkedHashSet<>();
            if (topics != null) {
                for (String topic : topics) {
                    String normalized = clean(topic, 100);
                    while (normalized.startsWith("#")) normalized = normalized.substring(1).trim();
                    if (!normalized.isEmpty()) uniqueTopics.add(normalized);
                    if (uniqueTopics.size() == 32) break;
                }
            }
            final String finalAuthorLabel = authorLabel;
            synchronized (stateLock) {
                if (!isEnabled() || generation != expectedGeneration) return null;
                long token = generation;
                long timestamp = clock.millis();
                ZonedDateTime local = Instant.ofEpochMilli(timestamp).atZone(zone.get());
                if (workHash.equals(lastWorkHash) && local.toLocalDate().equals(lastWorkDay)) {
                    // Repeated selection callbacks do not inflate visits. A visibility resume may
                    // reopen a bounded segment, while retaining the existing work-view dedupe.
                    if (source == null) return null;
                    if (currentVisit != null && currentVisit.workHash.equals(workHash)) return currentVisit.token;
                }
                boolean countView = !workHash.equals(lastWorkHash) || !local.toLocalDate().equals(lastWorkDay);
                String visitToken = source == null ? null : UUID.randomUUID().toString();
                boolean queued = enqueue(() -> write(token, local.toLocalDate(), db -> {
                    if (visitToken != null) {
                        db.execSQL("INSERT INTO visits(token,work_hash,type,day,hour,source,started,visible_ms,ended,end_reason) VALUES (?,?,?,?,?,?,?,0,0,'')",
                                new Object[]{visitToken, workHash, typeKey, local.toLocalDate().toString(), local.getHour(), source, timestamp});
                        for (String topic : uniqueTopics) db.execSQL("INSERT OR IGNORE INTO visit_topics(token,topic) VALUES (?,?)", new Object[]{visitToken, topic});
                        trimVisits(db);
                    }
                    if (!countView) return;
                    addDaily(db, local.toLocalDate(), 1, 0, advertisement ? 1 : 0,
                            Math.max(0, durationMillis));
                    String day = local.toLocalDate().toString();
                    db.execSQL("INSERT OR IGNORE INTO work_days(day, work_hash) VALUES (?, ?)",
                            new Object[]{day, workHash});
                    db.execSQL("INSERT OR IGNORE INTO hours(day, hour, count) VALUES (?, ?, 0)",
                            new Object[]{day, local.getHour()});
                    db.execSQL("UPDATE hours SET count=count+1 WHERE day=? AND hour=?",
                            new Object[]{day, local.getHour()});
                    addRank(db, day, "type", typeKey, typeLabel(typeKey));
                    addRank(db, day, "author", authorKey, finalAuthorLabel);
                    for (String topic : uniqueTopics) addRank(db, day, "topic", topic, topic);
                    addRecent(db, timestamp, typeLabel(typeKey) + " · " + finalAuthorLabel, "view");
                }));
                if (queued) {
                    lastWorkHash = workHash;
                    lastWorkDay = local.toLocalDate();
                    if (visitToken != null) currentVisit = new Visit(visitToken, workHash, token, timestamp);
                    return visitToken;
                }
            }
        } catch (Exception ignored) { /* Malformed host metadata must never disrupt the feed. */ }
        return null;
    }

    /** cumulativeVisibleMillis comes only from bounded foreground visibility ticks. */
    public void pulseVisit(String token, long cumulativeVisibleMillis) { updateVisit(token, cumulativeVisibleMillis, null); }

    public void finishVisit(String token, long visibleMillis, String endReason) { updateVisit(token, visibleMillis, clean(endReason, 80)); }

    private void updateVisit(String token, long cumulativeVisibleMillis, String endReason) {
        if (token == null) return;
        synchronized (stateLock) {
            Visit visit = currentVisit;
            if (!isEnabled() || visit == null || !visit.token.equals(token) || visit.generation != generation) return;
            long observed = Math.max(0, Math.min(MAX_VISIBLE_MILLIS,
                    Math.min(cumulativeVisibleMillis, Math.max(0, clock.millis() - visit.started))));
            visit.visibleMillis = Math.max(visit.visibleMillis, observed);
            if (endReason == null && visit.visibleMillis - visit.persistedMillis < 3_000) return;
            long total = visit.visibleMillis;
            boolean queued = enqueue(() -> write(visit.generation, today(), db -> db.execSQL(
                    "UPDATE visits SET visible_ms=MAX(visible_ms,?),ended=?,end_reason=? WHERE token=?",
                    new Object[]{total, endReason == null ? 0 : 1, endReason == null ? "" : endReason, token})));
            if (queued) visit.persistedMillis = total;
            if (endReason != null) currentVisit = null;
        }
    }

    public String currentVisitToken(String workId) {
        try {
            if (workId == null) return null;
            String workHash = hash(workId.trim());
            synchronized (stateLock) {
                return isEnabled() && currentVisit != null && currentVisit.generation == generation
                        && currentVisit.workHash.equals(workHash) ? currentVisit.token : null;
            }
        } catch (Exception ignored) { return null; }
    }

    public void recordAction(long expectedGeneration, String workId, String key, String label, String source, String stage) {
        recordAction(expectedGeneration, currentVisitToken(workId), workId, key, label, source, stage);
    }

    public void recordAction(long expectedGeneration, String expectedVisit, String workId, String key,
            String label, String source, String stage) {
        String featureKey = clean(key, 100), featureLabel = clean(label, 120);
        if (featureKey.isEmpty() || featureLabel.isEmpty()) return;
        String actionSource = normalizeSource(source), actionStage = normalizeStage(stage);
        synchronized (stateLock) {
            if (!isEnabled() || generation != expectedGeneration) return;
            String linked = currentVisitToken(workId);
            final String visitToken = expectedVisit != null && expectedVisit.equals(linked) ? linked : null;
            long timestamp = clock.millis();
            LocalDate day = Instant.ofEpochMilli(timestamp).atZone(zone.get()).toLocalDate();
            enqueue(() -> write(expectedGeneration, day, db -> {
                addFeature(db, day, timestamp, featureKey, featureLabel, actionSource);
                db.execSQL("INSERT INTO actions(day,token,feature,source,stage,ts) VALUES (?,?,?,?,?,?)",
                        new Object[]{day.toString(), visitToken, featureKey, actionSource, actionStage, timestamp});
                db.execSQL("DELETE FROM actions WHERE id NOT IN (SELECT id FROM actions ORDER BY id DESC LIMIT 30000)");
            }));
        }
    }

    void recordFeature(String key, String label) {
        if (!isEnabled()) return;
        String featureKey = clean(key, 100);
        String featureLabel = clean(label, 120);
        if (featureKey.isEmpty() || featureLabel.isEmpty()) return;
        synchronized (stateLock) {
            if (!isEnabled()) return;
            long token = generation;
            long timestamp = clock.millis();
            LocalDate day = Instant.ofEpochMilli(timestamp).atZone(zone.get()).toLocalDate();
            enqueue(() -> write(token, day, db -> {
                addFeature(db, day, timestamp, featureKey, featureLabel, featureSource(featureKey));
            }));
        }
    }

    private void addFeature(SQLiteDatabase db, LocalDate day, long timestamp, String key, String label, String source) {
        addDaily(db, day, 0, 1, 0, 0);
        addRank(db, day.toString(), "feature", key, label);
        addRank(db, day.toString(), "feature_source", source, sourceLabel(source));
        addRecent(db, timestamp, label, "feature");
    }

    /** Off-main only. Waits for previously queued writes. days=0 selects retained history. */
    public AnalyticsSnapshot snapshot(int days) {
        if (closed) return AnalyticsSnapshot.empty();
        FutureTask<AnalyticsSnapshot> task = new FutureTask<>(() -> readSnapshot(days));
        if (!enqueue(task)) return readSnapshot(days);
        try { return task.get(); }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return AnalyticsSnapshot.empty();
        } catch (Exception ignored) { throw new IllegalStateException("无法读取本地统计数据"); }
    }

    /** Worker only. A logical snapshot, including settled queued writes; never copies SQLite files. */
    org.json.JSONObject exportBackup() {
        if (closed) throw new IllegalStateException("本地分析存储已关闭");
        FutureTask<org.json.JSONObject> task = new FutureTask<>(() -> {
            synchronized (databaseLock) {
                if (closed) throw new IllegalStateException("本地分析存储已关闭");
                SQLiteDatabase db = database == null && !new File(directory, "usage.db").exists()
                        ? null : openDatabase();
                if (db == null) return AnalyticsBackup.capture(null, clock.millis(), queueDropped.get());
                db.beginTransaction();
                try {
                    prune(db, today());
                    org.json.JSONObject snapshot = AnalyticsBackup.capture(db, clock.millis(), queueDropped.get());
                    db.setTransactionSuccessful(); return snapshot;
                } finally { db.endTransaction(); }
            }
        });
        if (!enqueue(task)) throw new IllegalStateException("本地分析写入繁忙，请稍后重试备份");
        try { return task.get(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("本地分析备份已中断", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("无法备份本地分析数据", cause);
        }
    }

    /** All imported rows were validated against fixed columns before this transaction starts. */
    void replaceBackup(AnalyticsBackup.Snapshot snapshot, Runnable beforeCommit) {
        fenceBackup();
        synchronized (databaseLock) {
            boolean committed = false;
            try {
                if (closed) throw new IllegalStateException("本地分析存储已关闭");
                SQLiteDatabase db = openDatabase();
                db.beginTransaction();
                try {
                    AnalyticsBackup.replaceTables(db, snapshot);
                    lastPrunedDay = null;
                    prune(db, today());
                    beforeCommit.run();
                    synchronized (stateLock) {
                        // The short continuation boundary also fences observations collected while
                        // replacement was being prepared. Later switch changes retain this epoch.
                        fenceBackup();
                        db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,?)",
                                new Object[]{clock.millis(), enabled ? 1 : 0});
                        db.setTransactionSuccessful();
                    }
                } finally { db.endTransaction(); }
                committed = true;
                queueDropped.set(0);
            } finally {
                // Reject every observation accepted before or while replacement was in progress.
                // The local collection flag is deliberately neither read from nor changed by a backup.
                if (!committed) fenceBackup();
                lastPrunedDay = null;
            }
        }
    }

    private void fenceBackup() {
        synchronized (stateLock) {
            generation++; storageEpoch++;
            lastWorkHash = null; lastWorkDay = null; currentVisit = null;
            cancelAutomaticAdvance();
        }
    }

    /** Off-main only. Returns after deletion; stale queued events cannot repopulate the database. */
    public void clear() {
        synchronized (stateLock) {
            generation++;
            storageEpoch++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            queueDropped.set(0);
            cancelAutomaticAdvance();
        }
        synchronized (databaseLock) {
            try {
                File databaseFile = new File(directory, "usage.db");
                File ownedDirectory = directory.getCanonicalFile();
                for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
                    File ownedFile = new File(directory, "usage.db" + suffix);
                    if (!ownedDirectory.equals(ownedFile.getCanonicalFile().getParentFile()))
                        throw new IllegalStateException("Analytics database path is outside local storage");
                }
                if (database != null) {
                    database.close();
                    database = null;
                }
                // Explicit user clearing must also recover a corrupt database. Do not open it:
                // delete only this database and SQLite sidecars, preserving the opt-in flag.
                SQLiteDatabase.deleteDatabase(databaseFile);
                for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
                    if (new File(directory, "usage.db" + suffix).exists())
                        throw new IllegalStateException("Analytics database could not be removed");
                }
                lastPrunedDay = null;
            } catch (Exception ignored) {
                failClosed();
                throw new IllegalStateException("无法清除本地统计数据");
            } finally {
                // Observations accepted while deletion waited for the DB belong to the clear
                // boundary as well. Fence them and reset their consecutive-view suppression.
                synchronized (stateLock) {
                    generation++;
                    storageEpoch++;
                    lastWorkHash = null;
                    lastWorkDay = null;
                    currentVisit = null;
                    queueDropped.set(0);
                }
            }
        }
    }

    @Override public void close() {
        synchronized (stateLock) {
            closed = true;
            enabled = false;
            generation++;
            storageEpoch++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            cancelAutomaticAdvance();
        }
        writer.shutdown();
        synchronized (databaseLock) {
            if (database != null) {
                database.close();
                database = null;
            }
        }
        if (active == this) active = null;
    }

    private boolean enqueue(Runnable runnable) {
        try { writer.execute(runnable); return true; }
        catch (RejectedExecutionException ignored) { if (!closed) queueDropped.incrementAndGet(); return false; }
    }

    private interface Mutation { void run(SQLiteDatabase db); }

    private void write(long token, LocalDate eventDay, Mutation mutation) {
        synchronized (databaseLock) {
            if (closed || !enabled || generation != token) return;
            try {
                LocalDate today = today();
                if (eventDay.isBefore(today.minusDays(RETENTION_DAYS - 1L))) return;
                SQLiteDatabase db = openDatabase();
                db.beginTransaction();
                try {
                    prune(db, today);
                    mutation.run(db);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            } catch (Exception ignored) { lastPrunedDay = null; failClosed(); }
        }
    }

    private SQLiteDatabase openDatabase() {
        if (database != null && database.isOpen()) return database;
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Local analytics directory unavailable");
        // Android's default corruption handler deletes the DB and may reopen an empty one.
        // Preserve failed storage and surface the error instead of silently losing a report.
        SQLiteDatabase opened = SQLiteDatabase.openDatabase(new File(directory, "usage.db").getPath(),
                null, SQLiteDatabase.CREATE_IF_NECESSARY,
                corrupt -> { throw new IllegalStateException("Local analytics database corrupt"); });
        try {
            try (Cursor secureDelete = opened.rawQuery("PRAGMA secure_delete=ON", null)) {
                if (!secureDelete.moveToFirst() || secureDelete.getInt(0) != 1)
                    throw new IllegalStateException("Local storage cannot securely clear records");
            }
            if (opened.getVersion() > 2) throw new IllegalStateException("Unsupported analytics schema");
            opened.beginTransaction();
            try {
            opened.execSQL("CREATE TABLE IF NOT EXISTS daily(day TEXT PRIMARY KEY, views INTEGER NOT NULL, features INTEGER NOT NULL, ads INTEGER NOT NULL, content_duration INTEGER NOT NULL)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS hours(day TEXT NOT NULL, hour INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(day,hour))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS work_days(day TEXT NOT NULL, work_hash TEXT NOT NULL, PRIMARY KEY(day,work_hash))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS ranks(day TEXT NOT NULL, kind TEXT NOT NULL, item_key TEXT NOT NULL, label TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(day,kind,item_key))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS recent(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, label TEXT NOT NULL, kind TEXT NOT NULL)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS visits(token TEXT PRIMARY KEY,work_hash TEXT NOT NULL,type TEXT NOT NULL,day TEXT NOT NULL,hour INTEGER NOT NULL,source TEXT NOT NULL,started INTEGER NOT NULL,visible_ms INTEGER NOT NULL,ended INTEGER NOT NULL,end_reason TEXT NOT NULL)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS visit_day ON visits(day)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS visit_topics(token TEXT NOT NULL,topic TEXT NOT NULL,PRIMARY KEY(token,topic))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS actions(id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL,token TEXT,feature TEXT NOT NULL,source TEXT NOT NULL,stage TEXT NOT NULL,ts INTEGER NOT NULL)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS action_day ON actions(day)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS collection_boundaries(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,enabled INTEGER NOT NULL)");
            opened.setVersion(2);
            opened.setTransactionSuccessful();
            } finally { opened.endTransaction(); }
            database = opened;
            return opened;
        } catch (RuntimeException failure) { opened.close(); throw failure; }
    }

    private void addDaily(SQLiteDatabase db, LocalDate day, long views, long features, long ads, long duration) {
        db.execSQL("INSERT OR IGNORE INTO daily VALUES (?,0,0,0,0)", new Object[]{day.toString()});
        db.execSQL("UPDATE daily SET views=views+?,features=features+?,ads=ads+?,content_duration=content_duration+? WHERE day=?",
                new Object[]{views, features, ads, duration, day.toString()});
    }

    private void addRank(SQLiteDatabase db, String day, String kind, String key, String label) {
        db.execSQL("INSERT OR IGNORE INTO ranks VALUES (?,?,?,?,0)", new Object[]{day, kind, key, label});
        db.execSQL("UPDATE ranks SET count=count+1,label=? WHERE day=? AND kind=? AND item_key=?",
                new Object[]{label, day, kind, key});
    }

    private void addRecent(SQLiteDatabase db, long timestamp, String label, String kind) {
        db.execSQL("INSERT OR IGNORE INTO meta(key,value) VALUES ('first_seen',?)", new Object[]{timestamp});
        db.execSQL("INSERT INTO recent(ts,label,kind) VALUES (?,?,?)", new Object[]{timestamp, label, kind});
        db.execSQL("DELETE FROM recent WHERE id NOT IN (SELECT id FROM recent ORDER BY id DESC LIMIT 30)");
    }

    private void prune(SQLiteDatabase db, LocalDate today) {
        if (today.equals(lastPrunedDay)) return;
        String cutoff = today.minusDays(RETENTION_DAYS - 1L).toString();
        for (String table : new String[]{"daily", "hours", "work_days", "ranks"})
            db.delete(table, "day < ?", new String[]{cutoff});
        long cutoffTime = LocalDate.parse(cutoff).atStartOfDay(zone.get()).toInstant().toEpochMilli();
        db.delete("recent", "ts < ?", new String[]{Long.toString(cutoffTime)});
        db.delete("collection_boundaries", "ts < ?", new String[]{Long.toString(cutoffTime)});
        String visitCutoff = today.minusDays(VISIT_RETENTION_DAYS - 1L).toString();
        db.delete("visits", "day < ?", new String[]{visitCutoff});
        db.delete("actions", "day < ?", new String[]{visitCutoff});
        db.execSQL("DELETE FROM visit_topics WHERE token NOT IN (SELECT token FROM visits)");
        lastPrunedDay = today;
    }

    private static void trimVisits(SQLiteDatabase db) {
        db.execSQL("DELETE FROM visits WHERE token NOT IN (SELECT token FROM visits ORDER BY started DESC,rowid DESC LIMIT " + MAX_VISITS + ")");
        db.execSQL("DELETE FROM visit_topics WHERE token NOT IN (SELECT token FROM visits)");
    }

    private void recordCollectionBoundary(long token, long timestamp, boolean value) {
        synchronized (databaseLock) {
            if (closed || storageEpoch != token) return;
            try {
                SQLiteDatabase db = openDatabase();
                db.execSQL("INSERT OR IGNORE INTO meta(key,value) VALUES ('collection_start',?)", new Object[]{timestamp});
                db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,?)", new Object[]{timestamp, value ? 1 : 0});
            } catch (Exception ignored) { failClosed(); }
        }
    }

    private AnalyticsSnapshot readSnapshot(int days) {
        synchronized (databaseLock) {
            if (closed || (database == null && !new File(directory, "usage.db").exists()))
                return AnalyticsSnapshot.empty();
            try {
                SQLiteDatabase db = openDatabase();
                LocalDate end = today();
                prune(db, end);
                LocalDate start = end.minusDays(days <= 0 ? RETENTION_DAYS - 1L
                        : Math.min(days, RETENTION_DAYS) - 1L);
                String[] range = {start.toString(), end.toString()};
                long views = 0, features = 0, ads = 0;
                Set<LocalDate> activeDates = new HashSet<>();
                Map<LocalDate, Long> dailyViews = new HashMap<>();
                try (Cursor cursor = db.rawQuery("SELECT day,views,features,ads FROM daily WHERE day BETWEEN ? AND ? ORDER BY day", range)) {
                    while (cursor.moveToNext()) {
                        LocalDate date = LocalDate.parse(cursor.getString(0));
                        activeDates.add(date);
                        dailyViews.put(date, cursor.getLong(1));
                        views += cursor.getLong(1);
                        features += cursor.getLong(2);
                        ads += cursor.getLong(3);
                    }
                }
                long unique = scalar(db, "SELECT COUNT(DISTINCT work_hash) FROM work_days WHERE day BETWEEN ? AND ?", range);
                long first = scalar(db, "SELECT value FROM meta WHERE key='first_seen'", null);
                long[] hours = new long[24];
                try (Cursor cursor = db.rawQuery("SELECT hour,SUM(count) FROM hours WHERE day BETWEEN ? AND ? GROUP BY hour", range)) {
                    while (cursor.moveToNext()) {
                        int hour = cursor.getInt(0);
                        if (hour >= 0 && hour < hours.length) hours[hour] = cursor.getLong(1);
                    }
                }
                List<AnalyticsSnapshot.Recent> recent = new ArrayList<>();
                long from = start.atStartOfDay(zone.get()).toInstant().toEpochMilli();
                long until = end.plusDays(1).atStartOfDay(zone.get()).toInstant().toEpochMilli();
                try (Cursor cursor = db.rawQuery("SELECT ts,label,kind FROM recent WHERE ts>=? AND ts<? ORDER BY ts DESC,id DESC LIMIT 30",
                        new String[]{Long.toString(from), Long.toString(until)})) {
                    while (cursor.moveToNext()) recent.add(new AnalyticsSnapshot.Recent(cursor.getLong(0), cursor.getString(1), cursor.getString(2)));
                }
                int streak = 0;
                LocalDate streakDay = activeDates.contains(end) ? end : end.minusDays(1);
                while (activeDates.contains(streakDay)) { streak++; streakDay = streakDay.minusDays(1); }
                if (days <= 0 && !activeDates.isEmpty()) start = Collections.min(activeDates);
                return new AnalyticsSnapshot(views, features, unique, ads, activeDates.size(), streak, first,
                        trend(start, end, dailyViews), ranks(db, range, "type"), ranks(db, range, "author"),
                        ranks(db, range, "topic"), ranks(db, range, "feature"), hours, recent,
                        readPortrait(db, start, end, views, features, unique, first));
            } catch (Exception ignored) {
                failClosed();
                throw new IllegalStateException("无法读取本地统计数据");
            }
        }
    }

    private PortraitSnapshot readPortrait(SQLiteDatabase db, LocalDate start, LocalDate end,
            long views, long features, long unique, long firstSeen) {
        String[] range = {start.toString(), end.toString()};
        List<AnalyticsSnapshot.Rank> allAuthors = allRanks(db, range, "author");
        allAuthors.removeIf(rank -> "未识别作者".equals(rank.label));
        List<AnalyticsSnapshot.Rank> allTopics = allRanks(db, range, "topic");
        List<AnalyticsSnapshot.Rank> allTypes = allRanks(db, range, "type");
        List<AnalyticsSnapshot.Rank> sources = allRanks(db, range, "feature_source");
        long legacySources = features - PortraitMetrics.total(sources);
        if (legacySources > 0) {
            List<AnalyticsSnapshot.Rank> merged = new ArrayList<>();
            boolean added = false;
            for (AnalyticsSnapshot.Rank rank : sources) {
                if (rank.label.equals("来源未知")) { merged.add(new AnalyticsSnapshot.Rank(rank.label, rank.count + legacySources)); added = true; }
                else merged.add(rank);
            }
            if (!added) merged.add(new AnalyticsSnapshot.Rank("来源未知", legacySources));
            sources = merged;
        }
        Map<String, long[]> totals = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT day,views,features FROM daily WHERE day BETWEEN ? AND ?", range)) {
            while (cursor.moveToNext()) totals.put(cursor.getString(0), new long[]{cursor.getLong(1), cursor.getLong(2)});
        }
        Map<String, Long> dayUnique = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT day,COUNT(*) FROM work_days WHERE day BETWEEN ? AND ? GROUP BY day", range)) {
            while (cursor.moveToNext()) dayUnique.put(cursor.getString(0), cursor.getLong(1));
        }
        Map<String, Map<String, Long>> dayTypes = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT day,label,SUM(count) FROM ranks WHERE day BETWEEN ? AND ? AND kind='type' GROUP BY day,item_key ORDER BY item_key", range)) {
            while (cursor.moveToNext()) dayTypes.computeIfAbsent(cursor.getString(0), ignored -> new LinkedHashMap<>()).put(cursor.getString(1), cursor.getLong(2));
        }
        List<PortraitSnapshot.Day> days = new ArrayList<>();
        int browseDays = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            String date = day.toString();
            long[] total = totals.getOrDefault(date, new long[2]);
            if (total[0] > 0) browseDays++;
            days.add(new PortraitSnapshot.Day(date, total[0], total[1], dayUnique.getOrDefault(date, 0L),
                    dayTypes.getOrDefault(date, Collections.emptyMap())));
        }
        long[][] weekdayHours = new long[7][24];
        long night = 0, weekend = 0;
        try (Cursor cursor = db.rawQuery("SELECT day,hour,count FROM hours WHERE day BETWEEN ? AND ?", range)) {
            while (cursor.moveToNext()) {
                int weekday = LocalDate.parse(cursor.getString(0)).getDayOfWeek().getValue() - 1;
                int hour = cursor.getInt(1);
                if (hour < 0 || hour >= 24) continue;
                long count = cursor.getLong(2);
                weekdayHours[weekday][hour] += count;
                if (hour < 6) night += count;
                if (weekday >= 5) weekend += count;
            }
        }
        long repeated = scalar(db, "SELECT COUNT(*) FROM (SELECT work_hash FROM work_days WHERE day BETWEEN ? AND ? GROUP BY work_hash HAVING COUNT(*)>1)", range);
        long captured = scalar(db, "SELECT COUNT(*) FROM visits WHERE day BETWEEN ? AND ?", range);
        long finished = scalar(db, "SELECT COUNT(*) FROM visits WHERE day BETWEEN ? AND ? AND ended=1", range);
        long visible = scalar(db, "SELECT COALESCE(SUM(visible_ms),0) FROM visits WHERE day BETWEEN ? AND ?", range);
        long persistedDropped = scalar(db, "SELECT value FROM meta WHERE key='queue_dropped'", null);
        long newDropped = queueDropped.getAndSet(0);
        long dropped = persistedDropped + newDropped;
        if (newDropped > 0) db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES ('queue_dropped',?)", new Object[]{dropped});
        long collectionStart = scalar(db, "SELECT value FROM meta WHERE key='collection_start'", null);
        long earliest = firstSeen > 0 && collectionStart > 0 ? Math.min(firstSeen, collectionStart) : Math.max(firstSeen, collectionStart);
        PortraitSnapshot.Comparison comparison = comparison(db, start, end, earliest);
        PortraitSnapshot.Comparison week = comparison(db, end.minusDays(6), end, earliest);
        PortraitSnapshot.Comparison month = comparison(db, end.minusDays(29), end, earliest);
        List<PortraitSnapshot.Joint> topicHours = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT v.hour,t.topic,COUNT(*) AS n FROM visits v JOIN visit_topics t ON v.token=t.token WHERE v.day BETWEEN ? AND ? GROUP BY v.hour,t.topic ORDER BY n DESC,t.topic ASC,v.hour ASC LIMIT 48", range)) {
            while (cursor.moveToNext()) topicHours.add(new PortraitSnapshot.Joint(cursor.getInt(0) + "时", cursor.getString(1), cursor.getLong(2)));
        }
        List<PortraitSnapshot.Joint> typeActions = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT v.type,a.feature,a.source,a.stage,COUNT(*) AS n FROM actions a JOIN visits v ON a.token=v.token WHERE a.day BETWEEN ? AND ? GROUP BY v.type,a.feature,a.source,a.stage ORDER BY n DESC,a.feature ASC LIMIT 48", range)) {
            while (cursor.moveToNext()) typeActions.add(new PortraitSnapshot.Joint(typeLabel(cursor.getString(0)),
                    featureLabel(cursor.getString(1)) + " · " + sourceLabel(cursor.getString(2)) + " · " + stageLabel(cursor.getString(3)), cursor.getLong(4)));
        }
        List<String> coverage = new ArrayList<>();
        coverage.add("统计窗口 " + start + " 至 " + end + "；今天尚未结束。浏览次数包括再次刷到；空白日可能未使用、关闭采集或未覆盖。");
        coverage.add("日期汇总最多保留 365 天；关联可见片段保留 30 天且最多 20,000 段，动作最多 30,000 条。旧汇总不会补造关联。");
        coverage.add("停留是前台有焦点的可见采样，不保证识别同窗口遮挡或实际注视，也不等于完整观看或喜欢。约每 3 秒保存检查点，进程终止可能丢失未落盘尾部；单片段上限 30 分钟。");
        coverage.add("话题占比以全部话题出现次数为分母，一条作品可以包含多个话题；作者与话题均匀度来自全量分布，不只 Top 10。");
        coverage.add("已识别作者的记录 " + PortraitMetrics.total(allAuthors) + " / 总浏览 " + views + "；未知作者不计入作者数量、均匀度和集中度，作者占比以已识别作者记录为分母。");
        coverage.add("主动、自动、系统与未知来源分别统计；旧版功能事件来源未知。动作阶段分别计数，不能直接当作独立任务数。");
        coverage.add("时段关联采用可见片段开始小时；关联图只展示实际同段记录中次数最多的 48 组。未关联动作仍计入功能次数，不进入作品关联图。");
        if (earliest > 0) coverage.add("可追溯记录起点：" + Instant.ofEpochMilli(earliest).atZone(zone.get()).toLocalDate() + "；没有记录的更早日期不能当作零使用。");
        coverage.add("本地写入队列自清空以来拒绝 " + dropped + " 个任务；拒绝会导致样本缺失。采集不覆盖宿主所有页面。");
        String evidenceLevel = views == 0 ? "暂无浏览样本" : views < 50 || browseDays < 3 ? "样本较少，仅作记录描述" : views < 200 || browseDays < 7 ? "已有跨日样本，仍受覆盖限制" : "跨日样本较充足，仍只代表已记录行为";
        return new PortraitSnapshot(start.toString(), end.toString(), days, weekdayHours,
                PortraitMetrics.headAndTail(allTopics, 8), PortraitMetrics.headAndTail(allAuthors, 8), sources,
                browseDays, allAuthors.size(), allTopics.size(), PortraitMetrics.total(allTopics), repeated,
                captured, finished, visible, dropped, PortraitMetrics.diversity(allAuthors), PortraitMetrics.diversity(allTopics),
                allAuthors.isEmpty() ? 0 : PortraitMetrics.ratio(allAuthors.get(0).count, PortraitMetrics.total(allAuthors)),
                allTopics.isEmpty() ? 0 : PortraitMetrics.ratio(allTopics.get(0).count, PortraitMetrics.total(allTopics)),
                PortraitMetrics.ratio(repeated, unique), PortraitMetrics.ratio(night, views), PortraitMetrics.ratio(weekend, views),
                comparison, week, month, PortraitMetrics.insights(views, features, unique, browseDays, allAuthors,
                        allTopics, allTypes, sources, repeated, PortraitMetrics.ratio(night, views), PortraitMetrics.ratio(weekend, views), captured, visible, comparison),
                topicHours, typeActions, coverage, evidenceLevel);
    }

    private PortraitSnapshot.Comparison comparison(SQLiteDatabase db, LocalDate start, LocalDate end, long first) {
        long length = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate previousEnd = start.minusDays(1), previousStart = start.minusDays(length);
        String[] current = {start.toString(), end.toString()}, previous = {previousStart.toString(), previousEnd.toString()};
        long from = previousStart.atStartOfDay(zone.get()).toInstant().toEpochMilli();
        long until = start.atStartOfDay(zone.get()).toInstant().toEpochMilli();
        boolean dateComplete = first > 0 && first <= from && !previousStart.isBefore(today().minusDays(RETENTION_DAYS - 1L));
        long stopped = scalar(db, "SELECT COUNT(*) FROM collection_boundaries WHERE enabled=0 AND ts>=? AND ts<?", new String[]{Long.toString(from), Long.toString(until)});
        // A disable before the window can also cover its beginning.
        long disabledAtStart = scalar(db, "SELECT CASE WHEN enabled=0 THEN 1 ELSE 0 END FROM collection_boundaries WHERE ts<? ORDER BY ts DESC,id DESC LIMIT 1", new String[]{Long.toString(from)});
        boolean previousComplete = dateComplete && stopped == 0 && disabledAtStart == 0;
        String limitation = "按本地自然日比较等长窗口，本期含未结束的今天。"
                + (previousComplete ? "前期日期在保留范围内，但未覆盖页面、宿主退出与采集缺口仍可能漏记。" : "前期早于记录/保留起点，或含已知关闭采集时间；不计算增长率。")
                + "次数变化不能直接归因为兴趣变化。";
        List<AnalyticsSnapshot.Rank> currentAuthors = allRanks(db, current, "author"), previousAuthors = allRanks(db, previous, "author");
        currentAuthors.removeIf(rank -> "未识别作者".equals(rank.label));
        previousAuthors.removeIf(rank -> "未识别作者".equals(rank.label));
        return new PortraitSnapshot.Comparison(start.toString(), end.toString(), previousStart.toString(), previousEnd.toString(),
                scalar(db, "SELECT COALESCE(SUM(views),0) FROM daily WHERE day BETWEEN ? AND ?", current),
                scalar(db, "SELECT COALESCE(SUM(views),0) FROM daily WHERE day BETWEEN ? AND ?", previous),
                scalar(db, "SELECT COALESCE(SUM(features),0) FROM daily WHERE day BETWEEN ? AND ?", current),
                scalar(db, "SELECT COALESCE(SUM(features),0) FROM daily WHERE day BETWEEN ? AND ?", previous),
                allRanks(db, current, "type"), allRanks(db, previous, "type"), false, previousComplete, limitation,
                PortraitMetrics.headAndTail(allRanks(db, current, "topic"), 8),
                PortraitMetrics.headAndTail(allRanks(db, previous, "topic"), 8),
                PortraitMetrics.headAndTail(currentAuthors, 8), PortraitMetrics.headAndTail(previousAuthors, 8));
    }

    private static List<AnalyticsSnapshot.Rank> allRanks(SQLiteDatabase db, String[] range, String kind) {
        List<AnalyticsSnapshot.Rank> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT MAX(label),SUM(count) AS total FROM ranks WHERE day BETWEEN ? AND ? AND kind=? GROUP BY item_key ORDER BY total DESC,MAX(label) ASC", new String[]{range[0], range[1], kind})) {
            while (cursor.moveToNext()) result.add(new AnalyticsSnapshot.Rank(cursor.getString(0), cursor.getLong(1)));
        }
        return result;
    }

    private static List<AnalyticsSnapshot.Bucket> trend(LocalDate start, LocalDate end, Map<LocalDate, Long> values) {
        if (values.isEmpty()) return List.of();
        // At most 90 daily buckets; larger ranges use consecutive seven-day buckets (<=53).
        int step = ChronoUnit.DAYS.between(start, end) + 1 > 90 ? 7 : 1;
        List<AnalyticsSnapshot.Bucket> result = new ArrayList<>();
        for (LocalDate bucket = start; !bucket.isAfter(end); bucket = bucket.plusDays(step)) {
            LocalDate finish = bucket.plusDays(step - 1L);
            if (finish.isAfter(end)) finish = end;
            long count = 0;
            for (LocalDate date = bucket; !date.isAfter(finish); date = date.plusDays(1))
                count += values.getOrDefault(date, 0L);
            String label = SHORT_DATE.format(bucket);
            if (step > 1 && !bucket.equals(finish)) label += "–" + SHORT_DATE.format(finish);
            result.add(new AnalyticsSnapshot.Bucket(label, count));
        }
        return result;
    }

    private static List<AnalyticsSnapshot.Rank> ranks(SQLiteDatabase db, String[] range, String kind) {
        List<AnalyticsSnapshot.Rank> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT MAX(label),SUM(count) AS total FROM ranks WHERE day BETWEEN ? AND ? AND kind=? GROUP BY item_key ORDER BY total DESC,MAX(label) ASC LIMIT 10",
                new String[]{range[0], range[1], kind})) {
            while (cursor.moveToNext()) result.add(new AnalyticsSnapshot.Rank(cursor.getString(0), cursor.getLong(1)));
        }
        return result;
    }

    private static long scalar(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) { return cursor.moveToFirst() ? cursor.getLong(0) : 0; }
    }

    private LocalDate today() { return Instant.ofEpochMilli(clock.millis()).atZone(zone.get()).toLocalDate(); }

    private void persistEnabled(boolean value) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Local storage unavailable");
        File pending = new File(directory, "enabled.pending");
        try {
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                stream.write(value ? '1' : '0');
                stream.getFD().sync();
            }
            File target = new File(directory, "enabled");
            try {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // Explicit replacement works on Android and on Windows validation hosts. Keep
            // AtomicFile reads for compatibility with the original flag and legacy backups.
            String persisted = new String(enabledFile.readFully(), StandardCharsets.UTF_8);
            if (!(value ? "1" : "0").equals(persisted))
                throw new IllegalStateException("Local analytics preference was not saved");
        } finally { Files.deleteIfExists(pending.toPath()); }
    }

    private void failClosed() {
        // No caller holds stateLock while acquiring databaseLock; serialize flag/epoch here.
        synchronized (stateLock) {
            enabled = false;
            generation++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            cancelAutomaticAdvance();
            try { persistEnabled(false); }
            catch (Exception ignored) { try { enabledFile.delete(); } catch (Exception ignoredDelete) { } }
        }
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim().replace('\n', ' ').replace('\r', ' ');
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String normalizeSource(String source) {
        if ("manual".equals(source) || "automatic".equals(source) || "system".equals(source)) return source;
        return "unknown";
    }

    private static String normalizeStage(String stage) {
        if ("entry".equals(stage) || "request".equals(stage) || "accepted".equals(stage)
                || "success".equals(stage) || "failure".equals(stage) || "confirmed".equals(stage)) return stage;
        return "unknown";
    }

    private static String sourceLabel(String source) {
        switch (normalizeSource(source)) {
            case "manual": return "主动操作";
            case "automatic": return "自动触发";
            case "system": return "系统处理";
            default: return "来源未知";
        }
    }

    private static String stageLabel(String stage) {
        switch (normalizeStage(stage)) {
            case "entry": return "入口";
            case "request": return "请求";
            case "accepted": return "已受理";
            case "success": return "成功";
            case "failure": return "失败";
            case "confirmed": return "已确认";
            default: return "阶段未知";
        }
    }

    private static String featureSource(String key) {
        switch (key) {
            case "auto_follow": case "auto_like": case "auto_comment_sent": case "auto_reply_sent": case "auto_slide":
                return "automatic";
            case "gesture_menu": case "view_finder": case "copy_text": case "save_media": case "open_comments":
            case "gesture_speed": case "comment_tts": case "third_party_download": case "analytics_share":
                return "manual";
            case "feed_filter": case "analytics_ai_analysis": return "system";
            default: return "unknown";
        }
    }

    private static String featureLabel(String key) {
        switch (key) {
            case "auto_follow": return "自动关注";
            case "auto_like": return "自动点赞";
            case "auto_comment_sent": return "自动评论";
            case "auto_reply_sent": return "自动回复";
            case "auto_slide": return "自动翻页";
            case "gesture_menu": return "快捷菜单";
            case "view_finder": return "视图查找器";
            case "copy_text": return "复制文案";
            case "save_media": return "保存媒体";
            case "open_comments": return "打开评论";
            case "gesture_speed": return "切换倍速";
            case "comment_tts": return "评论语音";
            case "download_queued": return "下载任务";
            case "third_party_download": return "第三方下载";
            case "feed_filter": return "内容过滤";
            default: return clean(key, 60);
        }
    }

    private static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte element : bytes) result.append(Character.forDigit((element >>> 4) & 15, 16)).append(Character.forDigit(element & 15, 16));
        return result.toString();
    }

    private static String normalizeType(String type) {
        if (type == null) return "unknown";
        switch (type) {
            case "short_video": case "long_video": case "live_photo": case "image": case "live": return type;
            default: return "unknown";
        }
    }

    public static String typeLabel(String type) {
        switch (normalizeType(type)) {
            case "short_video": return "短视频";
            case "long_video": return "长视频";
            case "live_photo": return "实况图集";
            case "image": return "图集";
            case "live": return "直播卡片";
            default: return "其他作品";
        }
    }
}
