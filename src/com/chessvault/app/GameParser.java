package com.chessvault.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser PGN puro-Java (sem dependência Android) para permitir teste unitário.
 * Extrai: lances SAN, relógios [%clk], identifica cor do usuário, classifica
 * resultado e calcula proxy de erro (mate recebido -> lance ~3 antes do fim).
 */
public final class GameParser {
    private GameParser() {}

    private static final Pattern MOVE_NUM = Pattern.compile("\\d+\\.{1,3}\\s*");
    private static final Pattern CLK = Pattern.compile("\\{\\[%clk (\\d+):(\\d+):([\\d.]+)\\]}");
    private static final Pattern SAN_TOKEN = Pattern.compile("[^\\s{}]+");
    private static final Pattern RESULT_TOK = Pattern.compile("^(1-0|0-1|1/2-1/2|\\*)$");

    public static final class Parsed {
        public final List<String> movesSan = new ArrayList<String>();
        public final List<Double> clocksSec = new ArrayList<Double>();
        public int moveCount;
    }

    /** Separa movetext: remove comentários {...}, NAGs $n, número de lance e resultado. */
    public static Parsed parseMoves(String pgn) {
        Parsed p = new Parsed();
        if (pgn == null) return p;
        int bodyStart = pgn.indexOf("\n\n");
        String body = bodyStart >= 0 ? pgn.substring(bodyStart) : pgn;
        Matcher clkM = CLK.matcher(body);
        while (clkM.find()) {
            try {
                double s = Integer.parseInt(clkM.group(1)) * 3600.0
                        + Integer.parseInt(clkM.group(2)) * 60.0
                        + Double.parseDouble(clkM.group(3));
                p.clocksSec.add(s);
            } catch (Exception ignored) {}
        }
        String noComments = body.replaceAll("\\{[^}]*\\}", " ");
        noComments = noComments.replaceAll("\\$\\d+", " ");
        noComments = MOVE_NUM.matcher(noComments).replaceAll(" ");
        Matcher m = SAN_TOKEN.matcher(noComments);
        while (m.find()) {
            String tok = m.group();
            if (RESULT_TOK.matcher(tok).matches()) break;
            if (tok.equals("...")) continue;
            p.movesSan.add(tok);
        }
        p.moveCount = (p.movesSan.size() + 1) / 2;
        return p;
    }

    /** "rnbqkbnr/..." só conta peças para inferir fase: <=10 fim, <=20 meio? Não: usa moveCount. */
    public static String phaseForMoveNum(int moveNum) {
        if (moveNum <= 12) return "abertura";
        if (moveNum <= 30) return "meio";
        return "final";
    }

    /**
     * Proxy de erro: se perdeu por mate, o erro provável está ~2 lances cheios
     * antes do fim (4 plies se de brancas, 3 se de pretas — ímpar do fim).
     * Retorna número do lance ou -1.
     */
    public static int errorMoveNum(String myResult, String myColor, int totalPlies) {
        if (!"checkmated".equals(myResult)) return -1;
        if (totalPlies < 4) return -1;
        int errPly = "white".equals(myColor) ? totalPlies - 4 : totalPlies - 3;
        if (errPly < 0) errPly = 0;
        return errPly / 2 + 1;
    }

    public static boolean isWin(String r) { return "win".equals(r); }

    public static boolean isDraw(String r) {
        return "agreed".equals(r) || "repetition".equals(r) || "stalemate".equals(r)
                || "insufficient".equals(r) || "50move".equals(r) || "timevsinsufficient".equals(r);
    }

    public static boolean isLoss(String r) { return !isWin(r) && !isDraw(r); }

