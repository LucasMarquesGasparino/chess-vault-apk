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
    private static final String UA = "ChessVault/1.0 (Android; contact: lucas.gasparino; github.com/LucasMarquesGasparino)";

    public static final String EXTRA_SYNC_MODE = "sync_mode"; // "full" ou "incremental"

    public static class SyncProgress {
        public static volatile boolean isSyncing = false;
        public static volatile String mode = "idle";
        public static volatile int currentMonth = 0;
        public static volatile int totalMonths = 0;
        public static volatile String currentArchive = "";
        public static volatile int gamesInserted = 0;
        public static volatile String statusMessage = "";
        public static volatile String lastError = null;

        public static synchronized JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("is_syncing", isSyncing);
                o.put("mode", mode);
                o.put("current_month", currentMonth);
                o.put("total_months", totalMonths);
                o.put("current_archive", currentArchive);
                o.put("games_inserted", gamesInserted);
                o.put("status_message", statusMessage);
                o.put("last_error", lastError != null ? lastError : JSONObject.NULL);
            } catch (Exception ignored) {}
            return o;
        }

        public static void reset(String newMode) {
            isSyncing = true;
            mode = newMode;
            currentMonth = 0;
            totalMonths = 0;
            currentArchive = "";
            gamesInserted = 0;
            statusMessage = "Iniciando...";
            lastError = null;
        }

        public static void finish(int total) {
            isSyncing = false;
            gamesInserted = total;
            statusMessage = "Concluído (" + total + " novas partidas)";
        }

        public static void error(String err) {
            isSyncing = false;
            lastError = err;
            statusMessage = "Erro: " + err;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String reqMode = intent != null ? intent.getStringExtra(EXTRA_SYNC_MODE) : null;
        Notification notif = buildOngoingNotification("Sincronizando Chess Vault...");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID_ONGOING, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID_ONGOING, notif);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground error", e);
        }

        new Thread(new Runnable() {
            @Override public void run() {
                int inserted = 0;
                String effectiveMode = reqMode;
                DatabaseHelper db = new DatabaseHelper(SyncService.this);
                try {
                    if (effectiveMode == null || effectiveMode.isEmpty()) {
                        String fullDone = db.getConfig("full_sync_completed", "false");
                        effectiveMode = "true".equals(fullDone) ? "incremental" : "full";
                    }
                    SyncProgress.reset(effectiveMode);
                    inserted = doSync(db, effectiveMode);
                    SyncProgress.finish(inserted);
                } catch (Exception e) {
                    Log.e(TAG, "sync error", e);
                    SyncProgress.error(e.getMessage());
                    showErrorNotification(e.getMessage());
                } finally {
                    db.close();
                    try { showSummaryNotification(inserted); } catch (Exception e) { Log.e(TAG, "summary error", e); }
                    try {
                        if (Build.VERSION.SDK_INT >= 24) {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } else {
                            stopForeground(true);
                        }
                    } catch (Exception ignored) {}
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

    private void updateOngoingNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID_ONGOING, buildOngoingNotification(text));
        } catch (Exception ignored) {}
    }

    private int doSync(DatabaseHelper db, String mode) throws Exception {
        String username = db.getConfig("username", "LuckGaspar").trim().toLowerCase();
        if (username.isEmpty()) {
            Log.w(TAG, "no username configured");
            return 0;
        }

        List<String> archives = fetchArchives(username);
        if (archives.isEmpty()) {
            SyncProgress.statusMessage = "Nenhum arquivo encontrado para o usuário";
            return 0;
        }

        SyncProgress.totalMonths = archives.size();
        int totalInserted = 0;
        boolean isFullMode = "full".equalsIgnoreCase(mode);

        java.util.Calendar nowCal = java.util.Calendar.getInstance();
        int curYear = nowCal.get(java.util.Calendar.YEAR);
        int curMonth = nowCal.get(java.util.Calendar.MONTH) + 1;
        String curYm = String.format(java.util.Locale.US, "%04d/%02d", curYear, curMonth);

        if (isFullMode) {
            for (int i = 0; i < archives.size(); i++) {
                String archiveUrl = archives.get(i);
                String ym = extractYearMonth(archiveUrl);
                boolean isCurrentMonth = archiveUrl.endsWith(curYm) || (i == archives.size() - 1);

                SyncProgress.currentMonth = i + 1;
                SyncProgress.currentArchive = ym;
                SyncProgress.statusMessage = "Mês " + (i + 1) + "/" + archives.size() + " (" + ym + ")";

                // Se já foi sincronizado e não é o mês corrente em aberto, pula download
                if (!isCurrentMonth && db.isArchiveSynced(archiveUrl)) {
                    continue;
                }

                updateOngoingNotification("Mês " + (i + 1) + "/" + archives.size() + " (" + totalInserted + " partidas)");

                int inserted = fetchAndStoreMonth(db, username, archiveUrl);
                totalInserted += inserted;
                SyncProgress.gamesInserted = totalInserted;

                if (!isCurrentMonth) {
                    db.markArchiveSynced(archiveUrl, ym, inserted, true);
                }

                try { Thread.sleep(120); } catch (Exception ignored) {}
            }
            db.putConfig("full_sync_completed", "true");
            java.text.SimpleDateFormat sdfFull = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            db.putConfig("full_sync_date", sdfFull.format(new java.util.Date()));
        } else {
            // Modo incremental: processa apenas os últimos 2 arquivos (mês atual e anterior)
            int startIdx = Math.max(0, archives.size() - 2);
            SyncProgress.totalMonths = archives.size() - startIdx;
            int step = 0;
            for (int i = startIdx; i < archives.size(); i++) {
                step++;
                String archiveUrl = archives.get(i);
                String ym = extractYearMonth(archiveUrl);

                SyncProgress.currentMonth = step;
                SyncProgress.currentArchive = ym;
                SyncProgress.statusMessage = "Mês " + ym;

                updateOngoingNotification("Sync recente (" + ym + ")...");
                int inserted = fetchAndStoreMonth(db, username, archiveUrl);
                totalInserted += inserted;
                SyncProgress.gamesInserted = totalInserted;

                try { Thread.sleep(120); } catch (Exception ignored) {}
            }
        }

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
        db.putConfig("last_alarm_run", String.valueOf(System.currentTimeMillis()));

        Log.i(TAG, "sync done mode=" + mode + " total=" + totalInserted);
        return totalInserted;
    }

    private String extractYearMonth(String archiveUrl) {
        try {
            String[] parts = archiveUrl.split("/");
            if (parts.length >= 2) {
                return parts[parts.length - 2] + "-" + parts[parts.length - 1];
            }
        } catch (Exception ignored) {}
        return archiveUrl;
    }

    private List<String> fetchArchives(String username) throws Exception {
        String body = httpGet("https://api.chess.com/pub/player/" + username + "/games/archives");
        JSONObject o = new JSONObject(body);
        JSONArray arr = o.optJSONArray("archives");
        List<String> out = new ArrayList<String>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getString(i));
            }
        }
        return out;
    }

    private int fetchAndStoreMonth(DatabaseHelper db, String username, String archiveUrl) {
        try {
            String body = httpGetWithRetry(archiveUrl);
            JSONObject o = new JSONObject(body);
            JSONArray games = o.optJSONArray("games");
            if (games == null || games.length() == 0) return 0;
            JSONArray toInsert = new JSONArray();
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                long endTime = g.optLong("end_time", 0);
                if (endTime <= 0) continue;
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

    private String httpGetWithRetry(String urlStr) throws Exception {
        try {
            return httpGet(urlStr);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                Log.w(TAG, "HTTP 429 rate limit, sleeping 2500ms before retry...");
                try { Thread.sleep(2500); } catch (Exception ignored) {}
                return httpGet(urlStr);
            }
            throw e;
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
