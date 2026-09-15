package com.chessvault.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public class VaultBridge {
    private final Context ctx;
    private final DatabaseHelper db;
    private final MainActivity activity;

    public VaultBridge(MainActivity activity, DatabaseHelper db) {
        this.activity = activity;
        this.ctx = activity;
        this.db = db;
    }

    @JavascriptInterface
    public void showToast(String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    @JavascriptInterface
    public void saveConfig(String key, String value) {
        db.putConfig(key, value);
    }

    @JavascriptInterface
    public String getConfig(String key) {
        String v = db.getConfig(key);
        return v != null ? v : "";
    }

    @JavascriptInterface
    public String getConfigWithDefault(String key, String def) {
        String v = db.getConfig(key, def);
        return v != null ? v : def;
    }

    @JavascriptInterface
    public String getAllConfig() {
        try {
            JSONObject o = new JSONObject();
            String[] keys = new String[]{"username", "auto_sync_enabled", "last_sync_human", "next_alarm_human"};
            for (String k : keys) {
                String v = db.getConfig(k);
                if (v != null) o.put(k, v);
            }
            o.put("total_games", db.countGames());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private String statsCache = null;
    private long statsAt = 0;

    private synchronized void invalidateStats() { statsCache = null; statsAt = 0; }

    @JavascriptInterface
    public String getStats() {
        try {
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (statsCache != null && now - statsAt < 10000) return statsCache;
            }
            String s = db.getStats().toString();
            synchronized (this) { statsCache = s; statsAt = now; }
            return s;
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public String getRecentGames(int limit, int offset) {
        try {
            return db.getRecentGames(limit, offset).toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String getGame(String uuid) {
        try {
            JSONObject g = db.getGame(uuid);
            return g != null ? g.toString() : "{}";
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public int saveGames(String jsonArrayStr) {
        try {
            JSONArray arr = new JSONArray(jsonArrayStr);
            int n = db.insertGames(arr);
            if (n > 0) {
                invalidateStats();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    @JavascriptInterface
    public long getMaxEndTime() {
        return db.getMaxEndTime();
    }

    @JavascriptInterface
    public void triggerSync() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.startSyncService(); }
        });
    }

    @JavascriptInterface
    public void scheduleSync() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.scheduleAlarm(); }
        });
    }

    @JavascriptInterface
    public void cancelSync() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.cancelAlarm(); }
        });
    }

    @JavascriptInterface
    public String getLastSyncHuman() {
        String v = db.getConfig("last_sync_human");
        return v != null ? v : "";
    }

    @JavascriptInterface
    public void clearAll() {
        db.clearAll();
        invalidateStats();
    }

    @JavascriptInterface
    public String exportJson() {
        try {
            JSONObject exp = new JSONObject();
            exp.put("stats", db.getStats());
            exp.put("games", db.getRecentGames(10000, 0));
            exp.put("exported_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date()));
            return exp.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Não foi possível abrir: " + url, Toast.LENGTH_SHORT).show();
        }
    }

    @JavascriptInterface
    public void openAppInfo() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) { Toast.makeText(ctx, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }
}
