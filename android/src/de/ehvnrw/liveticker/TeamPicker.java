package de.ehvnrw.liveticker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * v2.98: „Teams auswählen“ – statt des ganzen Vereins nur einzelne Mannschaften hervorheben.
 * Kandidaten = alle Teams des Saisonplans, deren Name einen der Suchbegriffe enthält, nach Altersklasse sortiert.
 * Gespeichert wird „Altersklasse|Teamname“ (nicht die Liga-ID): Der Verband legt für Ergänzungs- und
 * Platzierungsrunden neue Ligen an (z. B. U13 Landesliga 21668 Hauptrunde → 21669 Ergänzungsrunde),
 * die Auswahl bleibt so erhalten.
 */
final class TeamPicker {
    private TeamPicker() {
    }

    /** terms = aktueller Inhalt des Suchfelds in den Optionen; done läuft nach „Übernehmen“ */
    static void show(final Activity a, final String terms, final Runnable done) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 18), Ui.dp(a, 20), Ui.dp(a, 6));
        box.addView(Ui.text(a, "Teams auswählen", 18, Ui.TEXT, true));
        box.addView(Ui.text(a, "Nur angehakte Teams werden hervorgehoben und erscheinen unter „Meine“. "
                + "Nichts angehakt = wie bisher alle Teams mit dem Suchbegriff.", 12, Ui.MUTED, false));
        Ui.stripe(a, box, 10);
        final TextView status = Ui.text(a, "Lade Spielplan …", 13, Ui.MUTED, false);
        box.addView(status);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final List<CheckBox> boxes = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setView(Ui.withScrollHint(sv))
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton("Keins", null)
                .setPositiveButton("Übernehmen", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Set<String> sel = new TreeSet<>(Prefs.favTeams(a));
                        for (int i = 0; i < boxes.size(); i++) {
                            if (boxes.get(i).isChecked()) sel.add(keys.get(i));
                            else sel.remove(keys.get(i));
                        }
                        Prefs.setFavTeams(a, sel);
                        if (done != null) done.run();
                    }
                })
                .show();
        Ui.dialog(dlg);
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                for (CheckBox c : boxes) c.setChecked(false);
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Game> season = Api.season(false);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        fill(a, box, status, season, terms, boxes, keys);
                    }
                });
            }
        }).start();
    }

    private static final class Cand {
        String group, team;
        int rank;
        final Set<String> leagues = new TreeSet<>();
    }

    private static void fill(Activity a, LinearLayout box, TextView status, List<Game> season, String terms,
                             List<CheckBox> boxes, List<String> keys) {
        if (season.isEmpty()) {
            status.setText("Spielplan nicht erreichbar – später erneut versuchen.");
            status.setTextColor(Ui.RED);
            return;
        }
        List<String> t = new ArrayList<>();
        for (String s : terms.split(",")) {
            String x = s.trim().toLowerCase(Locale.GERMANY);
            if (!x.isEmpty()) t.add(x);
        }
        if (t.isEmpty()) {
            status.setText("Erst in den Optionen einen Suchbegriff eintragen (z. B. Neuss), dann hier die Teams anhaken.");
            return;
        }
        Map<String, Cand> map = new LinkedHashMap<>();
        for (Game g : season) {
            String gr = TablesPane.group(g);
            for (String team : new String[]{g.homeName, g.awayName}) {
                if (team == null || team.isEmpty()) continue;
                String low = team.toLowerCase(Locale.GERMANY);
                boolean hit = false;
                for (String x : t) if (low.contains(x)) { hit = true; break; }
                if (!hit) continue;
                String key = Prefs.teamKey(gr, team);
                Cand c = map.get(key);
                if (c == null) {
                    c = new Cand();
                    c.group = gr;
                    c.team = team;
                    c.rank = TablesPane.groupRank(gr);
                    map.put(key, c);
                }
                c.leagues.add(TablesPane.title(g, gr));
            }
        }
        List<Cand> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Cand>() {
            @Override
            public int compare(Cand x, Cand y) {
                if (x.rank != y.rank) return x.rank < y.rank ? -1 : 1;
                if (!x.group.equals(y.group)) return x.group.compareTo(y.group);
                return x.team.compareToIgnoreCase(y.team);
            }
        });
        if (list.isEmpty()) {
            status.setText("Kein Team mit „" + terms + "“ im Spielplan gefunden.");
            return;
        }
        status.setText(list.size() + " Teams mit „" + terms + "“ im Spielplan:");
        Set<String> sel = Prefs.favTeams(a);
        String last = null;
        for (Cand c : list) {
            if (!c.group.equals(last)) {
                last = c.group;
                Ui.section(a, box, c.group);
            }
            CheckBox cb = new CheckBox(a);
            cb.setText(c.team + "\n" + join(c.leagues));
            cb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            cb.setTextColor(Ui.TEXT);
            cb.setChecked(sel.contains(Prefs.teamKey(c.group, c.team)));
            cb.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), 0, Ui.dp(a, 4));
            box.addView(cb);
            boxes.add(cb);
            keys.add(Prefs.teamKey(c.group, c.team));
        }
    }

    private static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) {
            if (b.length() > 0) b.append(" · ");
            b.append(x);
        }
        return b.toString();
    }
}
