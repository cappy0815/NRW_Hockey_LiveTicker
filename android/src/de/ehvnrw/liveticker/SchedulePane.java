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
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * v2.7: Reiter "Spielplan" (Nächste / Ergebnisse) und "Meine" (hervorgehobene Teams) im Hauptfenster.
 * Vorher eigenes Fenster (ScheduleActivity). Daten aus dem Saison-Spielplan (Api.season, 30 Min. Speicher),
 * Antippen = Steckbrief mit Route/Kalender/Tabelle.
 */
class SchedulePane extends LinearLayout {

    static final int TAB_NEXT = 0, TAB_RESULTS = 1, TAB_MINE = 2;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int C_TEXT = Color.rgb(34, 38, 43);
    private static final int C_MUTED = Color.rgb(110, 116, 124);

    private final Activity a;
    private final boolean mine;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Object> items = new ArrayList<>(); // String = Ueberschrift, Game = Spiel
    private List<Game> season = new ArrayList<>();
    private int tab;
    private final Button[] tabs = new Button[2];
    private final TextView status;
    private final Adapter adapter;
    private final Button reload;
    private boolean loaded, loading;

    /** mine = true: Reiter "Meine", sonst "Spielplan" mit Umschalter Nächste/Ergebnisse */
    SchedulePane(Activity act, boolean mine) {
        super(act);
        this.a = act;
        this.mine = mine;
        tab = mine ? TAB_MINE : TAB_NEXT;
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(8), dp(10), 0);

