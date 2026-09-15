import java.util.List;

/** Teste puro-Java (javac + java): valida GameParser com PGN real do luckgaspar. */
public class GameParserTest {
    static int pass = 0, fail = 0;

    static void check(boolean cond, String name) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static final String PGN_MATE =
        "[Event \"Live Chess\"]\n[Site \"Chess.com\"]\n[White \"luckgaspar\"]\n[Black \"oppx\"]\n" +
        "[Result \"0-1\"]\n[ECO \"B22\"]\n[ECOUrl \"https://www.chess.com/openings/Alapin-Sicilian-Defense-2...Qa5\"]\n\n" +
        "1. e4 {[%clk 0:05:00]} 1... c5 {[%clk 0:05:00]} 2. Nf3 {[%clk 0:04:55]} 2... d6 {[%clk 0:04:58]} " +
        "3. d4 {[%clk 0:04:50]} 3... cxd4 {[%clk 0:04:55]} 4. Nxd4 {[%clk 0:04:48]} 4... Nf6 {[%clk 0:04:52]} 0-1";

    static final String PGN_NOCLOCK =
        "[Event \"Live Chess\"]\n\n1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0";

    public static void main(String[] a) {
        com.chessvault.app.GameParser.Parsed p =
                com.chessvault.app.GameParser.parseMoves(PGN_MATE);
        check(p.movesSan.size() == 8, "parseMoves 8 plies, got=" + p.movesSan.size());
        check(p.movesSan.get(0).equals("e4"), "primeiro lance e4");
        check(p.movesSan.get(7).equals("Nf6"), "ultimo lance Nf6");
        check(p.moveCount == 4, "moveCount=4");
        check(p.clocksSec.size() == 8, "8 relogios, got=" + p.clocksSec.size());
        check(Math.abs(p.clocksSec.get(0) - 300.0) < 0.01, "clk 5min=300s");

        com.chessvault.app.GameParser.Parsed p2 =
                com.chessvault.app.GameParser.parseMoves(PGN_NOCLOCK);
        check(p2.movesSan.size() == 6, "noclock 6 plies");
        check(p2.clocksSec.isEmpty(), "noclock sem relogios");
        check(p2.moveCount == 3, "noclock 3 lances");

        String pgnCrlf = "[Event \"Live Chess\"]\r\n[Site \"Chess.com\"]\r\n[Result \"1-0\"]\r\n\r\n1. e4 e5 2. Nf3 Nc6 1-0";
        com.chessvault.app.GameParser.Parsed pCrlf =
                com.chessvault.app.GameParser.parseMoves(pgnCrlf);
        check(pCrlf.movesSan.size() == 4, "crlf 4 plies, got=" + pCrlf.movesSan.size());
        check(pCrlf.movesSan.get(0).equals("e4"), "crlf primeiro lance e4");

        check(com.chessvault.app.GameParser.parseMoves(null).movesSan.isEmpty(), "null vazio");

        check(com.chessvault.app.GameParser.phaseForMoveNum(8).equals("abertura"), "fase L8");
        check(com.chessvault.app.GameParser.phaseForMoveNum(12).equals("abertura"), "fase L12");
        check(com.chessvault.app.GameParser.phaseForMoveNum(13).equals("meio"), "fase L13");
        check(com.chessvault.app.GameParser.phaseForMoveNum(30).equals("meio"), "fase L30");
        check(com.chessvault.app.GameParser.phaseForMoveNum(31).equals("final"), "fase L31");

        check(com.chessvault.app.GameParser.errorMoveNum("checkmated", "white", 40) == 19, "erro brancas 40plies=L19");
        check(com.chessvault.app.GameParser.errorMoveNum("checkmated", "black", 40) == 19, "erro pretas 40plies=L19");
        check(com.chessvault.app.GameParser.errorMoveNum("resigned", "white", 40) == -1, "resign sem erro");
        check(com.chessvault.app.GameParser.errorMoveNum("timeout", "white", 40) == -1, "timeout sem erro");
        check(com.chessvault.app.GameParser.errorMoveNum("checkmated", "white", 2) == -1, "jogo curto -1");

        check(com.chessvault.app.GameParser.isWin("win"), "isWin");
        check(!com.chessvault.app.GameParser.isWin("resigned"), "resign nao win");
        check(com.chessvault.app.GameParser.isDraw("agreed"), "agreed draw");
        check(com.chessvault.app.GameParser.isDraw("stalemate"), "stalemate draw");
        check(com.chessvault.app.GameParser.isLoss("checkmated"), "mate loss");
        check(com.chessvault.app.GameParser.isLoss("timeout"), "timeout loss");
        check(!com.chessvault.app.GameParser.isLoss("win"), "win nao loss");

        check(com.chessvault.app.GameParser.ecoNameFromUrl(
                "https://www.chess.com/openings/Alapin-Sicilian-Defense-2...Qa5")
                .equals("Alapin Sicilian Defense 2...Qa5"), "ecoName");
        check(com.chessvault.app.GameParser.ecoNameFromUrl("").equals(""), "eco vazio");
        check(com.chessvault.app.GameParser.ecoNameFromUrl(null).equals(""), "eco null");

        java.util.List<Double> clks = new java.util.ArrayList<Double>();
        for (int i = 0; i < 30; i++) clks.add(300.0 - i * 5.0);
        double[] avg = com.chessvault.app.GameParser.avgTimeByPhase(clks);
        check(avg != null && Math.abs(avg[0] - 5.0) < 0.01, "avgTime 5s/lance");
        check(com.chessvault.app.GameParser.avgTimeByPhase(null) == null, "avgTime null");
        check(com.chessvault.app.GameParser.avgTimeByPhase(new java.util.ArrayList<Double>()) == null, "avgTime vazio");

        check(com.chessvault.app.GameParser.dateStr(1754000000L).length() == 10, "dateStr yyyy-MM-dd");

        try {
            org.json.JSONObject g = new org.json.JSONObject();
            g.put("uuid", "x");
            g.put("url", "https://www.chess.com/game/live/1");
            g.put("end_time", 1754000000L);
            g.put("time_class", "blitz");
            g.put("time_control", "300");
            g.put("rated", true);
            g.put("rules", "chess");
            g.put("eco", "https://www.chess.com/openings/Alapin-Sicilian-Defense-2...Qa5");
            g.put("pgn", PGN_MATE.replace("luckgaspar", "ZZZFLAT").replace("oppx", "luckgaspar"));
            org.json.JSONObject w = new org.json.JSONObject();
            w.put("username", "ZZZFLAT"); w.put("rating", 600); w.put("result", "win");
            org.json.JSONObject b = new org.json.JSONObject();
            b.put("username", "luckgaspar"); b.put("rating", 606); b.put("result", "checkmated");
            g.put("white", w); g.put("black", b);
            org.json.JSONObject flat = com.chessvault.app.GameParser.flattenGame("luckgaspar", g);
            check(flat != null, "flattenGame nao null");
            check("black".equals(flat.optString("my_color")), "flatten cor pretas");
            check(flat.optInt("is_mate_loss") == 1, "flatten mate_loss");
            check(flat.optInt("move_count") == 4, "flatten move_count");
            check(flat.optString("eco_name").contains("Alapin"), "flatten eco");
            check(flat.has("err_move_num") && flat.optInt("err_move_num") > 0, "flatten err_move");
        } catch (Exception e) {
            check(false, "flattenGame exception: " + e.getMessage());
        }

        System.out.println("GameParserTest: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
