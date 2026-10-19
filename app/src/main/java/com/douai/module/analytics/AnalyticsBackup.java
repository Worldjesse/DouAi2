package com.douai.module.analytics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Logical, bounded snapshots of owned tables. Never accepts database files, SQL or paths. */
public final class AnalyticsBackup {
    public static final int MAX_BYTES = 30 * 1024 * 1024;
    public static final int MAX_ROWS = 250_000;
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private AnalyticsBackup() { }

    private static final class Table {
        final String name; final String[] columns; final int maxRows; final int[] key;
        Table(String name, String columns, int maxRows, int... key) {
            this.name = name; this.columns = columns.split(","); this.maxRows = maxRows; this.key = key;
        }
    }
    private static final List<Table> TABLES = List.of(
            new Table("daily", "day,views,features,ads,content_duration", 365, 0),
            new Table("hours", "day,hour,count", 365 * 24, 0, 1),
            new Table("work_days", "day,work_hash", 100_000, 0, 1),
            new Table("ranks", "day,kind,item_key,label,count", 150_000, 0, 1, 2),
            new Table("recent", "id,ts,label,kind", 30, 0),
            new Table("meta", "key,value", 3, 0),
            new Table("visits", "token,work_hash,type,day,hour,source,started,visible_ms,ended,end_reason", 20_000, 0),
            new Table("visit_topics", "token,topic", 200_000, 0, 1),
            new Table("actions", "id,day,token,feature,source,stage,ts", 30_000, 0),
            new Table("collection_boundaries", "id,ts,enabled", 100_000, 0));

    /** Detached validated payload; no mutable JSON from the importing caller is retained. */
    public static final class Snapshot {
        public final int rowCount;
        public final String summary;
        private final Map<String, List<Object[]>> tables;
        private final LocalPortraitContext.State context;
        private final long capturedAt;
        private Snapshot(Map<String, List<Object[]>> tables, LocalPortraitContext.State context,
                         long capturedAt, int rowCount, String summary) {
            this.tables = tables; this.context = context; this.capturedAt = capturedAt;
            this.rowCount = rowCount; this.summary = summary;
        }
    }

    /** Worker only; queued writes drain before a transaction reads all tables. */
    public static JSONObject export(Context context) {
        return LocalPortraitContext.withState(context, state -> {
            JSONObject payload = LocalAnalytics.get(context).exportBackup();
            try {
                payload.put("context", new JSONObject().put("purpose", state.purpose).put("feedback", state.feedback));
                checkBytes(payload);
                inspect(payload);
                return payload;
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw invalid(); }
        });
    }

