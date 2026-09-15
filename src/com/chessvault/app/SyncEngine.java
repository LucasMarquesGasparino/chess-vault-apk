package com.chessvault.app;

import android.content.Context;
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

/**
 * Motor de sincronização puro (sem Service, sem Notification).
 * Usado pela VaultBridge em thread de fundo: o botão nunca mais
 * passa por startForegroundService, então não há como crashar com
 * ForegroundServiceDidNotStartInTimeException.
 */
public final class SyncEngine {
    private SyncEngine() {}

    private static final String TAG = "ChessVaultSync";
    static final String UA = "ChessVault/1.0 (Android; contact: lucas.gasparino; github.com/LucasMarquesGasparino)";

    public interface ProgressListener {
        void onProgress(String text);
    }

    public static int runSync(Context ctx, DatabaseHelper db, String mode, ProgressListener listener) throws Exception {
        String username = db.getConfig("username", "LuckGaspar");
        if (username == null) username = "";
        username = username.trim().toLowerCase();
        if (username.isEmpty()) throw new Exception("Configure o username primeiro");

        List<String> archives = fetchArchives(username);
        if (archives.isEmpty()) throw new Exception("Nenhum arquivo encontrado para " + username);

        SyncService.SyncProgress.totalMonths = archives.size();
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

                SyncService.SyncProgress.currentMonth = i + 1;
                SyncService.SyncProgress.currentArchive = ym;
                SyncService.SyncProgress.statusMessage = "Mês " + (i + 1) + "/" + archives.size() + " (" + ym + ")";

                if (!isCurrentMonth && db.isArchiveSynced(archiveUrl)) continue;
                if (listener != null) listener.onProgress("Mês " + (i + 1) + "/" + archives.size() + " (" + ym + ")");

                int inserted = fetchAndStoreMonth(db, username, archiveUrl);
                totalInserted += inserted;
                SyncService.SyncProgress.gamesInserted = totalInserted;

                if (!isCurrentMonth) db.markArchiveSynced(archiveUrl, ym, inserted, true);
                try { Thread.sleep(120); } catch (Exception ignored) {}
            }
            db.putConfig("full_sync_completed", "true");
            java.text.SimpleDateFormat sdfFull = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            db.putConfig("full_sync_date", sdfFull.format(new java.util.Date()));
        } else {
            int startIdx = Math.max(0, archives.size() - 2);
            SyncService.SyncProgress.totalMonths = archives.size() - startIdx;
            int step = 0;
            for (int i = startIdx; i < archives.size(); i++) {
                step++;
                String archiveUrl = archives.get(i);
                String ym = extractYearMonth(archiveUrl);

                SyncService.SyncProgress.currentMonth = step;
                SyncService.SyncProgress.currentArchive = ym;
                SyncService.SyncProgress.statusMessage = "Mês " + ym;
                if (listener != null) listener.onProgress("Sync recente (" + ym + ")...");

                int inserted = fetchAndStoreMonth(db, username, archiveUrl);
                totalInserted += inserted;
                SyncService.SyncProgress.gamesInserted = totalInserted;
                try { Thread.sleep(120); } catch (Exception ignored) {}
            }
        }

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
        db.putConfig("last_alarm_run", String.valueOf(System.currentTimeMillis()));

        Log.i(TAG, "sync done mode=" + mode + " total=" + totalInserted);
        return totalInserted;
    }

    static String extractYearMonth(String archiveUrl) {
        try {
            String[] parts = archiveUrl.split("/");
            if (parts.length >= 2) return parts[parts.length - 2] + "-" + parts[parts.length - 1];
        } catch (Exception ignored) {}
        return archiveUrl;
    }

    static List<String> fetchArchives(String username) throws Exception {
        String body = httpGet("https://api.chess.com/pub/player/" + username + "/games/archives");
        JSONObject o = new JSONObject(body);
        JSONArray arr = o.optJSONArray("archives");
        List<String> out = new ArrayList<String>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        }
        return out;
    }

    static int fetchAndStoreMonth(DatabaseHelper db, String username, String archiveUrl) {
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

    static String httpGetWithRetry(String urlStr) throws Exception {
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

    static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
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
