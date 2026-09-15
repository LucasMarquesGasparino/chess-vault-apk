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
    private static final int DB_VERSION = 1;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
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
        return v != null ? v : def;
    }

    public synchronized int insertGames(JSONArray arr) {
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
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM games", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized long getMaxEndTime() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT MAX(end_time) FROM games", null);
        try {
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized JSONObject getStats() {
        JSONObject o = new JSONObject();
        SQLiteDatabase db = getReadableDatabase();
        try {
            o.put("total", countGames());
            Cursor c = db.rawQuery(
                "SELECT time_class, COUNT(*), SUM(is_win), SUM(is_loss), SUM(is_draw), " +
                "AVG(my_rating), AVG(accuracy) FROM games GROUP BY time_class", null);
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
                "SELECT my_color, COUNT(*), SUM(is_win), SUM(is_loss), SUM(is_draw) FROM games GROUP BY my_color", null);
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
                "WHERE eco_name<>'' GROUP BY eco_name ORDER BY COUNT(*) DESC LIMIT 15", null);
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
                "SELECT err_phase, COUNT(*) FROM games WHERE err_phase<>'' AND err_phase IS NOT NULL GROUP BY err_phase", null);
            JSONObject errPhase = new JSONObject();
            try {
                while (c4.moveToNext()) errPhase.put(c4.getString(0), c4.getInt(1));
            } finally { c4.close(); }
            o.put("err_by_phase", errPhase);

            Cursor c5 = db.rawQuery(
                "SELECT err_move_num, COUNT(*) FROM games WHERE err_move_num IS NOT NULL " +
                "GROUP BY err_move_num ORDER BY err_move_num", null);
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
                "AVG(accuracy) FROM games", null);
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
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        int lim = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int off = Math.max(0, offset);
        Cursor c = db.rawQuery(
            "SELECT uuid, url, end_time, end_date, time_class, time_control, my_color, " +
            "my_rating, opp_username, opp_rating, my_result, is_win, is_loss, is_draw, " +
            "eco_name, accuracy, move_count FROM games ORDER BY end_time DESC LIMIT ? OFFSET ?",
            new String[]{String.valueOf(lim), String.valueOf(off)});
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

    public synchronized JSONObject getGame(String uuid) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM games WHERE uuid=? LIMIT 1", new String[]{uuid});
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

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        try { db.execSQL("DELETE FROM games"); } catch (Exception ignored) {}
    }
}
