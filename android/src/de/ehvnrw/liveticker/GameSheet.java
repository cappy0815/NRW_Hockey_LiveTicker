package de.ehvnrw.liveticker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Spiel-Steckbrief (Spielplan), Tabelle, Route und Kalendereintrag. */
final class GameSheet {
    private static final int C_TEXT = Color.rgb(34, 38, 43);
    private static final int C_MUTED = Color.rgb(110, 116, 124);
    private static final int C_FAV = Color.rgb(255, 246, 214);
    private static final int THEME = android.R.style.Theme_Material_Light_Dialog_Alert;
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Wappen im Hintergrund laden und setzen (TeamBadge zeigt sonst das Kuerzel) */
    static void loadBadge(final TeamBadge b, final String url, final String shortName) {
        b.setTeam(shortName, null);
        b.setTag(url);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final android.graphics.Bitmap bm = Api.logo(url);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url != null && url.equals(b.getTag())) b.setTeam(shortName, bm);
                    }
                });
            }
        });
    }

    /** Wappen in ein einfaches Bild laden (kleine Darstellung ohne Kuerzel) */
    static void loadImage(final android.widget.ImageView iv, final String url) {
        iv.setImageDrawable(null);
        iv.setTag(url);
        if (url == null || url.isEmpty()) return;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final android.graphics.Bitmap bm = Api.logo(url);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    private GameSheet() {
    }

    // ------------------------------------------------------------------ Livestream (v2.8)
    /**
     * Knopf zum Livestream (RED+-Logo „WATCH ON RED+“, sonst Textknopf) oder null, wenn kein Link
     * vorhanden bzw. in den Optionen ausgeschaltet. Ohne direkten Link: Tagesprogramm des Senders.
     */
    static View streamView(final Activity a, final Game g, boolean dark) {
        if (!Prefs.streamOn(a) || !g.hasStream()) return null;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(a, 10), 0, 0);
        View btn;
        if (g.isRedPlus()) {
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageResource(R.drawable.red_plus);
            iv.setAdjustViewBounds(true);
            iv.setContentDescription("Auf RED+ ansehen");
            btn = iv;
            box.addView(iv, new LinearLayout.LayoutParams(dp(a, 190), -2));
        } else {
            Button b = new Button(a);
            b.setText("\u25B6  " + (g.streamName.isEmpty() ? "Livestream" : g.streamName));
            Ui.pill(b, Ui.RED);
            btn = b;
            box.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStream(a, g);
            }
        });
        if (g.streamUrl.isEmpty()) {
            TextView t = new TextView(a);
            t.setText("kein direkter Link – öffnet das Tagesprogramm");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextColor(dark ? Color.rgb(170, 170, 170) : Color.rgb(110, 116, 124));
            t.setPadding(0, dp(a, 3), 0, 0);
            box.addView(t);
        }
        return box;
    }

    /** kurzer Hinweis für Listenzeilen ohne RED+-Logo: " · ▶ Stream" (leer bei RED+, kein Stream oder ausgeschaltet) */
    static String streamTag(android.content.Context c, Game g) {
        if (!Prefs.streamOn(c) || !g.hasStream() || g.isRedPlus()) return "";
        return "  ·  \u25B6 " + (g.streamName.isEmpty() ? "Stream" : g.streamName);
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** "NRW - U15" + "Regionalliga A Hauptrunde 2026/27" -> "U15 · Regionalliga A Hauptrunde" */
    static String leagueShort(Game g) {
        String l = g.league.trim().replaceFirst("^NRW\\s*-\\s*", "").trim();
        String d = divisionClean(g.division);
        return d.isEmpty() ? l : l + " · " + d;
    }

    /**
     * v2.81: Runde/Saison weglassen: "Hauptrunde 2026/27" -> "", "Regionalliga West Hauptrunde 26/27" -> "Regionalliga West",
     * "DFEL2 HR 2026/27" -> "DFEL2", "Hauptrunde 2026/27 Gruppe A" -> "Gruppe A". Andere Runden (z. B. Platzierungsrunde) bleiben.
     */
    static String divisionClean(String div) {
        if (div == null) return "";
        return div.replaceAll("\\b\\d{2,4}/\\d{2,4}\\b", " ")
                .replaceAll("(?i)\\bHauptrunde\\b|\\bHR\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** v2.81: kleines RED+-Logo in Listenzeilen, antippen öffnet den Stream direkt */
    static void bindStreamBadge(final Activity a, android.widget.ImageView iv, final Game g) {
        if (!Prefs.streamOn(a) || !g.hasStream() || !g.isRedPlus()) {
            iv.setVisibility(View.GONE);
            iv.setOnClickListener(null);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStream(a, g);
            }
        });
    }

    static android.widget.ImageView streamBadge(Activity a) {
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageResource(R.drawable.red_plus);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        iv.setPadding(dp(a, 6), dp(a, 4), 0, dp(a, 4)); // groessere Tippflaeche
        iv.setContentDescription("Auf RED+ ansehen");
        iv.setVisibility(View.GONE);
        return iv;
    }

    static void openStream(Activity a, Game g) {
        String url = g.streamUrl.isEmpty() ? g.streamDay : g.streamUrl;
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, "Kein Browser gefunden", Toast.LENGTH_SHORT).show();
        }
    }

    /** "Sa, 24.10." */
    static String dayLabel(long ms) {
        return new SimpleDateFormat("EE, dd.MM.", Locale.GERMANY).format(new Date(ms));
    }

    static String resultSuffix(Game g) {
        return g.shootout ? " n.P." : (g.overtime ? " n.V." : "");
    }

    // ------------------------------------------------------------------ Steckbrief
    static void show(final Activity a, final Game g) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 22), dp(a, 18), dp(a, 22), dp(a, 4));

        TextView lg = text(a, leagueShort(g), 12, C_MUTED, false);
        lg.setGravity(Gravity.CENTER);
        box.addView(lg, new LinearLayout.LayoutParams(-1, -2));

        // Kopf: Wappen + Kuerzel | Stand | Wappen + Kuerzel
        String mid = g.status == 0 ? "–" : g.homeScore + " : " + g.awayScore;
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(a, 8), 0, 0);
        head.addView(teamCol(a, g.homeLogo, g.homeShort));
        TextView title = text(a, mid, 28, C_TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(a, 14), 0, dp(a, 14), dp(a, 16));
        head.addView(title);
        head.addView(teamCol(a, g.awayLogo, g.awayShort));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView names = text(a, g.homeName + "\n– " + g.awayName, 14, C_TEXT, false);
        names.setGravity(Gravity.CENTER);
        names.setPadding(0, dp(a, 4), 0, dp(a, 10));
        box.addView(names, new LinearLayout.LayoutParams(-1, -2));

        String when = (g.startMs > 0 ? dayLabel(g.startMs) + new SimpleDateFormat("yyyy", Locale.GERMANY).format(new Date(g.startMs)) : g.dateText)
                + (g.scheduledTime.isEmpty() ? "" : " · " + g.scheduledTime + " Uhr");
        if (g.status == 4) // ohne Uhrzeit – das waere nur der Anstoss
            when = "Endstand" + resultSuffix(g) + " · " + (g.startMs > 0 ? dayLabel(g.startMs)
                    + new SimpleDateFormat("yyyy", Locale.GERMANY).format(new Date(g.startMs)) : g.dateText);
        if (g.status == 1) when = "LIVE · " + g.phaseText();
        box.addView(line(a, "📅", when, false));

        if (g.status != 0 && !g.periods.isEmpty()) {
            String p = g.periods.replace("(", "").replace(")", "").replace(",", "  ·  ");
            box.addView(line(a, "⏱", "Drittel:  " + p, false));
        }
        if (!g.homeScorers.isEmpty()) box.addView(line(a, "🥅", g.homeShort + ":  " + g.homeScorers, false));
        if (!g.awayScorers.isEmpty()) box.addView(line(a, "🥅", g.awayShort + ":  " + g.awayScorers, false));

        if (!g.arenaLong.isEmpty()) {
            TextView arena = line(a, "📍", g.arenaLong + (g.arenaAddress.isEmpty() ? "" : "\n" + g.arenaAddress) + "  ›", true);
            underlineAfterIcon(arena);
            arena.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    maps(a, g);
                }
            });
            box.addView(arena);
        }

        View stream = streamView(a, g, false);
        if (stream != null) box.addView(stream, new LinearLayout.LayoutParams(-1, -2));

        // Knoepfe (mittig): Kalender (nur kommende Spiele), Tabelle – Route = Halle antippen
        LinearLayout btns = new LinearLayout(a);
        btns.setGravity(Gravity.CENTER_HORIZONTAL);
        btns.setPadding(0, dp(a, 14), 0, dp(a, 6));
        if (g.status == 0 && g.startMs > System.currentTimeMillis()) btns.addView(button(a, "Kalender", Ui.GREEN, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calendar(a, g);
            }
        }), weight(a));
        if (!g.divisionId.isEmpty()) btns.addView(button(a, "Tabelle", Ui.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                standings(a, g.divisionId, leagueShort(g));
            }
        }), weight(a));
        if (btns.getChildCount() > 0) box.addView(btns, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        Ui.dialog(new AlertDialog.Builder(a, THEME).setView(sv).setPositiveButton("Schließen", null).show());
    }

    /**
     * Unterstreicht den Text ab dem ersten Leerzeichen (also ohne das Emoji davor).
     * Per Span statt Paint-Flag: das Flag zeichnet bei mehrzeiligem Text mit Emoji
     * die Linie der 1. Zeile durch die 2. Zeile (auf Michas Handy gesehen).
     */
    static void underlineAfterIcon(TextView t) {
        String s = t.getText().toString();
        int from = s.indexOf(' ');
        while (from >= 0 && from < s.length() && s.charAt(from) == ' ') from++;
        if (from < 0 || from >= s.length()) return;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        int start = from;
        // jede Zeile einzeln unterstreichen, damit der Zeilenumbruch selbst nicht unterstrichen wird
        for (int i = from; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                if (i > start) sp.setSpan(new android.text.style.UnderlineSpan(), start, i, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = i + 1;
            }
        }
        t.setText(sp);
    }

    private static LinearLayout teamCol(Activity a, String logo, String shortName) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TeamBadge b = new TeamBadge(a, dp(a, 52), false);
        loadBadge(b, logo, shortName);
        col.addView(b);
        TextView t = text(a, shortName, 13, C_TEXT, true);
        t.setPadding(0, dp(a, 3), 0, 0);
        col.addView(t);
        return col;
    }

    private static TextView line(Activity a, String icon, String s, boolean link) {
        TextView t = text(a, icon + "  " + s, 13, link ? Color.rgb(0, 110, 190) : C_TEXT, false);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4));
        return t;
    }

    private static Button button(Activity a, String label, int color, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(label);
        Ui.pill(b, color);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(a, 3);
        lp.rightMargin = dp(a, 3);
        return lp;
    }

    // ------------------------------------------------------------------ Route + Kalender
    static void maps(Activity a, Game g) {
        String q = g.arenaLong + (g.arenaAddress.isEmpty() ? "" : ", " + g.arenaAddress);
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))));
        } catch (Exception e) {
            Toast.makeText(a, "Keine Karten-App gefunden", Toast.LENGTH_SHORT).show();
        }
    }

    static void calendar(Activity a, Game g) {
        Intent i = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, g.startMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, g.startMs + 150 * 60 * 1000L)
                .putExtra(CalendarContract.Events.TITLE, "🏒 " + g.homeName + " – " + g.awayName)
                .putExtra(CalendarContract.Events.DESCRIPTION, leagueShort(g) + "\n(eingetragen mit EHV Live)")
                .putExtra(CalendarContract.Events.EVENT_LOCATION, g.arenaLong + (g.arenaAddress.isEmpty() ? "" : ", " + g.arenaAddress));
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Keine Kalender-App gefunden", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ Tabelle
    // ------------------------------------------------------------------ Tabelle + Liga-Statistik (v2.9)
    private static final String[] STAT_NAMES = {"Tabelle", "Scorer", "Torhüter", "Strafen"};

    /** Container, der waagerechtes Wischen erkennt, ohne das senkrechte Scrollen zu stören */
    private static class SwipeFrame extends android.widget.FrameLayout {
        android.view.GestureDetector gd;

        SwipeFrame(Activity a) {
            super(a);
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            if (gd != null) gd.onTouchEvent(ev);
            return super.dispatchTouchEvent(ev);
        }
    }

    /**
     * Tabelle einer Liga, seit v2.9 mit wischbaren Seiten Scorer · Torhüter · Strafen (gesamte Liga).
     * Reihenfolge der Statistik-Listen wie von hockeydata/ehv-nrw.de geliefert. Jede Seite lädt erst beim Öffnen.
     */
    static void standings(final Activity a, final String divisionId, String title) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 4));
        final TextView head = text(a, "Tabelle", 18, C_TEXT, true);
        box.addView(head);
        box.addView(text(a, title, 12, C_MUTED, false));

        final Button[] seg = new Button[4];
        LinearLayout segRow = new LinearLayout(a);
        segRow.setPadding(0, dp(a, 8), 0, dp(a, 4));
        final LinearLayout[] pages = new LinearLayout[4];
        final boolean[] loaded = new boolean[4];
        final int[] cur = {0};
        final java.util.Map<String, String> teamNames = new java.util.HashMap<>(); // teamId -> Langname (Hervorhebung)
        final android.widget.FrameLayout pageFrame = new android.widget.FrameLayout(a);
        final Runnable[] select = new Runnable[1];
        final int[] want = {0};
        select[0] = new Runnable() {
            @Override
            public void run() {
                int t = want[0];
                cur[0] = t;
                head.setText(STAT_NAMES[t]);
                for (int i = 0; i < 4; i++) {
                    pages[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
                    if (i == t) Ui.pill(seg[i], Ui.GREEN);
                    else Ui.outline(seg[i], Ui.DARK);
                    seg[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    seg[i].setPadding(dp(a, 2), dp(a, 6), dp(a, 2), dp(a, 6));
                }
                if (!loaded[t]) {
                    loaded[t] = true;
                    loadStatPage(a, divisionId, t, pages[t], teamNames);
                }
            }
        };
        for (int i = 0; i < 4; i++) {
            final int t = i;
            seg[i] = new Button(a);
            seg[i].setText(STAT_NAMES[i]);
            seg[i].setSingleLine(true);
            seg[i].setMinWidth(0);
            seg[i].setMinimumWidth(0);
            seg[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    want[0] = t;
                    select[0].run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = dp(a, 2);
            lp.rightMargin = dp(a, 2);
            segRow.addView(seg[i], lp);
            pages[i] = new LinearLayout(a);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pageFrame.addView(pages[i], new android.widget.FrameLayout.LayoutParams(-1, -2));
        }
        box.addView(segRow, new LinearLayout.LayoutParams(-1, -2));
        box.addView(pageFrame, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(a);
        sv.setScrollbarFadingEnabled(false);
        sv.addView(box);
        SwipeFrame frame = new SwipeFrame(a);
        frame.addView(sv);
        frame.gd = new android.view.GestureDetector(a, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(a, 80) || Math.abs(dx) < Math.abs(dy) * 2 || Math.abs(vx) < 500) return false;
                int t = cur[0] + (dx < 0 ? 1 : -1);
                if (t < 0 || t > 3) return false;
                want[0] = t;
                select[0].run();
                return true;
            }
        });
        Ui.dialog(new AlertDialog.Builder(a, THEME).setView(frame).setPositiveButton("Schließen", null).show());
        want[0] = 0;
        select[0].run();
    }

    /** Lädt eine Seite (0 Tabelle, 1 Scorer, 2 Torhüter, 3 Strafen) im Hintergrund und füllt sie */
    private static void loadStatPage(final Activity a, final String divisionId, final int page, final LinearLayout box,
                                     final java.util.Map<String, String> teamNames) {
        final TextView state = text(a, page == 0 ? "Lade Tabelle …" : "Lade Statistik der Liga …", 13, C_MUTED, false);
        state.setPadding(0, dp(a, 12), 0, dp(a, 12));
        box.addView(state);
        final TableLayout table = new TableLayout(a);
        table.setColumnStretchable(1, true);
        table.setColumnShrinkable(1, true);
        box.addView(table, new LinearLayout.LayoutParams(-1, -2));
        final String[] types = {null, "FieldPlayers", "Goalkeepers", "BadBoys"};
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<JSONObject> rows = null, teams = null;
                String err = null;
                try {
                    synchronized (teamNames) {
                        if (teamNames.isEmpty() || page == 0) teams = Api.standings(divisionId);
                    }
                    rows = page == 0 ? teams : Api.leaders(divisionId, types[page]);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<JSONObject> fRows = rows, fTeams = teams;
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fTeams != null) synchronized (teamNames) {
                            for (JSONObject t : fTeams) teamNames.put(t.optString("id", ""), t.optString("teamLongname", ""));
                        }
                        if (fRows == null || fRows.isEmpty()) {
                            state.setText(fErr != null ? "Keine Verbindung (" + fErr + ")"
                                    : page == 0 ? "Für diese Liga gibt es (noch) keine Tabelle." : "Für diese Liga gibt es (noch) keine Statistik.");
                            return;
                        }
                        state.setVisibility(View.GONE);
                        fillStatPage(a, page, table, fRows, teamNames);
                        String n = page == 0 ? "Team = Kürzel wie in Liste und Floating · S / N inklusive Verlängerung und Penaltyschießen"
                                : page == 1 ? "Sp = Spiele · T = Tore · A = Assists · P = Punkte · SM = Strafminuten · Top 50 der Liga"
                                : page == 2 ? "Sp = Spiele · GT = Gegentore · Ø = Gegentore je Spiel · Sv% = Fangquote (nur, wo Schüsse erfasst sind)"
                                : "Sp = Spiele · SM = Strafminuten · 2′ / 5′ = kleine / große Strafen · D = Disziplinar- und Matchstrafen · Top 50";
                        TextView note = text(a, n + (page == 0 ? "" : "\nQuelle: Liga-Statistik ehv-nrw.de · wischen für die nächste Seite"), 11, C_MUTED, false);
                        note.setPadding(0, dp(a, 8), 0, 0);
                        box.addView(note);
                    }
                });
            }
        }).start();
    }

    private static void fillStatPage(Activity a, int page, TableLayout table, List<JSONObject> rows, java.util.Map<String, String> teamNames) {
        if (page == 0) {
            table.addView(row(a, true, false, null, "#", "Team", "Sp", "S", "N", "Tore", "Pkt"));
            for (JSONObject r : rows) {
                String team = r.optString("teamLongname", "");
                String shortName = r.optString("teamShortname", team);
                int s = r.optInt("gamesWon") + r.optInt("gamesWonInOt");
                int n = r.optInt("gamesLost") + r.optInt("gamesLostInOt");
                table.addView(row(a, false, Prefs.isFavName(a, team), Api.logoUrl(r.optString("id", "")),
                        r.optString("tableRank", ""), shortName.isEmpty() ? team : shortName, r.optString("gamesPlayed", ""),
                        String.valueOf(s), String.valueOf(n),
                        r.optInt("goalsFor") + ":" + r.optInt("goalsAgainst"),
                        r.optString("points", "")));
            }
            return;
        }
        if (page == 1) table.addView(row(a, true, false, null, "#", "Spieler", "Sp", "T", "A", "P", "SM"));
        else if (page == 2) table.addView(row(a, true, false, null, "#", "Spieler", "Sp", "GT", "Ø", "Sv%"));
        else table.addView(row(a, true, false, null, "#", "Spieler", "Sp", "SM", "2′", "5′", "D"));
        int max = page == 2 ? rows.size() : Math.min(50, rows.size());
        for (int i = 0; i < max; i++) {
            JSONObject r = rows.get(i);
            String teamId = r.optString("teamId", "");
            String longName = teamNames.get(teamId);
            boolean fav = longName != null && Prefs.isFavName(a, longName);
            String name = r.optString("playerLastname", "") + " " + r.optString("playerFirstname", "").trim();
            String who = name.trim() + " · " + r.optString("teamShortname", "");
            String logo = Api.logoUrl(teamId);
            String rank = String.valueOf(i + 1);
            if (page == 1) {
                table.addView(row(a, false, fav, logo, rank, who, r.optString("gamesPlayed"), r.optString("goals"),
                        r.optString("assists"), r.optString("points"), r.optString("penaltyMinutes")));
            } else if (page == 2) {
                double sv = r.optDouble("savePercentage", 0);
                table.addView(row(a, false, fav, logo, rank, who, r.optString("gamesPlayed"), r.optString("goalsAgainst"),
                        String.format(Locale.GERMANY, "%.2f", r.optDouble("goalsAgainstAverage", 0)),
                        sv > 0 ? String.format(Locale.GERMANY, "%.1f", sv) : "–"));
            } else {
                int d = r.optInt("misconducts") + r.optInt("gameMisconducts") + r.optInt("matchPenalties");
                table.addView(row(a, false, fav, logo, rank, who, r.optString("gamesPlayed"), r.optString("penaltyMinutes"),
                        r.optString("minorPenalties"), r.optString("majorPenalties"), d == 0 ? "" : String.valueOf(d)));
            }
        }
    }

    private static TableRow row(Activity a, boolean head, boolean fav, String logo, String... cells) {
        TableRow tr = new TableRow(a);
        tr.setPadding(0, dp(a, 5), 0, dp(a, 5));
        if (fav) tr.setBackgroundColor(C_FAV);
        if (head) tr.setBackgroundColor(Color.rgb(238, 238, 238));
        for (int i = 0; i < cells.length; i++) {
            TextView t = text(a, cells[i], 13, head ? C_MUTED : C_TEXT, head || i == cells.length - 1 || fav);
            t.setPadding(dp(a, 4), 0, dp(a, 4), 0);
            if (i == 1) {
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                if (!head) { // kleines Wappen vor dem Teamnamen
                    LinearLayout cell = new LinearLayout(a);
                    cell.setGravity(Gravity.CENTER_VERTICAL);
                    android.widget.ImageView iv = new android.widget.ImageView(a);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    loadImage(iv, logo);
                    cell.addView(iv, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
                    cell.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
                    tr.addView(cell);
                    continue;
                }
            } else {
                t.setGravity(Gravity.CENTER);
            }
            tr.addView(t);
        }
        return tr;
    }
}
