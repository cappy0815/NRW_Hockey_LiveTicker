package de.ehvnrw.liveticker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.7: Reiter "Tabellen" – alle Ligen aus dem Saison-Spielplan, nach Altersklasse gruppiert.
 * Ligen mit hervorgehobenen Teams stehen oben. Antippen = Tabelle (GameSheet.standings).
 * Keine zusätzlichen Abfragen: die Liste entsteht aus dem ohnehin geladenen Spielplan.
 */
class TablesPane extends LinearLayout {

    private static final int C_TEXT = Ui.TEXT;
    private static final int C_MUTED = Ui.MUTED;

    /** eine Liga (Division) */
    private static class Div {
        String id, group, title, sub;
        int games, done, rank;
        boolean fav;
    }

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Object> items = new ArrayList<>(); // String = Ueberschrift ("#" = rot), Div = Liga
    private List<Game> season = new ArrayList<>();
    private final TextView status;
    private final Button reload;
    private final Adapter adapter;
    private boolean loaded, loading;

    TablesPane(Activity act) {
        super(act);
        a = act;
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(8), dp(10), 0);

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(a);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(C_MUTED);
        status.setPadding(dp(4), dp(8), dp(4), dp(6));
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        reload = new Button(a);
        reload.setText("↻");
        Ui.outline(reload, Ui.DARK);
        reload.setMinWidth(0);
        reload.setMinimumWidth(0);
        reload.setPadding(dp(14), dp(4), dp(14), dp(4));
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(true);
            }
        });
        bar.addView(reload, new LinearLayout.LayoutParams(-2, -2));
        addView(bar);

        ListView list = new ListView(a);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof Div) {
                    Div d = (Div) o;
                    GameSheet.standings(a, d.id, d.group + " · " + d.title);
                }
            }
        });
        addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    void shown() {
        if (!loaded) load(false);
        else build();
    }

    void load(final boolean force) {
        if (loading) return;
        loading = true;
        reload.setEnabled(false);
        status.setText("Lade Ligen …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Game> s = Api.season(force);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        reload.setEnabled(true);
                        season = s;
                        loaded = !s.isEmpty();
                        build();
                    }
                });
            }
        }).start();
    }

    /** "NRW - U15 Regionalliga A" -> "U15", "NRW - Senioren" -> "Senioren", "NRW - Freundschaftsspiele" -> dito */
    static String group(Game g) {
        String l = g.league.trim().replaceFirst("^NRW\\s*-\\s*", "").trim();
        Matcher m = Pattern.compile("^(U\\d+)").matcher(l);
        if (m.find()) return m.group(1);
        return l.isEmpty() ? "Sonstige" : l;
    }

    /** Reihenfolge der Altersklassen: Senioren, Frauen, U20 … U7, dann Pokal/Freundschaftsspiele/Sonstige */
    private static int groupRank(String gr) {
        if (gr.equalsIgnoreCase("Senioren")) return 0;
        if (gr.equalsIgnoreCase("Frauen") || gr.equalsIgnoreCase("Damen")) return 1;
        Matcher m = Pattern.compile("^U(\\d+)$").matcher(gr);
        if (m.find()) return 100 - Integer.parseInt(m.group(1)); // U20 vor U15 vor U9
        if (gr.toLowerCase().contains("freundschaft")) return 300;
        return 200;
    }

    /** Spielklasse innerhalb der Altersklasse: Regionalliga, Landesliga, Bezirksliga, Rest */
    private static int levelRank(String s) {
        String t = s.toLowerCase();
        if (t.contains("dfel") || t.contains("regionalliga")) return 0;
        if (t.contains("landesliga")) return 1;
        if (t.contains("bezirksliga")) return 2;
        return 3;
    }

    /** Titel der Liga ohne Altersklasse und Saison: "Regionalliga A · Hauptrunde" */
    private static String title(Game g, String gr) {
        String l = g.league.trim().replaceFirst("^NRW\\s*-\\s*", "").trim();
        if (l.startsWith(gr)) l = l.substring(gr.length()).trim();
        String d = GameSheet.divisionClean(g.division);
        if (l.isEmpty()) return d.isEmpty() ? gr : d;
        return d.isEmpty() ? l : l + " · " + d;
    }

    private void build() {
        items.clear();
        if (season.isEmpty()) {
            status.setText("Keine Daten – Verbindung prüfen und ↻ tippen.");
            adapter.notifyDataSetChanged();
            return;
        }
        boolean favOn = Prefs.favOn(a) && !Prefs.favTerms(a).trim().isEmpty();
        Map<String, Div> map = new LinkedHashMap<>();
        for (Game g : season) {
            if (g.divisionId.isEmpty()) continue;
            Div d = map.get(g.divisionId);
            if (d == null) {
                d = new Div();
                d.id = g.divisionId;
                d.group = group(g);
                d.title = title(g, d.group);
                d.rank = groupRank(d.group) * 10 + levelRank(d.title);
                map.put(d.id, d);
            }
            d.games++;
            if (g.status == 4) d.done++;
            if (favOn && Prefs.isFav(a, g)) d.fav = true;
        }
        List<Div> all = new ArrayList<>(map.values());
        for (Div d : all) d.sub = d.done + " von " + d.games + " Spielen gespielt";
        Collections.sort(all, new Comparator<Div>() {
            @Override
            public int compare(Div x, Div y) {
                if (x.rank != y.rank) return x.rank < y.rank ? -1 : 1;
                return x.title.compareToIgnoreCase(y.title);
            }
        });
        List<Div> favs = new ArrayList<>();
        for (Div d : all) if (d.fav) favs.add(d);
        if (!favs.isEmpty()) {
            items.add("#Ligen meiner Teams");
            items.addAll(favs);
        }
        String last = null;
        for (Div d : all) {
            if (!d.group.equals(last)) {
                items.add(d.group);
                last = d.group;
            }
            items.add(d);
        }
        status.setText(all.size() + " Ligen · Antippen zeigt die Tabelle");
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return Ui.dp(a, v);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int pos) {
            return items.get(pos) instanceof Div ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int pos) {
            return items.get(pos) instanceof Div;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Object o = items.get(pos);
            if (!(o instanceof Div)) {
                String s = (String) o;
                boolean section = s.startsWith("#");
                TextView h = cv instanceof TextView ? (TextView) cv : new TextView(a);
                h.setText(section ? s.substring(1) : s);
                h.setAllCaps(true);
                h.setLetterSpacing(0.08f);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextSize(TypedValue.COMPLEX_UNIT_SP, section ? 13 : 12);
                h.setTextColor(section ? Color.WHITE : C_MUTED);
                h.setBackgroundColor(section ? Ui.RED : Color.rgb(238, 238, 238));
                h.setPadding(dp(8), dp(section ? 8 : 5), dp(8), dp(section ? 8 : 5));
                return h;
            }
            Div d = (Div) o;
            LinearLayout r = cv instanceof LinearLayout ? (LinearLayout) cv : row();
            ((TextView) r.getChildAt(0)).setText((d.fav ? "★ " : "") + d.group + " · " + d.title);
            ((TextView) r.getChildAt(1)).setText(d.sub);
            r.setBackgroundColor(d.fav ? Ui.FAV : Color.TRANSPARENT);
            return r;
        }

        private LinearLayout row() {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(VERTICAL);
            r.setPadding(dp(8), dp(10), dp(8), dp(10));
            TextView t = new TextView(a);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTextColor(C_TEXT);
            TextView s = new TextView(a);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setTextColor(C_MUTED);
            r.addView(t);
            r.addView(s);
            return r;
        }
    }
}