        if (!mine) {
            LinearLayout seg = new LinearLayout(a);
            String[] names = {"Nächste", "Ergebnisse"};
            for (int i = 0; i < 2; i++) {
                final int t = i;
                tabs[i] = new Button(a);
                tabs[i].setText(names[i]);
                tabs[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setTab(t);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                seg.addView(tabs[i], lp);
            }
            addView(seg);
            styleTabs();
        }

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(a);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(C_MUTED);
        status.setPadding(dp(4), dp(8), dp(4), dp(6));
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        reload = new Button(a);
        reload.setText("\u21BB");
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
                if (o instanceof Game) GameSheet.show(a, (Game) o);
            }
        });
        addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    /** Reiter wird sichtbar: beim ersten Mal laden (Saisonplan meist schon im Speicher), sonst neu aufbauen */
    void shown() {
        if (!loaded) load(false);
        else build();
    }

    /** Umschalten Nächste/Ergebnisse (nur Spielplan) */
    void setTab(int t) {
        if (mine) return;
        tab = t;
        styleTabs();
        if (loaded) build();
    }

    private void styleTabs() {
        for (int i = 0; i < 2; i++) {
            if (i == tab) Ui.pill(tabs[i], Ui.GREEN);
            else Ui.outline(tabs[i], Ui.DARK);
            tabs[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tabs[i].setPadding(dp(4), dp(8), dp(4), dp(8));
            tabs[i].setSingleLine(true);
        }
    }

    void load(final boolean force) {
        if (loading) return;
        loading = true;
        reload.setEnabled(false);
        status.setText("Lade Spielplan aller Ligen …");
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

    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Liste fuer den aktuellen Reiter aufbauen */
    private void build() {
        items.clear();
        if (season.isEmpty()) {
            status.setText("Keine Daten – Verbindung prüfen und ↻ tippen.");
            adapter.notifyDataSetChanged();
            return;
        }
        long today = startOfToday();
        List<Game> sel = new ArrayList<>();
        String msg;
        if (tab == TAB_NEXT) {
            for (Game g : season)
                if (g.status != 4 && g.startMs >= today && g.startMs < today + 8 * DAY) sel.add(g);
            msg = sel.size() + " Spiele in den nächsten 7 Tagen";
            if (sel.isEmpty()) { // nichts in Sicht -> die naechsten 30 Spiele, egal wann
                for (Game g : season) {
                    if (g.status == 0 && g.startMs >= today) sel.add(g);
                    if (sel.size() >= 30) break;
                }
                msg = "In den nächsten 7 Tagen keine Spiele – die nächsten " + sel.size() + ":";
            }
            addGrouped(sel, today);
        } else if (tab == TAB_RESULTS) {
            for (Game g : season)
                if (g.status == 4 && g.startMs >= today - 7 * DAY) sel.add(g);
            Collections.reverse(sel); // neueste zuerst
            msg = sel.size() + " Ergebnisse der letzten 7 Tage";
            addGrouped(sel, today);
        } else {
            if (!Prefs.favOn(a) || Prefs.favTerms(a).trim().isEmpty()) {
                msg = "Keine Teams hervorgehoben – in den Optionen unter „Hervorhebung“ eintragen (z. B. Neuss).";
            } else {
                List<Game> next = new ArrayList<>(), last = new ArrayList<>();
                for (Game g : season) {
                    if (!Prefs.isFav(a, g)) continue;
                    if (g.status == 4) last.add(g);
                    else if (g.startMs >= today) next.add(g);
                }
                Collections.reverse(last);
                if (last.size() > 10) last = new ArrayList<>(last.subList(0, 10));
                msg = "„" + Prefs.favTerms(a).trim() + "“: " + next.size() + " kommende Spiele · letzte " + last.size() + " Ergebnisse";
                if (!next.isEmpty()) {
                    items.add("#Kommende Spiele");
                    addGrouped(next, today);
                }
                if (!last.isEmpty()) {
                    items.add("#Letzte Ergebnisse");
                    addGrouped(last, today);
                }
            }
        }
        status.setText(msg);
        adapter.notifyDataSetChanged();
    }

    /** fuegt Spiele mit Tages-Ueberschriften ein ("Heute · Mo, 19.10.") */
    private void addGrouped(List<Game> games, long today) {
        String last = null;
        for (Game g : games) {
            String day = GameSheet.dayLabel(g.startMs);
            long d0 = g.startMs - today;
            if (d0 >= 0 && d0 < DAY) day = "Heute · " + day;
            else if (d0 >= DAY && d0 < 2 * DAY) day = "Morgen · " + day;
            else if (d0 < 0 && d0 >= -DAY) day = "Gestern · " + day;
            if (!day.equals(last)) {
                items.add(day);
                last = day;
            }
            items.add(g);
        }
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ Liste
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
            return items.get(pos) instanceof Game ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int pos) {
            return items.get(pos) instanceof Game;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Object o = items.get(pos);
            if (!(o instanceof Game)) {
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
            Row r = cv instanceof Row ? (Row) cv : new Row();
            r.bind((Game) o);
            return r;
        }
    }

    /** Zeile: links Uhrzeit bzw. Ergebnis, rechts Teams + Liga/Halle */
    private class Row extends LinearLayout {
        final TextView left, homeTxt, awayTxt, sub, place;
        final android.widget.ImageView red;
        final android.widget.ImageView homeImg, awayImg;

        Row() {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(6), dp(9), dp(6), dp(9));
            left = new TextView(getContext());
            left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            left.setTypeface(Typeface.DEFAULT_BOLD);
            left.setGravity(Gravity.CENTER);
            addView(left, new LayoutParams(dp(64), -2));
            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(VERTICAL);
            col.setPadding(dp(8), 0, 0, 0);
            // je Team eine Zeile: Mini-Wappen + Name
            homeImg = new android.widget.ImageView(getContext());
            awayImg = new android.widget.ImageView(getContext());
            homeTxt = new TextView(getContext());
            awayTxt = new TextView(getContext());
            col.addView(teamLine(homeImg, homeTxt));
            col.addView(teamLine(awayImg, awayTxt));
            sub = new TextView(getContext());
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(C_MUTED);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // v2.81: Liga + RED+-Logo in einer Zeile, Halle darunter (verschwand sonst rechts)
            LinearLayout subLine = new LinearLayout(getContext());
            subLine.setGravity(Gravity.CENTER_VERTICAL);
            subLine.setPadding(0, dp(2), 0, 0);
            subLine.addView(sub, new LayoutParams(0, -2, 1f));
            red = GameSheet.streamBadge(a);
            subLine.addView(red, new LayoutParams(dp(74), dp(24)));
            col.addView(subLine);
            place = new TextView(getContext());
            place.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            place.setTextColor(C_MUTED);
            place.setMaxLines(1);
            place.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(place);
            addView(col, new LayoutParams(0, -2, 1f));
        }

        private LinearLayout teamLine(android.widget.ImageView iv, TextView t) {
            LinearLayout l = new LinearLayout(getContext());
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(0, dp(1), 0, dp(1));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            l.addView(iv, new LayoutParams(dp(20), dp(20)));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTextColor(C_TEXT);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(6), 0, 0, 0);
            l.addView(t, new LayoutParams(0, -2, 1f));
            return l;
        }

        void bind(Game g) {
            setBackgroundColor(Prefs.isFav(a, g) ? Color.rgb(255, 246, 214) : Color.TRANSPARENT);
            if (g.status == 4) {
                left.setText(g.homeScore + ":" + g.awayScore + (g.overtime || g.shootout ? "\n" + GameSheet.resultSuffix(g).trim() : ""));
                left.setTextColor(C_TEXT);
            } else if (g.status == 1) {
                left.setText("LIVE\n" + g.homeScore + ":" + g.awayScore);
                left.setTextColor(Ui.RED);
            } else {
                left.setText(g.scheduledTime.isEmpty() ? "–" : g.scheduledTime);
                left.setTextColor(Ui.GREEN);
            }
            homeTxt.setText(g.homeName);
            awayTxt.setText(g.awayName);
            GameSheet.loadImage(homeImg, g.homeLogo);
            GameSheet.loadImage(awayImg, g.awayLogo);
            sub.setText(GameSheet.leagueShort(g) + GameSheet.streamTag(a, g));
            place.setText(g.arenaShort.isEmpty() ? "" : "📍 " + g.arenaShort);
            place.setVisibility(g.arenaShort.isEmpty() ? GONE : VISIBLE);
            GameSheet.bindStreamBadge(a, red, g);
        }
    }
}