    /** Pure validation. Does not open this installation's analytics database. */
    public static Snapshot inspect(JSONObject payload) {
        try {
            checkBytes(payload);
            exact(payload, "version", "capturedAt", "tables", "context");
            if (integer(payload.get("version"), 1, 1) != 1) throw invalid();
            long captured = integer(payload.get("capturedAt"), 0, System.currentTimeMillis() + 300_000);
            LocalDate lastDay = Instant.ofEpochMilli(captured).atOffset(ZoneOffset.ofHours(18)).toLocalDate();
            JSONObject tables = payload.getJSONObject("tables");
            if (tables.length() != TABLES.size()) throw invalid();
            JSONObject self = payload.getJSONObject("context"); exact(self, "purpose", "feedback");
            LocalPortraitContext.State state = LocalPortraitContext.backupState(
                    text(self.get("purpose"), 240, true), text(self.get("feedback"), 8, true));
            Map<String, List<Object[]>> result = new LinkedHashMap<>(); int rows = 0;
            Set<String> visits = new HashSet<>();
            long views = 0, features = 0; String first = "", last = "";
            for (Table table : TABLES) {
                JSONArray incoming = tables.getJSONArray(table.name);
                if (incoming.length() > table.maxRows || incoming.length() > MAX_ROWS - rows) throw invalid();
                List<Object[]> values = new ArrayList<>(); Set<List<Object>> keys = new HashSet<>();
                for (int index = 0; index < incoming.length(); index++) {
                    JSONArray row = incoming.getJSONArray(index);
                    if (row.length() != table.columns.length) throw invalid();
                    Object[] cells = new Object[row.length()];
                    for (int column = 0; column < cells.length; column++) {
                        cells[column] = validateCell(table.name, table.columns[column], row.get(column), captured, lastDay);
                    }
                    List<Object> key = new ArrayList<>(); for (int column : table.key) key.add(cells[column]);
                    if (!keys.add(key)) throw invalid();
                    if (table.name.equals("daily")) {
                        if ((Long) cells[3] > (Long) cells[1]) throw invalid();
                        views = Math.addExact(views, (Long) cells[1]); features = Math.addExact(features, (Long) cells[2]);
                        String day = (String) cells[0];
                        if (first.isEmpty() || day.compareTo(first) < 0) first = day;
                        if (last.isEmpty() || day.compareTo(last) > 0) last = day;
                    }
                    if (table.name.equals("visits")) visits.add((String) cells[0]);
                    if (table.name.equals("visit_topics") && !visits.contains(cells[0])) throw invalid();
                    if (table.name.equals("meta")) {
                        if (!List.of("first_seen", "collection_start", "queue_dropped").contains(cells[0])) throw invalid();
                        if (!cells[0].equals("queue_dropped") && (Long) cells[1] > captured) throw invalid();
                        if (cells[0].equals("queue_dropped") && (Long) cells[1] > MAX_COUNT) throw invalid();
                    }
                    values.add(cells);
                }
                result.put(table.name, values); rows += values.size();
            }
            String dates = first.isEmpty() ? "无浏览日期" : first + " — " + last;
            String summary = dates + "；" + views + " 次浏览，" + features + " 次功能使用，"
                    + result.get("visits").size() + " 条可见片段，" + rows + " 条记录"
                    + ((!state.purpose.isEmpty() || !state.feedback.isEmpty()) ? "；含侧写自述/反馈" : "；无侧写自述/反馈");
            return new Snapshot(result, state, captured, rows, summary);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    /**
     * Worker only. SQLite replacement is transactional. Context/settings are compensated on failure;
     * these separate stores are not a single crash-atomic transaction.
     */
    public static void restore(Context context, Snapshot snapshot, Runnable applySettings, Runnable rollbackSettings) {
        if (snapshot == null) throw invalid();
        LocalPortraitContext.withState(context, before -> {
            boolean[] contextSaved = {false}, settingsSaved = {false};
            try {
                LocalAnalytics.get(context).replaceBackup(snapshot, () -> {
                    LocalPortraitContext.replaceFromBackup(context, snapshot.context); contextSaved[0] = true;
                    applySettings.run(); settingsSaved[0] = true;
                });
            } catch (RuntimeException failure) {
                if (settingsSaved[0]) try { rollbackSettings.run(); }
                catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
                if (contextSaved[0]) try { LocalPortraitContext.replaceFromBackup(context, before); }
                catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
                if (failure.getSuppressed().length > 0) throw new IllegalStateException(
                        "恢复未完成，部分配置或自述未能回滚；本地统计事务已保留，请检查存储后重试", failure);
                throw failure;
            }
            return null;
        });
    }

    static JSONObject capture(SQLiteDatabase db, long capturedAt, long dropped) {
        try {
            JSONObject tables = new JSONObject(); int rows = 0; long estimatedBytes = 256;
            for (Table table : TABLES) {
                JSONArray values = new JSONArray();
                if (db != null) try (Cursor cursor = db.query(table.name, table.columns, null, null, null, null,
                        String.join(",", table.columns))) {
                    while (cursor.moveToNext()) {
                        if (++rows > MAX_ROWS || values.length() >= table.maxRows) throw tooLarge();
                        JSONArray row = new JSONArray();
                        for (int column = 0; column < table.columns.length; column++) {
                            Object cell = cursor.isNull(column) ? JSONObject.NULL
                                    : cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(column) : cursor.getString(column);
                            row.put(cell);
                        }
                        estimatedBytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                        if (estimatedBytes > MAX_BYTES) throw tooLarge();
                        values.put(row);
                    }
                }
                if (table.name.equals("meta") && dropped > 0) {
                    long stored = 0; int found = -1;
                    for (int index = 0; index < values.length(); index++) if ("queue_dropped".equals(values.getJSONArray(index).getString(0))) {
                        found = index; stored = values.getJSONArray(index).getLong(1); break;
                    }
                    JSONArray row = new JSONArray().put("queue_dropped").put(Math.addExact(stored, dropped));
                    if (found < 0) {
                        if (++rows > MAX_ROWS || values.length() >= table.maxRows) throw tooLarge();
                        values.put(row);
                    } else values.put(found, row);
                }
                tables.put(table.name, values);
            }
            return new JSONObject().put("version", 1).put("capturedAt", capturedAt).put("tables", tables);
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    static void replaceTables(SQLiteDatabase db, Snapshot snapshot) {
        for (Table table : TABLES) db.delete(table.name, null, null);
        // A logical replacement also replaces owned auto-increment high-water marks.
        db.delete("sqlite_sequence", "name IN (?,?,?)", new String[]{"recent", "actions", "collection_boundaries"});
        for (Table table : TABLES) for (Object[] row : snapshot.tables.get(table.name)) {
            ContentValues values = new ContentValues();
            for (int column = 0; column < row.length; column++) {
                Object value = row[column];
                if (value == null) values.putNull(table.columns[column]);
                else if (value instanceof Long) values.put(table.columns[column], (Long) value);
                else values.put(table.columns[column], (String) value);
            }
            db.insertOrThrow(table.name, null, values);
        }
        // Imported live segments cannot be resumed by this process.
        ContentValues ended = new ContentValues(); ended.put("ended", 1); ended.put("end_reason", "backup_restore");
        db.update("visits", ended, "ended=0", null);
    }

    private static Object validateCell(String table, String column, Object value, long captured, LocalDate lastDay) {
        if (column.equals("day")) {
            String day = text(value, 10, false);
            LocalDate parsed = LocalDate.parse(day);
            if (!parsed.toString().equals(day) || parsed.isBefore(LocalDate.of(1970, 1, 1)) || parsed.isAfter(lastDay)) throw invalid();
            return day;
        }
        if (column.equals("id")) return integer(value, 1, MAX_COUNT);
        if (column.equals("hour")) return integer(value, 0, 23);
        if (column.equals("enabled") || column.equals("ended")) return integer(value, 0, 1);
        if (column.equals("ts") || column.equals("started")) return integer(value, 0, captured);
        if (column.equals("visible_ms")) return integer(value, 0, 30 * 60 * 1000L);
        if (column.equals("value")) return integer(value, 0, Math.max(captured, MAX_COUNT));
        if (List.of("views", "features", "ads", "content_duration", "count").contains(column))
            return integer(value, 0, MAX_COUNT);
        if (column.equals("work_hash")) {
            String hash = text(value, 64, false); if (!hash.matches("[a-f0-9]{64}")) throw invalid(); return hash;
        }
        if (column.equals("token")) {
            if (table.equals("actions") && value == JSONObject.NULL) return null;
            String token = text(value, 36, false);
            if (!token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw invalid(); return token;
        }
        if (column.equals("source")) return enumeration(value, "manual", "automatic", "system", "unknown");
        if (column.equals("stage")) return enumeration(value, "entry", "request", "accepted", "success", "failure", "confirmed", "unknown");
        if (column.equals("type")) return enumeration(value, "short_video", "long_video", "live_photo", "image", "live", "unknown");
        if (column.equals("kind")) return table.equals("recent") ? enumeration(value, "view", "feature")
                : enumeration(value, "type", "author", "topic", "feature", "feature_source");
        if (column.equals("end_reason")) return text(value, 80, true);
        if (column.equals("topic") || column.equals("feature")) return text(value, 100, false);
        if (column.equals("key")) return text(value, 32, false);
        if (column.equals("item_key") || column.equals("label")) return text(value, 160, false);
        throw invalid();
    }
    private static long integer(Object value, long min, long max) {
        if (!(value instanceof Long || value instanceof Integer)) throw invalid();
        long number = ((Number) value).longValue(); if (number < min || number > max) throw invalid(); return number;
    }
    private static String enumeration(Object value, String... allowed) {
        String text = text(value, 32, false); if (!List.of(allowed).contains(text)) throw invalid(); return text;
    }
    private static String text(Object value, int max, boolean empty) {
        if (!(value instanceof String)) throw invalid(); String text = (String) value;
        if (text.length() > max || (!empty && text.isEmpty())
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) throw invalid();
        return text;
    }
    private static void exact(JSONObject object, String... keys) {
        if (object.length() != keys.length) throw invalid();
        for (String key : keys) if (!object.has(key)) throw invalid();
    }
    private static void checkBytes(JSONObject object) {
        if (object == null || object.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
    }
    private static IllegalArgumentException tooLarge() { return new IllegalArgumentException("本地分析数据超过 30 MiB 或记录数量限制，未导出不完整快照"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("本地分析备份格式无效、数据越界或内容不完整"); }
}