    /** Nome legível da abertura a partir da ECOUrl: ".../Alapin-Sicilian-Defense-2...Qa5". */
    public static String ecoNameFromUrl(String ecoUrl) {
        if (ecoUrl == null || ecoUrl.isEmpty()) return "";
        String tail = ecoUrl;
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) tail = tail.substring(slash + 1);
        tail = tail.replace('-', ' ');
        return tail.trim();
    }

    /** Tempo médio gasto por fase (segundos/lance) a partir dos relógios. Retorna [ab, meio, fim] ou null. */
    public static double[] avgTimeByPhase(List<Double> clocksSec) {
        if (clocksSec == null || clocksSec.size() < 4) return null;
        double[] sum = new double[3];
        int[] n = new int[3];
        for (int i = 1; i < clocksSec.size(); i++) {
            double spent = clocksSec.get(i - 1) - clocksSec.get(i);
            if (spent < 0 || spent > 3600) continue;
            int moveNum = i / 2 + 1;
            int ph = moveNum <= 12 ? 0 : (moveNum <= 30 ? 1 : 2);
            sum[ph] += spent;
            n[ph]++;
        }
        if (n[0] + n[1] + n[2] == 0) return null;
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) out[k] = n[k] == 0 ? 0 : sum[k] / n[k];
        return out;
    }

    /** yyyy-MM-dd a partir de end_time (segundos epoch). */
    public static String dateStr(long endTimeSec) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            return sdf.format(new java.util.Date(endTimeSec * 1000L));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Achata um game JSON da PubAPI num objeto pronto p/ insertGames.
     * Puro-Java (org.json vem no android.jar) — testável sem emulador.
     * Retorna null se a partida não envolve o username.
     */
    public static org.json.JSONObject flattenGame(String username, org.json.JSONObject g) {
        try {
            org.json.JSONObject white = g.optJSONObject("white");
            org.json.JSONObject black = g.optJSONObject("black");
            if (white == null || black == null) return null;
            boolean isWhite = username.equalsIgnoreCase(white.optString("username", ""));
            boolean isBlack = username.equalsIgnoreCase(black.optString("username", ""));
            if (!isWhite && !isBlack) return null;
            org.json.JSONObject me = isWhite ? white : black;
            org.json.JSONObject opp = isWhite ? black : white;
            String myResult = me.optString("result", "");
            String pgn = g.optString("pgn", "");
            Parsed parsed = parseMoves(pgn);
            int errMove = errorMoveNum(myResult, isWhite ? "white" : "black", parsed.movesSan.size());
            org.json.JSONObject flat = new org.json.JSONObject();
            flat.put("uuid", g.optString("uuid", ""));
            flat.put("url", g.optString("url", ""));
            long endTime = g.optLong("end_time", 0);
            flat.put("end_time", endTime);
            flat.put("end_date", dateStr(endTime));
            flat.put("time_class", g.optString("time_class", ""));
            flat.put("time_control", g.optString("time_control", ""));
            flat.put("rated", g.optBoolean("rated", true) ? 1 : 0);
            flat.put("rules", g.optString("rules", ""));
            flat.put("my_color", isWhite ? "white" : "black");
            flat.put("my_rating", me.optInt("rating", 0));
            flat.put("opp_username", opp.optString("username", ""));
            flat.put("opp_rating", opp.optInt("rating", 0));
            flat.put("my_result", myResult);
            flat.put("opp_result", opp.optString("result", ""));
            flat.put("is_win", isWin(myResult) ? 1 : 0);
            flat.put("is_loss", isLoss(myResult) ? 1 : 0);
            flat.put("is_draw", isDraw(myResult) ? 1 : 0);
            flat.put("is_timeout_loss", "timeout".equals(myResult) ? 1 : 0);
            flat.put("is_mate_loss", "checkmated".equals(myResult) ? 1 : 0);
            flat.put("eco", g.optString("eco", ""));
            flat.put("eco_name", ecoNameFromUrl(g.optString("eco", "")));
            org.json.JSONObject acc = g.optJSONObject("accuracies");
            if (acc != null) {
                String accKey = isWhite ? "white" : "black";
                String oppKey = isWhite ? "black" : "white";
                if (acc.has(accKey) && !acc.isNull(accKey)) flat.put("accuracy", acc.optDouble(accKey));
                else flat.put("accuracy", org.json.JSONObject.NULL);
                if (acc.has(oppKey) && !acc.isNull(oppKey)) flat.put("opp_accuracy", acc.optDouble(oppKey));
                else flat.put("opp_accuracy", org.json.JSONObject.NULL);
            } else {
                flat.put("accuracy", org.json.JSONObject.NULL);
                flat.put("opp_accuracy", org.json.JSONObject.NULL);
            }
            flat.put("move_count", parsed.moveCount);
            flat.put("pgn", pgn);
            org.json.JSONArray clocks = new org.json.JSONArray();
            for (Double s : parsed.clocksSec) clocks.put(s);
            flat.put("clocks_json", clocks.toString());
            org.json.JSONArray sans = new org.json.JSONArray();
            for (String s : parsed.movesSan) sans.put(s);
            flat.put("moves_san", sans.toString());
            if (errMove > 0) {
                flat.put("err_move_num", errMove);
                flat.put("err_phase", phaseForMoveNum(errMove));
            } else {
                flat.put("err_move_num", org.json.JSONObject.NULL);
                flat.put("err_phase", org.json.JSONObject.NULL);
            }
            return flat;
        } catch (Exception e) {
            return null;
        }
    }
}
