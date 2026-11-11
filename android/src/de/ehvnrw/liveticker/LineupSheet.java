package de.ehvnrw.liveticker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aufstellung beider Teams (v2.5: eigene Klasse, damit Liste UND Detailfenster sie oeffnen koennen).
 * Inhalt unveraendert aus MainActivity 2.4 uebernommen.
 */
final class LineupSheet {
    private static final int C_TEXT = Ui.TEXT;
    private static final int C_MUTED = Ui.MUTED;
    private static final int C_ACCENT = Ui.RED;
    private static final int C_GREEN = Ui.GREEN;
    private static final int THEME = android.R.style.Theme_Material_Light_Dialog_Alert;
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final int C_STAR = Color.rgb(214, 150, 0);     // Gold fuer den Topscorer-Stern

    private LineupSheet() {
    }

    static void show(final Activity a, final Game g) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 6));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);

        TextView lg = text(a, GameSheet.leagueShort(g), 12, C_MUTED, false);
        lg.setGravity(Gravity.CENTER);
        box.addView(lg, new LinearLayout.LayoutParams(-1, -2));
        final TextView title = text(a, g.homeShort + "   " + (g.type == 1 ? "–" : g.score()) + "   " + g.awayShort, 20, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));
        Ui.stripe(a, box, 10);

        final LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        box.addView(content);
        final TextView state = text(a, "Lade Aufstellung …", 13, C_MUTED, false);
        state.setPadding(0, dp(a, 12), 0, dp(a, 4));
        content.addView(state);

        Ui.dialog(new android.app.AlertDialog.Builder(a, THEME).setView(sv).setPositiveButton("Schließen", null).show());

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                org.json.JSONObject data = null;
                try {
                    data = Api.reportData(g.id);
                } catch (Exception ignored) {
                }
                // v2.9: Topscorer je Team aus der Liga-Statistik (nur auf Abruf, 30 Min. im Speicher)
                java.util.Map<Long, org.json.JSONObject> stats = new java.util.HashMap<>();
                if (data != null && !g.divisionId.isEmpty()) {
                    try {
                        for (org.json.JSONObject r : Api.leaders(g.divisionId, "FieldPlayers")) stats.put(r.optLong("id", -1), r);
                    } catch (Exception ignored) {
                    }
                }
                final java.util.Map<Long, org.json.JSONObject> fRanks = stats;
                final org.json.JSONObject fData = data;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fData == null) {
                            state.setText("Keine Verbindung – bitte später erneut versuchen.");
                            return;
                        }
                        org.json.JSONObject gd = fData.optJSONObject("gameData");
                        if (gd != null && gd.optInt("gameStatus", 0) != 0)
                            title.setText(gd.optString("homeTeamShortname") + "   " + gd.optInt("homeTeamScore") + " : "
                                    + gd.optInt("awayTeamScore") + "   " + gd.optString("awayTeamShortname"));
                        content.removeAllViews();
                        int n = lineupSide(a, content, fData, "home", g.homeName, fRanks);
                        n += lineupSide(a, content, fData, "away", g.awayName, fRanks);
                        TextView foot = text(a, n == 0
                                ? "Aufstellung noch nicht verfügbar – wird eingetragen, sobald der Anschreiber sie erfasst."
                                : "R = Reihe · T = Tore · A = Assists · SM = Strafminuten · GT = Gegentore"
                                + (fRanks.isEmpty() ? "" : "\n☆ 1–3 = die drei besten Scorer dieser Aufstellung (Saisonpunkte in dieser Liga, "
                                + "bei Gleichstand mehr Tore) · T/A/SM = dieses Spiel · Tabelle seitlich wischbar"), 11, C_MUTED, false);
                        foot.setPadding(0, dp(a, 12), 0, 0);
                        content.addView(foot);
                    }
                });
            }
        });
    }

    /** Eine Mannschaft: zuerst Torhueter, dann Spieler nach Reihe und Position. Liefert die Anzahl. */
    /**
     * v2.91: Rang 1–3 unter den Feldspielern DIESER Aufstellung (vorher Top 3 des ganzen Kaders – fehlte einer im Spiel,
     * fehlte sein Stern). Sortiert nach Saisonpunkten in der Liga, dann Toren; Spieler ohne Punkte zählen nicht.
     */
    static java.util.Map<Long, Integer> lineupRanks(org.json.JSONArray fp, java.util.Map<Long, org.json.JSONObject> stats) {
        java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        if (fp == null || stats.isEmpty()) return out;
        List<org.json.JSONObject> l = new ArrayList<>();
        for (int i = 0; i < fp.length(); i++) {
            org.json.JSONObject p = fp.optJSONObject(i);
            org.json.JSONObject st = p == null ? null : stats.get(p.optLong("id", -2));
            if (st != null && st.optInt("points", 0) > 0) l.add(st);
        }
        java.util.Collections.sort(l, new java.util.Comparator<org.json.JSONObject>() {
            @Override
            public int compare(org.json.JSONObject x, org.json.JSONObject y) {
                int d = y.optInt("points") - x.optInt("points");
                return d != 0 ? d : y.optInt("goals") - x.optInt("goals");
            }
        });
        for (int i = 0; i < l.size() && i < 3; i++) out.put(l.get(i).optLong("id", -1), i + 1);
        return out;
    }

    private static int lineupSide(Activity a, LinearLayout box, org.json.JSONObject data, String side, String teamName,
                                  java.util.Map<Long, org.json.JSONObject> stats) {
        List<String[]> rows = new ArrayList<>();
        org.json.JSONArray gk = data.optJSONArray(side + "GoalKeepers");
        if (gk != null) for (int i = 0; i < gk.length(); i++) {
            org.json.JSONObject p = gk.optJSONObject(i);
            if (p == null) continue;
            boolean start = p.optInt("isStartingPlayer", 0) == 1 || p.optBoolean("isStartingPlayer", false);
            rows.add(new String[]{"0" + (start ? "0" : "1") + String.format(Locale.GERMANY, "%03d", parseNr(p)),
                    p.optString("playerJerseyNr"),
                    lineupName(p) + (start ? " (Start)" : "") + " · " + p.optInt("goalsAgainst") + " GT",
                    "G", "", num(p.optInt("goals")), num(p.optInt("assists")), num(p.optInt("penaltyMinutes")), ""});
        }
        org.json.JSONArray fp = data.optJSONArray(side + "FieldPlayers");
        java.util.Map<Long, Integer> ranks = lineupRanks(fp, stats);
        if (fp != null) for (int i = 0; i < fp.length(); i++) {
            org.json.JSONObject p = fp.optJSONObject(i);
            if (p == null) continue;
            int line = p.optInt("lineupLine", 0);
            String pos = p.optString("position", "");
            rows.add(new String[]{"1" + String.format(Locale.GERMANY, "%02d", line > 0 ? line : 99) + posOrder(pos)
                    + String.format(Locale.GERMANY, "%03d", parseNr(p)),
                    p.optString("playerJerseyNr"), lineupName(p), pos, line > 0 ? String.valueOf(line) : "",
                    num(p.optInt("goals")), num(p.optInt("assists")), num(p.optInt("penaltyMinutes")),
                    ranks.containsKey(p.optLong("id", -2)) ? String.valueOf(ranks.get(p.optLong("id", -2))) : ""});
        }
        if (rows.isEmpty()) return 0;
        java.util.Collections.sort(rows, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareTo(b[0]);
            }
        });
        section(a, box, teamName);
        // v2.91: Tabelle seitlich scrollbar, Namen vollständig (vorher abgeschnitten)
        android.widget.TableLayout table = new android.widget.TableLayout(a);
        table.setColumnStretchable(1, true);
        table.addView(lineupRow(a, new String[]{"", "#", "Name", "Pos", "R", "T", "A", "SM", ""}, true));
        for (String[] r : rows) table.addView(lineupRow(a, r, false));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setFillViewport(true);
        hs.setHorizontalScrollBarEnabled(true);
        hs.addView(table, new android.widget.FrameLayout.LayoutParams(-2, -2));
        box.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        return rows.size();
    }

    private static android.widget.TableRow lineupRow(Activity a, String[] r, boolean head) {
        android.widget.TableRow row = new android.widget.TableRow(a);
        row.setPadding(0, dp(a, 2), 0, dp(a, 2));
        row.setGravity(Gravity.CENTER_VERTICAL);
        int color = head ? C_MUTED : C_TEXT;
        int[] w = {30, 0, 34, 22, 22, 22, 28};
        for (int i = 0; i < 7; i++) {
            TextView t = text(a, r[i + 1], head ? 11 : 12.5f, color, head || i == 0);
            t.setSingleLine(true);
            if (i == 1) {
                t.setPadding(dp(a, 2), 0, dp(a, 10), 0);
                if (!head && r.length > 8 && !r[8].isEmpty()) { // Topscorer-Stern vor dem Namen
                    t.setCompoundDrawables(new StarDrawable(r[8], C_STAR, dp(a, 26)), null, null, null);
                    t.setCompoundDrawablePadding(dp(a, 4));
                }
                t.setGravity(Gravity.CENTER_VERTICAL);
            } else {
                t.setGravity(Gravity.CENTER);
                t.setMinWidth(dp(a, w[i]));
            }
            row.addView(t);
        }
        return row;
    }

    private static String lineupName(org.json.JSONObject p) {
        String first = p.optString("playerFirstname", "");
        return p.optString("playerLastname", "") + (first.isEmpty() ? "" : " " + first);
    }

    private static int parseNr(org.json.JSONObject p) {
        try {
            return Integer.parseInt(p.optString("playerJerseyNr", "0").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String num(int v) {
        return v == 0 ? "" : String.valueOf(v);
    }

    private static String posOrder(String pos) {
        switch (pos) {
            case "LD": return "1";
            case "RD": return "2";
            case "D": return "3";
            case "LF": case "LW": return "4";
            case "C": return "5";
            case "RF": case "RW": return "6";
            case "F": return "7";
            default: return "8";
        }
    }


    private static int dp(Activity a, float v) {
        return Ui.dp(a, v);
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        return Ui.text(a, s, sp, color, bold);
    }

    private static void section(Activity a, LinearLayout box, String title) {
        Ui.section(a, box, title);
    }
}
