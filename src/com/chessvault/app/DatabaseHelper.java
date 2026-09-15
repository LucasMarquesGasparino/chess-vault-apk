package com.chessvault.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "chess_vault.db";
    private static final int DB_VERSION = 4;

    public DatabaseHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS games (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uuid TEXT UNIQUE," +
                "url TEXT," +
                "end_time INTEGER," +
                "end_date TEXT," +
                "owner TEXT DEFAULT ''," +
                "time_class TEXT," +
                "time_control TEXT," +
                "rated INTEGER," +
                "rules TEXT," +
                "my_color TEXT," +
                "my_rating INTEGER," +
                "opp_username TEXT," +
                "opp_rating INTEGER," +
                "my_result TEXT," +
                "opp_result TEXT," +
                "is_win INTEGER," +
                "is_loss INTEGER," +
                "is_draw INTEGER," +
                "is_timeout_loss INTEGER," +
                "is_mate_loss INTEGER," +
                "eco TEXT," +
                "eco_name TEXT," +
                "accuracy REAL," +
                "opp_accuracy REAL," +
                "move_count INTEGER," +
                "pgn TEXT," +
                "clocks_json TEXT," +
                "moves_san TEXT," +
                "err_move_num INTEGER," +
                "err_phase TEXT," +
                "inserted_at INTEGER" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_end ON games(end_time DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_tc ON games(time_class)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_eco ON games(eco_name)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_result ON games(my_result)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_errphase ON games(err_phase)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_owner ON games(owner)");

        db.execSQL("CREATE TABLE IF NOT EXISTS synced_archives (" +
                "url TEXT PRIMARY KEY," +
                "owner TEXT DEFAULT ''," +
                "year_month TEXT," +
                "games_count INTEGER," +
                "is_complete INTEGER," +
                "synced_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_synced_ym ON synced_archives(year_month)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_synced_owner ON synced_archives(owner)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS synced_archives (" +
                    "url TEXT PRIMARY KEY," +
                    "owner TEXT DEFAULT ''," +
                    "year_month TEXT," +
                    "games_count INTEGER," +
                    "is_complete INTEGER," +
                    "synced_at INTEGER)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_synced_ym ON synced_archives(year_month)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_synced_owner ON synced_archives(owner)");
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE games ADD COLUMN owner TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_owner ON games(owner)"); } catch (Exception ignored) {}
        }
        if (oldVersion < 4) {
            try { db.execSQL("ALTER TABLE synced_archives ADD COLUMN owner TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_synced_owner ON synced_archives(owner)"); } catch (Exception ignored) {}
            migrateLegacyGlobalFlags(db);
        }
    }

    public static String normOwner(String owner) {
        return owner != null ? owner.trim().toLowerCase(java.util.Locale.US) : "";
    }

    public static String ownerKey(String owner, String base) {
        String ow = normOwner(owner);
        return ow.isEmpty() ? base : ("owner:" + ow + ":" + base);
    }

    private void migrateLegacyGlobalFlags(SQLiteDatabase db) {
        try {
            String u1 = null, u2 = null, active = null;
            Cursor c = db.rawQuery("SELECT key, value FROM config WHERE key IN ('username','secondary_username','active_owner')", null);
            try {
                while (c.moveToNext()) {
                    String k = c.getString(0);
                    String v = c.getString(1);
                    if ("username".equals(k)) u1 = v;
                    else if ("secondary_username".equals(k)) u2 = v;
                    else if ("active_owner".equals(k)) active = v;
                }
            } finally { c.close(); }
            java.util.ArrayList<String> owners = new java.util.ArrayList<String>();
            if (u1 != null && !u1.trim().isEmpty()) owners.add(normOwner(u1));
            if (u2 != null && !u2.trim().isEmpty()) owners.add(normOwner(u2));
            if (active != null && !active.trim().isEmpty()) owners.add(normOwner(active));
            Cursor g = db.rawQuery("SELECT DISTINCT owner FROM games WHERE owner<>'' AND owner IS NOT NULL", null);
            try {
                while (g.moveToNext()) {
                    String o = normOwner(g.getString(0));
                    if (!o.isEmpty() && !owners.contains(o)) owners.add(o);
                }
            } finally { g.close(); }
            String glFull = null, glDate = null, glLast = null;
            Cursor f = db.rawQuery("SELECT key, value FROM config WHERE key IN ('full_sync_completed','full_sync_date','last_sync_human')", null);
            try {
                while (f.moveToNext()) {
                    String k = f.getString(0);
                    String v = f.getString(1);
                    if ("full_sync_completed".equals(k)) glFull = v;
                    else if ("full_sync_date".equals(k)) glDate = v;
                    else if ("last_sync_human".equals(k)) glLast = v;
                }
            } finally { f.close(); }
            boolean hadGlobal = (glFull != null || glDate != null || glLast != null);
            for (String ow : owners) {
                long n = 0;
                Cursor cc = db.rawQuery("SELECT COUNT(*) FROM games WHERE owner=?", new String[]{ow});
                try { if (cc.moveToFirst()) n = cc.getLong(0); } finally { cc.close(); }
                if (n <= 0) continue;
                if (hadGlobal) {
                    if ("true".equals(glFull)) putConfigTx(db, ownerKey(ow, "full_sync_completed"), "true");
                    if (glDate != null) putConfigTx(db, ownerKey(ow, "full_sync_date"), glDate);
                    if (glLast != null) putConfigTx(db, ownerKey(ow, "last_sync_human"), glLast);
                    ContentValues cv = new ContentValues();
                    cv.put("owner", ow);
                    db.update("synced_archives", cv, "owner='' OR owner IS NULL", null);
                } else {
                    putConfigTx(db, ownerKey(ow, "full_sync_completed"), "true");
                    db.execSQL("UPDATE synced_archives SET owner=? WHERE owner='' OR owner IS NULL", new String[]{ow});
                    Cursor m = db.rawQuery("SELECT MAX(end_time) FROM games WHERE owner=?", new String[]{ow});
                    try {
                        if (m.moveToFirst() && !m.isNull(0)) {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                            putConfigTx(db, ownerKey(ow, "last_sync_human"), sdf.format(new java.util.Date()));
                        }
                    } finally { m.close(); }
                }
                break;
            }
            db.execSQL("DELETE FROM config WHERE key IN ('full_sync_completed','full_sync_date','last_sync_human')");
        } catch (Exception ignored) {}
    }

    private void putConfigTx(SQLiteDatabase db, String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict("config", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void putConfig(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict("config", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String getConfig(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT value FROM config WHERE key=?", new String[]{key});
        try {
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally { c.close(); }
    }

    public synchronized String getConfig(String key, String def) {
        String v = getConfig(key);
        if (v != null && !v.trim().isEmpty()) return v;
        if ("username".equals(key) && (def == null || def.isEmpty())) return "LuckGaspar";
        return def;
    }

    public synchronized int insertGames(JSONArray arr) {
        return insertGames(arr, null);
    }

    public synchronized int insertGames(JSONArray arr, String owner) {
        if (arr == null || arr.length() == 0) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                ContentValues cv = new ContentValues();
                cv.put("uuid", g.optString("uuid", null));
                cv.put("url", g.optString("url", ""));
                cv.put("end_time", g.optLong("end_time", 0));
                cv.put("end_date", g.optString("end_date", ""));
                if (owner != null && !owner.isEmpty()) cv.put("owner", owner);
                else if (g.has("owner") && !g.isNull("owner")) cv.put("owner", g.optString("owner", ""));
                cv.put("time_class", g.optString("time_class", ""));
                cv.put("time_control", g.optString("time_control", ""));
                cv.put("rated", g.optInt("rated", 1));
                cv.put("rules", g.optString("rules", ""));
                cv.put("my_color", g.optString("my_color", ""));
                cv.put("my_rating", g.optInt("my_rating", 0));
                cv.put("opp_username", g.optString("opp_username", ""));
                cv.put("opp_rating", g.optInt("opp_rating", 0));
                cv.put("my_result", g.optString("my_result", ""));
                cv.put("opp_result", g.optString("opp_result", ""));
                cv.put("is_win", g.optInt("is_win", 0));
                cv.put("is_loss", g.optInt("is_loss", 0));
                cv.put("is_draw", g.optInt("is_draw", 0));
                cv.put("is_timeout_loss", g.optInt("is_timeout_loss", 0));
                cv.put("is_mate_loss", g.optInt("is_mate_loss", 0));
                cv.put("eco", g.optString("eco", ""));
                cv.put("eco_name", g.optString("eco_name", ""));
                if (g.has("accuracy") && !g.isNull("accuracy")) cv.put("accuracy", g.optDouble("accuracy"));
                if (g.has("opp_accuracy") && !g.isNull("opp_accuracy")) cv.put("opp_accuracy", g.optDouble("opp_accuracy"));
                cv.put("move_count", g.optInt("move_count", 0));
                cv.put("pgn", g.optString("pgn", ""));
                cv.put("clocks_json", g.optString("clocks_json", "[]"));
                cv.put("moves_san", g.optString("moves_san", "[]"));
                if (g.has("err_move_num") && !g.isNull("err_move_num")) cv.put("err_move_num", g.optInt("err_move_num"));
                if (g.has("err_phase") && !g.isNull("err_phase")) cv.put("err_phase", g.optString("err_phase"));
                cv.put("inserted_at", System.currentTimeMillis());
                long r = db.insertWithOnConflict("games", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                if (r > 0) inserted++;
            }
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally { db.endTransaction(); }
        return inserted;
    }

    public synchronized int countGames() {
        return countGamesForOwner(activeOwner());
    }

    public synchronized int countAllGames() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM games", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized int countGamesForOwner(String owner) {
        SQLiteDatabase db = getReadableDatabase();
        String ow = normOwner(owner);
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM games WHERE owner=?", new String[]{ow});
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized long getMaxEndTime() {
        return getMaxEndTimeForOwner(activeOwner());
    }

    public synchronized long getMaxEndTimeForOwner(String owner) {
        String ow = normOwner(owner);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (!ow.isEmpty()) {
            c = db.rawQuery("SELECT MAX(end_time) FROM games WHERE owner=?", new String[]{ow});
        } else {
            c = db.rawQuery("SELECT MAX(end_time) FROM games", null);
        }
        try {
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized JSONObject getStats() {
        return getStatsForOwner(activeOwner());
    }

    public synchronized String activeOwner() {
        String o = getConfig("active_owner", "");
        if (o == null || o.trim().isEmpty()) {
            o = getConfig("username", "LuckGaspar");
        }
        return normOwner(o);
    }

    public synchronized JSONObject getStatsForOwner(String owner) {
        JSONObject o = new JSONObject();
        SQLiteDatabase db = getReadableDatabase();
        String ow = normOwner(owner);
        String owWhere = " WHERE owner=? ";
        String[] owArg = new String[]{ow};
        try {
            o.put("total", countGamesForOwner(ow));
            Cursor c = db.rawQuery(
                "SELECT time_class, COUNT(*), SUM(is_win), SUM(is_loss), SUM(is_draw), " +
                "AVG(my_rating), AVG(accuracy) FROM games" + owWhere + "GROUP BY time_class", owArg);
            JSONArray byTc = new JSONArray();
            try {
                while (c.moveToNext()) {
                    JSONObject t = new JSONObject();
                    t.put("time_class", c.getString(0));
                    t.put("games", c.getInt(1));
                    t.put("wins", c.isNull(2) ? 0 : c.getInt(2));
                    t.put("losses", c.isNull(3) ? 0 : c.getInt(3));
                    t.put("draws", c.isNull(4) ? 0 : c.getInt(4));
                    t.put("avg_rating", c.isNull(5) ? 0 : Math.round(c.getDouble(5)));
                    t.put("avg_accuracy", c.isNull(6) ? JSONObject.NULL : Math.round(c.getDouble(6) * 10.0) / 10.0);
                    byTc.put(t);
                }
            } finally { c.close(); }
            o.put("by_time_class", byTc);

            Cursor c2 = db.rawQuery(
                "SELECT my_color, COUNT(*), SUM(is_win), SUM(is_loss), SUM(is_draw) FROM games" + owWhere + "GROUP BY my_color", owArg);
            JSONArray byColor = new JSONArray();
            try {
                while (c2.moveToNext()) {
                    JSONObject t = new JSONObject();
                    t.put("color", c2.getString(0));
                    t.put("games", c2.getInt(1));
                    t.put("wins", c2.isNull(2) ? 0 : c2.getInt(2));
                    t.put("losses", c2.isNull(3) ? 0 : c2.getInt(3));
                    t.put("draws", c2.isNull(4) ? 0 : c2.getInt(4));
                    byColor.put(t);
                }
            } finally { c2.close(); }
            o.put("by_color", byColor);

            Cursor c3 = db.rawQuery(
                "SELECT eco_name, COUNT(*), SUM(is_win), SUM(is_loss), SUM(is_draw) FROM games " +
                owWhere + "AND eco_name<>'' GROUP BY eco_name ORDER BY COUNT(*) DESC LIMIT 15", owArg);
            JSONArray openings = new JSONArray();
            try {
                while (c3.moveToNext()) {
                    JSONObject t = new JSONObject();
                    t.put("eco", c3.getString(0));
                    t.put("games", c3.getInt(1));
                    t.put("wins", c3.isNull(2) ? 0 : c3.getInt(2));
                    t.put("losses", c3.isNull(3) ? 0 : c3.getInt(3));
                    t.put("draws", c3.isNull(4) ? 0 : c3.getInt(4));
                    openings.put(t);
                }
            } finally { c3.close(); }
            o.put("openings", openings);

            Cursor c4 = db.rawQuery(
                "SELECT err_phase, COUNT(*) FROM games" + owWhere + "AND err_phase<>'' AND err_phase IS NOT NULL GROUP BY err_phase", owArg);
            JSONObject errPhase = new JSONObject();
            try {
                while (c4.moveToNext()) errPhase.put(c4.getString(0), c4.getInt(1));
            } finally { c4.close(); }
            o.put("err_by_phase", errPhase);

            Cursor c5 = db.rawQuery(
                "SELECT err_move_num, COUNT(*) FROM games" + owWhere + "AND err_move_num IS NOT NULL " +
                "GROUP BY err_move_num ORDER BY err_move_num", owArg);
            JSONArray errHist = new JSONArray();
            try {
                while (c5.moveToNext()) {
                    JSONObject t = new JSONObject();
                    t.put("move", c5.getInt(0));
                    t.put("count", c5.getInt(1));
                    errHist.put(t);
                }
            } finally { c5.close(); }
            o.put("err_hist", errHist);

            Cursor c6 = db.rawQuery(
                "SELECT SUM(is_timeout_loss), SUM(is_mate_loss), " +
                "SUM(CASE WHEN my_result='resigned' THEN 1 ELSE 0 END), " +
                "AVG(accuracy) FROM games" + owWhere.substring(0, owWhere.length() - 1), owArg);
            try {
                if (c6.moveToFirst()) {
                    o.put("timeout_losses", c6.isNull(0) ? 0 : c6.getInt(0));
                    o.put("mate_losses", c6.isNull(1) ? 0 : c6.getInt(1));
                    o.put("resigns", c6.isNull(2) ? 0 : c6.getInt(2));
                    o.put("avg_accuracy", c6.isNull(3) ? JSONObject.NULL : Math.round(c6.getDouble(3) * 10.0) / 10.0);
                }
            } finally { c6.close(); }
        } catch (Exception ignored) {
        }
        return o;
    }

    public synchronized JSONArray getRecentGames(int limit, int offset) {
        return queryGames(null, limit, offset);
    }

    public synchronized int countFilteredGames(String filterJson) {
        Filter f = Filter.parse(filterJson, activeOwner());
        SQLiteDatabase db = getReadableDatabase();
        String[] a = f.args.toArray(new String[f.args.size()]);
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM games" + f.where, a);
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized JSONArray queryGames(String filterJson, int limit, int offset) {
        Filter f = Filter.parse(filterJson, activeOwner());
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        int lim = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int off = Math.max(0, offset);
        String[] args = new String[f.args.size() + 2];
        for (int i = 0; i < f.args.size(); i++) args[i] = f.args.get(i);
        args[f.args.size()] = String.valueOf(lim);
        args[f.args.size() + 1] = String.valueOf(off);
        Cursor c = db.rawQuery(
            "SELECT uuid, url, end_time, end_date, time_class, time_control, my_color, " +
            "my_rating, opp_username, opp_rating, my_result, is_win, is_loss, is_draw, " +
            "eco_name, accuracy, move_count FROM games" + f.where +
            " ORDER BY end_time DESC LIMIT ? OFFSET ?",
            args);
        try {
            while (c.moveToNext()) {
                JSONObject g = new JSONObject();
                try {
                    g.put("uuid", c.getString(0));
                    g.put("url", c.getString(1));
                    g.put("end_time", c.getLong(2));
                    g.put("end_date", c.getString(3));
                    g.put("time_class", c.getString(4));
                    g.put("time_control", c.getString(5));
                    g.put("my_color", c.getString(6));
                    g.put("my_rating", c.getInt(7));
                    g.put("opp_username", c.getString(8));
                    g.put("opp_rating", c.getInt(9));
                    g.put("my_result", c.getString(10));
                    g.put("is_win", c.getInt(11));
                    g.put("is_loss", c.getInt(12));
                    g.put("is_draw", c.getInt(13));
                    g.put("eco_name", c.getString(14));
                    g.put("accuracy", c.isNull(15) ? JSONObject.NULL : c.getDouble(15));
                    g.put("move_count", c.getInt(16));
                } catch (Exception ignored) {}
                arr.put(g);
            }
        } finally { c.close(); }
        return arr;
    }

    private static final class Filter {
        String where = "";
        java.util.ArrayList<String> args = new java.util.ArrayList<String>();

        static Filter parse(String filterJson, String owner) {
            Filter f = new Filter();
            java.util.ArrayList<String> conds = new java.util.ArrayList<String>();
            String ow = normOwner(owner);
            if (!ow.isEmpty()) {
                conds.add("owner=?");
                f.args.add(ow);
            }
            if (filterJson == null || filterJson.trim().isEmpty() || "{}".equals(filterJson.trim())) {
                if (!conds.isEmpty()) f.where = " WHERE owner=? ";
                return f;
            }
            try {
                JSONObject o = new JSONObject(filterJson);
                addEq(o, conds, f.args, "time_class", "time_class");
                addEq(o, conds, f.args, "color", "my_color");
                addEq(o, conds, f.args, "eco", "eco_name");
                addEq(o, conds, f.args, "err_phase", "err_phase");
                if (o.has("result") && !o.isNull("result")) {
                    String r = o.optString("result", "");
                    if ("win".equals(r)) conds.add("is_win=1");
                    else if ("loss".equals(r)) conds.add("is_loss=1");
                    else if ("draw".equals(r)) conds.add("is_draw=1");
                }
                if (o.has("loss_kind") && !o.isNull("loss_kind")) {
                    String k = o.optString("loss_kind", "");
                    if ("mate".equals(k)) conds.add("is_mate_loss=1");
                    else if ("timeout".equals(k)) conds.add("is_timeout_loss=1");
                    else if ("resign".equals(k)) conds.add("my_result='resigned'");
                }
                if (o.has("err_move") && !o.isNull("err_move")) {
                    try {
                        conds.add("err_move_num=" + o.getInt("err_move"));
                    } catch (Exception ignored) {}
                }
                if (!conds.isEmpty()) {
                    StringBuilder sb = new StringBuilder(" WHERE ");
                    for (int i = 0; i < conds.size(); i++) {
                        if (i > 0) sb.append(" AND ");
                        sb.append(conds.get(i));
                    }
                    f.where = sb.toString();
                }
            } catch (Exception ignored) {}
            return f;
        }

        private static void addEq(JSONObject o, java.util.ArrayList<String> conds,
                                  java.util.ArrayList<String> args, String jsonKey, String col) {
            if (o.has(jsonKey) && !o.isNull(jsonKey)) {
                String v = o.optString(jsonKey, "");
                if (v != null && !v.isEmpty()) {
                    conds.add(col + "=?");
                    args.add(v);
                }
            }
        }
    }

    public synchronized JSONObject getGame(String uuid) {
        return getGameForOwner(uuid, activeOwner());
    }

    public synchronized JSONObject getGameForOwner(String uuid, String owner) {
        String ow = normOwner(owner);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (!ow.isEmpty()) {
            c = db.rawQuery("SELECT * FROM games WHERE uuid=? AND owner=? LIMIT 1", new String[]{uuid, ow});
        } else {
            c = db.rawQuery("SELECT * FROM games WHERE uuid=? LIMIT 1", new String[]{uuid});
        }
        try {
            if (c.moveToFirst()) {
                JSONObject g = new JSONObject();
                String[] cols = c.getColumnNames();
                for (int i = 0; i < cols.length; i++) {
                    try {
                        if (c.isNull(i)) g.put(cols[i], JSONObject.NULL);
                        else g.put(cols[i], c.getString(i));
                    } catch (Exception ignored) {}
                }
                return g;
            }
            return null;
        } finally { c.close(); }
    }

    public synchronized boolean isArchiveSynced(String url) {
        return isArchiveSynced(url, null);
    }

    public synchronized boolean isArchiveSynced(String url, String owner) {
        if (url == null) return false;
        String ow = normOwner(owner);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (!ow.isEmpty()) {
            c = db.rawQuery("SELECT is_complete FROM synced_archives WHERE url=? AND owner=? AND is_complete=1", new String[]{url, ow});
        } else {
            c = db.rawQuery("SELECT is_complete FROM synced_archives WHERE url=? AND is_complete=1", new String[]{url});
        }
        try {
            return c.moveToFirst();
        } finally { c.close(); }
    }

    public synchronized void markArchiveSynced(String url, String yearMonth, int count, boolean isComplete) {
        markArchiveSynced(url, yearMonth, count, isComplete, null);
    }

    public synchronized void markArchiveSynced(String url, String yearMonth, int count, boolean isComplete, String owner) {
        if (url == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("url", url);
        cv.put("owner", normOwner(owner));
        cv.put("year_month", yearMonth);
        cv.put("games_count", count);
        cv.put("is_complete", isComplete ? 1 : 0);
        cv.put("synced_at", System.currentTimeMillis());
        db.insertWithOnConflict("synced_archives", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized int getSyncedArchivesCount() {
        return getSyncedArchivesCountForOwner(null);
    }

    public synchronized int getSyncedArchivesCountForOwner(String owner) {
        String ow = normOwner(owner);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (!ow.isEmpty()) {
            c = db.rawQuery("SELECT COUNT(*) FROM synced_archives WHERE is_complete=1 AND owner=?", new String[]{ow});
        } else {
            c = db.rawQuery("SELECT COUNT(*) FROM synced_archives WHERE is_complete=1", null);
        }
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized void clearAll() {
        clearOwner(activeOwner());
    }

    public synchronized void clearOwner(String owner) {
        String ow = normOwner(owner);
        SQLiteDatabase db = getWritableDatabase();
        try {
            if (!ow.isEmpty()) {
                db.execSQL("DELETE FROM games WHERE owner=?", new String[]{ow});
                db.execSQL("DELETE FROM synced_archives WHERE owner=?", new String[]{ow});
                db.execSQL("DELETE FROM config WHERE key IN (?,?,?)",
                    new String[]{ownerKey(ow, "last_sync_human"), ownerKey(ow, "full_sync_completed"), ownerKey(ow, "full_sync_date")});
            } else {
                db.execSQL("DELETE FROM games");
                db.execSQL("DELETE FROM synced_archives");
                db.execSQL("DELETE FROM config WHERE key IN ('last_sync_human','full_sync_completed','full_sync_date')");
            }
        } catch (Exception ignored) {}
    }

    public synchronized void clearEverything() {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.execSQL("DELETE FROM games");
            db.execSQL("DELETE FROM synced_archives");
            db.execSQL("DELETE FROM config WHERE key LIKE 'owner:%' OR key IN ('last_sync_human','full_sync_completed','full_sync_date','last_alarm_run')");
        } catch (Exception ignored) {}
    }

    public synchronized boolean isFullSyncCompleted(String owner) {
        return "true".equals(getConfig(ownerKey(owner, "full_sync_completed"), "false"));
    }

    public synchronized String ownerLastSync(String owner) {
        String v = getConfig(ownerKey(owner, "last_sync_human"), "");
        return v != null ? v : "";
    }

    public synchronized String ownerFullSyncDate(String owner) {
        String v = getConfig(ownerKey(owner, "full_sync_date"), "");
        return v != null ? v : "";
    }
}
