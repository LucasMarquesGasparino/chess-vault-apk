package com.chessvault.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class SyncService extends Service {
    private static final String TAG = "ChessVaultSync";
    private static final String CHANNEL_ID = "chess_sync";
    private static final int NOTIF_ID_ONGOING = 1001;
    private static final int NOTIF_ID_SUMMARY = 1002;
    private static final String UA = "chess-vault/1.0 (contact: chess-vault-app)";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm0 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm0 != null) nm0.notify(NOTIF_ID_ONGOING, buildOngoingNotification("Sincronizando Chess Vault..."));
        } catch (Exception ignored) {}
        new Thread(new Runnable() {
            @Override public void run() {
                int inserted = 0;
                try {
                    inserted = doSync();
                } catch (Exception e) {
                    Log.e(TAG, "sync error", e);
                    showErrorNotification(e.getMessage());
                } finally {
                    try { showSummaryNotification(inserted); } catch (Exception e) { Log.e(TAG, "summary error", e); }
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.cancel(NOTIF_ID_ONGOING);
                    } catch (Exception ignored) {}
                    AlarmReceiver.scheduleExactAlarm(SyncService.this);
                    stopSelf();
                }
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Chess Vault Sync", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Sincronização diária das partidas do chess.com");
                nm.createNotificationChannel(ch);
            }
            String summaryId = CHANNEL_ID + "_summary";
            if (nm != null && nm.getNotificationChannel(summaryId) == null) {
                NotificationChannel ch2 = new NotificationChannel(summaryId, "Chess Vault Resumo", NotificationManager.IMPORTANCE_HIGH);
                ch2.setDescription("Resumo diário com partidas e resultado");
                nm.createNotificationChannel(ch2);
            }
        }
    }

    private Notification buildOngoingNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, CHANNEL_ID);
        else { b = new Notification.Builder(this); b.setPriority(Notification.PRIORITY_LOW); }
        b.setContentTitle("Chess Vault")
         .setContentText(text)
         .setSmallIcon(R.drawable.ic_vault)
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setContentIntent(pi);
        return b.build();
    }

    private void showSummaryNotification(int inserted) {
        DatabaseHelper db = new DatabaseHelper(this);
        int total = db.countGames();
        db.close();
        String title = "Chess Vault • +" + inserted + " partidas";
        String content = inserted == 0
            ? "Nenhuma partida nova. Total no cofre: " + total + "."
            : inserted + " partidas novas importadas. Total no cofre: " + total + ".";
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String ch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? CHANNEL_ID + "_summary" : CHANNEL_ID;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, ch);
        else { b = new Notification.Builder(this); b.setPriority(Notification.PRIORITY_HIGH); }
        b.setContentTitle(title)
         .setContentText(content)
         .setStyle(new Notification.BigTextStyle().bigText(content))
         .setSmallIcon(R.drawable.ic_vault)
         .setAutoCancel(true)
         .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(NOTIF_ID_SUMMARY, b.build()); } catch (SecurityException se) { Log.w(TAG, "notif perm missing", se); }
        }
    }

    private void showErrorNotification(String err) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String ch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? CHANNEL_ID + "_summary" : CHANNEL_ID;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, ch);
        else { b = new Notification.Builder(this); b.setPriority(Notification.PRIORITY_DEFAULT); }
        b.setContentTitle("Chess Vault • Erro no sync")
         .setContentText(err != null ? err.substring(0, Math.min(100, err.length())) : "Falha")
         .setSmallIcon(R.drawable.ic_vault)
         .setAutoCancel(true)
         .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(NOTIF_ID_SUMMARY + 1, b.build()); } catch (Exception ignored) {}
        }
    }

    private int doSync() throws Exception {
        DatabaseHelper db = new DatabaseHelper(this);
        String username = db.getConfig("username", "").trim().toLowerCase();
        if (username.isEmpty()) {
            Log.w(TAG, "no username configured");
            db.close();
            return 0;
        }
        long maxEnd = db.getMaxEndTime();
        List<String> archives = fetchArchives(username);
        int total = 0;
        for (String archiveUrl : archives) {
            int inserted = fetchAndStoreMonth(db, username, archiveUrl, maxEnd);
            total += inserted;
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
        db.putConfig("last_alarm_run", String.valueOf(System.currentTimeMillis()));
        db.close();
        Log.i(TAG, "sync done total=" + total);
        return total;
    }

    private List<String> fetchArchives(String username) throws Exception {
        String body = httpGet("https://api.chess.com/pub/player/" + username + "/games/archives");
        JSONObject o = new JSONObject(body);
        JSONArray arr = o.optJSONArray("archives");
        List<String> out = new ArrayList<String>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        return out;
    }

    private int fetchAndStoreMonth(DatabaseHelper db, String username, String archiveUrl, long maxEnd) {
        try {
            String body = httpGet(archiveUrl);
            JSONObject o = new JSONObject(body);
            JSONArray games = o.optJSONArray("games");
            if (games == null || games.length() == 0) return 0;
            JSONArray toInsert = new JSONArray();
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                long endTime = g.optLong("end_time", 0);
                if (endTime <= 0) continue;
                if (endTime <= maxEnd) continue;
                JSONObject flat = GameParser.flattenGame(username, g);
                if (flat != null) toInsert.put(flat);
            }
            if (toInsert.length() == 0) return 0;
            return db.insertGames(toInsert);
        } catch (Exception e) {
            Log.w(TAG, "month failed " + archiveUrl + ": " + e.getMessage());
            return 0;
        }
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code == 404) throw new Exception("HTTP 404 (usuário inexistente?)");
        if (code == 429) throw new Exception("HTTP 429 rate limit — tente de novo em instantes");
        if (code != 200) throw new Exception("HTTP " + code);
        InputStream is = conn.getInputStream();
        String enc = conn.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) {
            is = new java.util.zip.GZIPInputStream(is);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
