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
            String[] keys = new String[]{"username", "secondary_username", "active_owner", "auto_sync_enabled", "last_alarm_run", "next_alarm_ms", "next_alarm_human"};
            for (String k : keys) {
                String v = db.getConfig(k);
                if (v != null) o.put(k, v);
            }
            if (!o.has("username") || o.optString("username").trim().isEmpty()) {
                o.put("username", "LuckGaspar");
            }
            String active = db.activeOwner();
            if (active.isEmpty()) active = o.optString("username", "LuckGaspar").trim().toLowerCase(java.util.Locale.US);
            o.put("active_owner", active);
            o.put("full_sync_completed", db.isFullSyncCompleted(active) ? "true" : "false");
            o.put("full_sync_date", db.ownerFullSyncDate(active));
            o.put("last_sync_human", db.ownerLastSync(active));
            o.put("total_games", db.countGames());
            o.put("total_all_games", db.countAllGames());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private String statsCache = null;
    private long statsAt = 0;
    private String statsOwner = null;

    private synchronized void invalidateStats() { statsCache = null; statsAt = 0; statsOwner = null; }

    @JavascriptInterface
    public String getStats() {
        try {
            String owner = db.activeOwner();
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (statsCache != null && now - statsAt < 10000 && owner.equals(statsOwner)) return statsCache;
            }
            String s = db.getStats().toString();
            synchronized (this) { statsCache = s; statsAt = now; statsOwner = owner; }
            return s;
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public String switchOwner(String owner) {
        try {
            String o = owner != null ? owner.trim() : "";
            if (o.isEmpty()) return getAllConfig();
            db.putConfig("active_owner", o);
            String user1 = db.getConfig("username", "LuckGaspar");
            String user2 = db.getConfig("secondary_username", "");
            if (user2 != null && !user2.trim().isEmpty()
                    && !o.equalsIgnoreCase(user1.trim())
                    && !o.equalsIgnoreCase(user2.trim())) {
                db.putConfig("secondary_username", o);
            }
            invalidateStats();
            return getAllConfig();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public String getRecentGames(int limit, int offset) {
        try {
            return db.getRecentGames(limit, offset).toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String queryGames(String filterJson, int limit, int offset) {
        try {
            return db.queryGames(filterJson, limit, offset).toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public int countFilteredGames(String filterJson) {
        try {
            return db.countFilteredGames(filterJson);
        } catch (Exception e) { return 0; }
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
            String owner = db.activeOwner();
            int n = db.insertGames(arr, owner);
            if (n > 0) {
                invalidateStats();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                db.putConfig(DatabaseHelper.ownerKey(owner, "last_sync_human"), sdf.format(new java.util.Date()));
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
    public String getSyncProgress() {
        return SyncService.SyncProgress.toJson().toString();
    }

    @JavascriptInterface
    public void triggerSync() {
        triggerIncrementalSync();
    }

    @JavascriptInterface
    public void triggerFullSync() {
        startSyncThread("full");
    }

    @JavascriptInterface
    public void triggerIncrementalSync() {
        startSyncThread("incremental");
    }

    private void startSyncThread(final String mode) {
        if (SyncService.SyncProgress.isSyncing) {
            showToast("Sync já em andamento...");
            return;
        }
        SyncService.SyncProgress.reset(mode);
        new Thread(new Runnable() {
            @Override public void run() {
                int inserted = 0;
                try {
                    inserted = SyncEngine.runSync(ctx, db, mode, null);
                    SyncService.SyncProgress.finish(inserted);
                } catch (Exception e) {
                    android.util.Log.e("ChessVaultSync", "sync error", e);
                    SyncService.SyncProgress.error(e.getMessage());
                }
            }
        }).start();
    }

    @JavascriptInterface
    public String getCrashLog() {
        try {
            java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), "chess_crash.log");
            if (f == null || !f.exists()) return "(sem crash registrado)";
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            int count = 0;
            String last = "";
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
            int start = Math.max(0, lines.size() - 40);
            for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            String out = sb.toString();
            return out.length() > 4000 ? out.substring(out.length() - 4000) : out;
        } catch (Exception e) {
            return "erro ao ler log: " + e.getMessage();
        }
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
        String v = db.ownerLastSync(db.activeOwner());
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
