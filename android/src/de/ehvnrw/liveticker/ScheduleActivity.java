package de.ehvnrw.liveticker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
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
 * Spielplan: "Nächste" (7 Tage), "Ergebnisse" (letzte 7 Tage), "Meine Spiele" (hervorgehobene Teams).
 * Daten aus dem Saison-Spielplan (Api.season), Antippen = Steckbrief mit Route/Kalender/Tabelle.
 */
public class ScheduleActivity extends Activity {

    static final String EX_TAB = "tab";
    static final int TAB_NEXT = 0, TAB_RESULTS = 1, TAB_MINE = 2;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int C_TEXT = Color.rgb(34, 38, 43);
    private static final int C_MUTED = Color.rgb(110, 116, 124);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Object> items = new ArrayList<>(); // String = Ueberschrift, Game = Spiel
    private List<Game> season = new ArrayList<>();
    private int tab = TAB_NEXT;
    private final Button[] tabs = new Button[3];
    private TextView status;
    private Adapter adapter;

    static void open(Context c, int tab) {
        c.startActivity(new Intent(c, ScheduleActivity.class).putExtra(EX_TAB, tab));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tab = getIntent().getIntExtra(EX_TAB, TAB_NEXT);
        setTitle("Spielplan");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), 0);

        LinearLayout seg = new LinearLayout(this);
        String[] names = {"Nächste", "Ergebnisse", "Meine Spiele"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            tabs[i] = new Button(this);
            tabs[i].setText(names[i]);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tab = t;
                    styleTabs();
                    build();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            seg.addView(tabs[i], lp);
        }
        root.addView(seg);
        styleTabs();

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(C_MUTED);
        status.setPadding(dp(4), dp(8), dp(4), dp(6));
        root.addView(status);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof Game) GameSheet.show(ScheduleActivity.this, (Game) o);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        load(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Aktualisieren").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == 1) {
            load(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            if (i == tab) Ui.pill(tabs[i], Ui.GREEN);
            else Ui.outline(tabs[i], Ui.DARK);
            tabs[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tabs[i].setPadding(dp(4), dp(8), dp(4), dp(8));
            tabs[i].setSingleLine(true);
        }
    }

    private void load(final boolean force) {
        status.setText("Lade Spielplan aller Ligen …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Game> s = Api.season(force);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        season = s;
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
            status.setText("Keine Daten – Verbindung prüfen und „Aktualisieren“ tippen.");
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
            if (!Prefs.favOn(this) || Prefs.favTerms(this).trim().isEmpty()) {
                msg = "Keine Teams hervorgehoben – in den Optionen unter „Hervorhebung“ eintragen (z. B. Neuss).";
            } else {
                List<Game> next = new ArrayList<>(), last = new ArrayList<>();
                for (Game g : season) {
                    if (!Prefs.isFav(this, g)) continue;
                    if (g.status == 4) last.add(g);
                    else if (g.startMs >= today) next.add(g);
                }
                Collections.reverse(last);
                if (last.size() > 10) last = new ArrayList<>(last.subList(0, 10));
                msg = "„" + Prefs.favTerms(this).trim() + "“: " + next.size() + " kommende Spiele · letzte " + last.size() + " Ergebnisse";
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
        return Math.round(v * getResources().getDisplayMetrics().density);
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
                TextView h = cv instanceof TextView ? (TextView) cv : new TextView(ScheduleActivity.this);
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
        final TextView left, homeTxt, awayTxt, sub;
        final android.widget.ImageView homeImg, awayImg;

        Row() {
            super(ScheduleActivity.this);
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
            sub.setPadding(0, dp(2), 0, 0);
            col.addView(sub);
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
            setBackgroundColor(Prefs.isFav(ScheduleActivity.this, g) ? Color.rgb(255, 246, 214) : Color.TRANSPARENT);
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
            sub.setText(GameSheet.leagueShort(g) + (g.arenaShort.isEmpty() ? "" : "  ·  📍 " + g.arenaShort));
        }
    }
}
