package de.ehvnrw.liveticker;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v2.96: Schiedsrichter-Einteilungen aller Spiele von esrw.de (ca. 12 Tage), nach Tagen gruppiert.
 * Aufruf über die App-Verknüpfung „Schiedsrichter“. Hervorgehobene Teams gelb, Schalter „nur meine Teams“.
 * Spiele, die im EHV-Saisonplan gefunden werden, öffnen per Antippen den Steckbrief (›).
 */
final class RefereesSheet {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static boolean onlyMine = false;   // merkt sich die Wahl, solange die App läuft

    private RefereesSheet() {
    }

    static void show(final Activity a) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(Ui.dp(a, 20), Ui.dp(a, 18), Ui.dp(a, 20), 0);
        outer.addView(Ui.text(a, "Schiedsrichter-Einteilungen", 18, Ui.TEXT, true));
        final TextView sub = Ui.text(a, "Lade esrw.de …", 12, Ui.MUTED, false);
        outer.addView(sub);
        Ui.stripe(a, outer, 10);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final Switch sw = Ui.toggle(a, "Nur meine Teams", onlyMine);
        sw.setEnabled(Prefs.favOn(a));
        bar.addView(sw, new LinearLayout.LayoutParams(0, -2, 1f));
        final Button reload = new Button(a);
        reload.setText("↻");
        Ui.outline(reload, Ui.DARK);
        reload.setMinWidth(0);
        reload.setMinimumWidth(0);
        reload.setPadding(Ui.dp(a, 14), Ui.dp(a, 4), Ui.dp(a, 14), Ui.dp(a, 4));
        reload.setContentDescription("Neu laden");
        bar.addView(reload, new LinearLayout.LayoutParams(-2, -2));
        outer.addView(bar);

        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(a, 8));
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        outer.addView(Ui.withScrollHint(sv), new LinearLayout.LayoutParams(-1, -2));

        final AlertDialog dlg = new AlertDialog.Builder(a).setView(outer).setPositiveButton("Schließen", null).show();
        Ui.dialog(dlg);

        final List<Referees.Row>[] rows = new List[]{new ArrayList<Referees.Row>()};
        final List<Game>[] season = new List[]{new ArrayList<Game>()};
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                fill(a, dlg, list, rows[0], season[0], sw.isChecked());
            }
        };
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                onlyMine = on;
                fill.run();
            }
        });
        final Runnable[] load = new Runnable[1];
        load[0] = new Runnable() {
            @Override
            public void run() {
                load(a, sub, reload, rows, season, fill, false);
            }
        };
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(a, sub, reload, rows, season, fill, true);
            }
        });
        load[0].run();
    }

    private static void load(final Activity a, final TextView sub, final Button reload, final List<Referees.Row>[] rows,
                             final List<Game>[] season, final Runnable fill, final boolean force) {
        sub.setText("Lade esrw.de …");
        sub.setTextColor(Ui.MUTED);
        reload.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Referees.Row> r = Referees.all(force);
                final long at = Referees.loadedAt();
                List<Game> s;
                try {
                    s = Api.season(false);     // für den Steckbrief; 30-Min.-Speicher wie im Spielplan
                } catch (Exception e) {
                    s = new ArrayList<>();
                }
                final List<Game> fs = s;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        reload.setEnabled(true);
                        if (r.isEmpty()) {
                            sub.setText("esrw.de nicht erreichbar – später mit ↻ erneut versuchen");
                            sub.setTextColor(Ui.RED);
                        } else {
                            sub.setText(r.size() + " Spiele · Quelle esrw.de · Stand "
                                    + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(at)));
                        }
                        rows[0] = r;
                        season[0] = fs;
                        fill.run();
                    }
                });
            }
        }).start();
    }

    private static void fill(final Activity a, final AlertDialog dlg, LinearLayout list, List<Referees.Row> rows,
                             List<Game> season, boolean mine) {
        list.removeAllViews();
        String lastDate = "";
        int shown = 0;
        for (final Referees.Row r : rows) {
            boolean fav = Prefs.isFavName(a, r.home + "|" + r.away);
            if (mine && !fav) continue;
            if (!r.date.equals(lastDate)) {
                lastDate = r.date;
                Ui.section(a, list, dayTitle(r.date));
            }
            shown++;
            final Game g = Referees.findGame(r, season);
            LinearLayout row = new LinearLayout(a);
            row.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
            if (fav) row.setBackgroundColor(Ui.FAV);

            TextView time = Ui.text(a, r.time, 13, Ui.TEXT, true);
            row.addView(time, new LinearLayout.LayoutParams(Ui.dp(a, 48), -2));

            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(a, r.away.isEmpty() ? r.home + " (Gastgeber, kein Gegner angegeben)" : r.home + " – " + r.away, 13, Ui.TEXT, true));
            col.addView(Ui.text(a, r.league, 11, Ui.MUTED, false));
            String refs = Referees.text(r);
            TextView tr = Ui.text(a, refs.isEmpty() ? "noch nicht eingeteilt" : refs, 12,
                    refs.isEmpty() ? Ui.MUTED : Ui.GREEN, false);
            col.addView(tr);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            if (g != null) {
                row.addView(Ui.text(a, "›", 22, Ui.MUTED, false));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        GameSheet.show(a, g);
                    }
                });
            }
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View ln = Ui.colorBlock(a, Ui.LINE);
            list.addView(ln, new LinearLayout.LayoutParams(-1, 1));
        }
        if (shown == 0 && !rows.isEmpty()) {
            TextView t = Ui.text(a, mine ? "Keine Einteilungen für deine Teams (Optionen → Hervorhebung: „"
                    + Prefs.favTerms(a) + "“)" : "Keine Einteilungen", 13, Ui.MUTED, false);
            t.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 16));
            list.addView(t);
        }
    }

    /** „Heute · Fr 13.11.“, „Morgen · Sa 14.11.“, sonst „So 15.11.2026“ */
    private static String dayTitle(String ddmmyyyy) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);
            Date d = in.parse(ddmmyyyy);
            Calendar c = Calendar.getInstance();
            String today = in.format(c.getTime());
            c.add(Calendar.DAY_OF_YEAR, 1);
            String tomorrow = in.format(c.getTime());
            String wd = new SimpleDateFormat("EE dd.MM.", Locale.GERMANY).format(d);
            if (ddmmyyyy.equals(today)) return "Heute · " + wd;
            if (ddmmyyyy.equals(tomorrow)) return "Morgen · " + wd;
            return new SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY).format(d);
        } catch (Exception e) {
            return ddmmyyyy;
        }
    }
}
