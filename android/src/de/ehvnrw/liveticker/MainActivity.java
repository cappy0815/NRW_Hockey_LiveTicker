package de.ehvnrw.liveticker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.graphics.drawable.GradientDrawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(4);
    private final List<Game> games = new ArrayList<>();

    private TextView status;
    private TextView emptyTxt;
    private LinearLayout permBox;
    private Button refreshBtn;
    private GameAdapter adapter;
    private boolean loading;
    // v2.7: Reiter unten (Live · Spielplan · Meine · Tabellen), wischen zum Wechseln
    static final int T_LIVE = 0, T_PLAN = 1, T_MINE = 2, T_TABLES = 3;
    private final View[] panes = new View[4];
    private final TextView[] tabBtns = new TextView[4];
    private int curTab = T_LIVE;
    private SchedulePane planPane, minePane;
    private TablesPane tablesPane;
    private android.view.GestureDetector swipe;
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refresh(false);
        }
    };

    /** naechste automatische Aktualisierung planen (nur solange die App sichtbar ist) */
    private void scheduleAuto() {
        ui.removeCallbacks(autoRefresh);
        int sec = Prefs.listSec(this);
        if (sec > 0) ui.postDelayed(autoRefresh, sec * 1000L);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("EHV Live v" + appVersion());

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout root = new LinearLayout(this); // Reiter "Live"
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), 0);

        // Hinweis, falls die Overlay-Berechtigung fehlt
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        permBox.setBackgroundColor(Color.rgb(255, 243, 205));
        TextView pt = new TextView(this);
        pt.setText("Für das schwebende Live-Display braucht die App die Berechtigung „Über anderen Apps einblenden“.");
        pt.setTextColor(Color.rgb(90, 70, 10));
        permBox.addView(pt);
        Button pb = new Button(this);
        pb.setText("Berechtigung erteilen");
        Ui.pill(pb, Ui.RED);
        pb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-2, -2);
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pbLp.topMargin = dp(8);
        permBox.addView(pb, pbLp);
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(-1, -2);
        permLp.setMargins(dp(12), dp(8), dp(12), 0);
        outer.addView(permBox, permLp); // gilt für alle Reiter

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(6));
        refreshBtn = new Button(this);
        refreshBtn.setText("\u21BB  Aktualisieren");
        Ui.pill(refreshBtn, Ui.GREEN);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(false); // v2.5: Live-Stände immer frisch, Saisonplan aus dem 30-Min.-Speicher
            }
        });
        bar.addView(refreshBtn);
        status = new TextView(this);
        status.setPadding(dp(10), 0, 0, 0);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setMaxLines(2);
        status.setTextColor(Color.DKGRAY);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);

        ListView list = new ListView(this);
        adapter = new GameAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Game g = games.get(pos);
                if (g.type != 2) startTicker(g);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                Game g = games.get(pos);
                if (g.type == 2) return false;
                LineupSheet.show(MainActivity.this, g);
                return true;
            }
        });
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(0, dp(40), 0, 0);
        empty.setVisibility(View.GONE);
        emptyTxt = new TextView(this);
        emptyTxt.setText("Heute läuft kein Spiel mehr.");
        emptyTxt.setGravity(Gravity.CENTER);
        empty.addView(emptyTxt);
        Button nextBtn = new Button(this);
        nextBtn.setText("📅  Nächste Spiele anzeigen");
        Ui.pill(nextBtn, Ui.GREEN);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                planPane.setTab(SchedulePane.TAB_NEXT);
                selectTab(T_PLAN);
            }
        });
        LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(-2, -2);
        nbLp.topMargin = dp(16);
        empty.addView(nextBtn, nbLp);
        ((ViewGroup) root).addView(empty, new LinearLayout.LayoutParams(-1, -2));
        list.setEmptyView(empty);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("Quelle: ehv-nrw.de / hockeydata.net · Schiris: esrw.de");
        foot.setMaxLines(2);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        foot.setTextColor(Color.GRAY);
        foot.setPadding(0, dp(6), 0, dp(10));
        root.addView(foot);

        FrameLayout frame = new FrameLayout(this);
        planPane = new SchedulePane(this, false);
        minePane = new SchedulePane(this, true);
        tablesPane = new TablesPane(this);
        panes[T_LIVE] = root;
        panes[T_PLAN] = planPane;
        panes[T_MINE] = minePane;
        panes[T_TABLES] = tablesPane;
        for (View v : panes) frame.addView(v, new FrameLayout.LayoutParams(-1, -1));
        outer.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        outer.addView(tabBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(outer);
        selectTab(T_LIVE);
        initSwipe();
        showBarLogo();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        refresh(false); // v2.5: Saisonplan nur laden, wenn älter als 30 Min. (nach Kaltstart: einmal)
        // v2.6: Update-Prüfung höchstens einmal pro Tag (abschaltbar), nichts wird automatisch installiert
        if (Update.due(this)) Update.check(this, new Update.Done() {
            @Override
            public void done(boolean ok) {
                invalidateOptionsMenu();
            }
        });
    }

    // ------------------------------------------------------------------ Reiter (v2.7)
    private static final String[] TAB_ICONS = {"\uD83C\uDFD2", "\uD83D\uDCC5", "\u2B50", "\uD83D\uDCCA"};
    private static final String[] TAB_NAMES = {"Live", "Spielplan", "Meine", "Tabellen"};

    private LinearLayout tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(220, 223, 227));
        bar.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(Color.rgb(248, 249, 250));
        for (int i = 0; i < 4; i++) {
            final int t = i;
            TextView b = new TextView(this);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setPadding(0, dp(6), 0, dp(8));
            b.setSingleLine(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(t);
                }
            });
            tabBtns[i] = b;
            row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        bar.addView(row);
        return bar;
    }

    private void selectTab(int t) {
        curTab = t;
        for (int i = 0; i < 4; i++) {
            panes[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
            boolean on = i == t;
            android.text.SpannableString sp = new android.text.SpannableString(TAB_ICONS[i] + "\n" + TAB_NAMES[i]);
            sp.setSpan(new android.text.style.RelativeSizeSpan(1.5f), 0, TAB_ICONS[i].length(), 0);
            tabBtns[i].setText(sp);
            tabBtns[i].setTextColor(on ? C_GREEN : C_MUTED);
            tabBtns[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabBtns[i].setAlpha(on ? 1f : 0.75f);
        }
        if (t == T_PLAN) planPane.shown();
        else if (t == T_MINE) minePane.shown();
        else if (t == T_TABLES) tablesPane.shown();
    }

    /** Wischen nach links/rechts wechselt den Reiter (Listen scrollen weiter senkrecht) */
    private void initSwipe() {
        swipe = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(90) || Math.abs(dx) < Math.abs(dy) * 2 || Math.abs(vx) < 500) return false;
                int t = curTab + (dx < 0 ? 1 : -1);
                if (t < 0 || t > 3) return false;
                selectTab(t);
                return true;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (swipe != null) swipe.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (curTab != T_LIVE) selectTab(T_LIVE); // Zurück führt erst zur Live-Liste
        else super.onBackPressed();
    }

    /** Tipp auf „⬆ Update“ in der Titelleiste */
    private void showUpdate() {
        String msg = "Version " + Update.latest(this) + " ist verfügbar (installiert: " + Update.installed(this) + ").\n\n"
                + "„Herunterladen“ öffnet die APK im Browser. Danach die Datei antippen und installieren – "
                + "Einstellungen und Vormerkungen bleiben erhalten.";
        Ui.dialog(new android.app.AlertDialog.Builder(this)
                .setTitle("\u2B06 Update")
                .setMessage(msg)
                .setNegativeButton("Später", null)
                .setNeutralButton("Was ist neu?", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Update.openPage(MainActivity.this);
                    }
                })
                .setPositiveButton("Herunterladen", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Update.openDownload(MainActivity.this);
                    }
                })
                .show());
    }

    /** Abschnitt „Updates“ in den Optionen: Status, täglich prüfen, Jetzt prüfen / Herunterladen */
    private void updateSection(LinearLayout box, final Switch[] autoOut) {
        section(box, "Updates");
        final TextView st = text("", 13, C_TEXT, false);
        box.addView(st);
        final Switch swAuto = toggle("Täglich automatisch prüfen (es wird nichts automatisch installiert)", Update.auto(this));
        box.addView(swAuto);
        autoOut[0] = swAuto;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        final android.widget.Button chk = new android.widget.Button(this);
        chk.setText("Jetzt prüfen");
        Ui.pill(chk, Ui.DARK);
        final android.widget.Button dl = new android.widget.Button(this);
        dl.setText("\u2B06 Herunterladen");
        Ui.pill(dl, Ui.GREEN);
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                boolean avail = Update.available(MainActivity.this);
                String l = Update.latest(MainActivity.this);
                st.setText((Update.isBusy() ? "Prüfe …" : avail ? "Version " + l + " ist verfügbar"
                        : l.isEmpty() ? "Noch nicht geprüft" : "Aktuell (neueste Version " + l + ")")
                        + "\nInstalliert: " + Update.installed(MainActivity.this) + " · zuletzt geprüft: " + Update.lastText(MainActivity.this));
                st.setTextColor(avail ? C_GREEN : C_MUTED);
                dl.setVisibility(avail ? View.VISIBLE : View.GONE);
                chk.setEnabled(!Update.isBusy());
                invalidateOptionsMenu();
            }
        };
        chk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Update.check(MainActivity.this, new Update.Done() {
                    @Override
                    public void done(boolean ok) {
                        show.run();
                        if (!ok) {
                            st.setText("Update-Server nicht erreichbar – Internet prüfen");
                            st.setTextColor(C_ACCENT);
                        }
                    }
                });
                show.run();
            }
        });
        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Update.openDownload(MainActivity.this);
            }
        });
        row.addView(chk);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        row.addView(dl, lp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        box.addView(row, rlp);
        show.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        permBox.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
        scheduleAuto();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(autoRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }

    /** Versionsname aus dem Paket (wird im Build-Skript gesetzt). */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ Titelleiste
    /** EHV-Logo klein links neben „EHV Live“ in der Titelleiste */
    private void showBarLogo() {
        try {
            android.app.ActionBar ab = getActionBar();
            if (ab == null) return;
            int s = dp(30);
            Bitmap src = android.graphics.BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(getResources(),
                    Bitmap.createScaledBitmap(src, s, s, true));
            ab.setLogo(new android.graphics.drawable.InsetDrawable(bd, 0, 0, dp(10), 0));
            ab.setDisplayUseLogoEnabled(true);
            ab.setDisplayShowHomeEnabled(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // v2.6: ⬆ nur, wenn eine neuere Version gefunden wurde
        if (Update.available(this)) menu.add(0, 5, 0, "\u2B06 Update").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        MenuItem opt = menu.add(0, 3, 0, "Optionen");
        opt.setIcon(R.drawable.ic_gear);
        opt.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // nur Symbole, damit der Titel "EHV Live" Platz hat (lange druecken zeigt den Namen)
        menu.add(0, 1, 1, "Hilfe").setIcon(R.drawable.ic_help).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showHelp();
            return true;
        }
        if (item.getItemId() == 3) {
            showOptions();
            return true;
        }
        if (item.getItemId() == 5) {
            showUpdate();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ Hilfe-Fenster
    private static final int C_TEXT = Color.rgb(34, 38, 43);
    private static final int C_MUTED = Color.rgb(110, 116, 124);
    private static final int C_ACCENT = Color.rgb(200, 16, 46);   // Rot aus dem EHV-Logo
    private static final int C_GREEN = Color.rgb(0, 140, 69);     // Gruen aus dem EHV-Logo

    private void showHelp() {
        final ScrollView sv = new ScrollView(this);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(48));
        sv.setScrollbarFadingEnabled(false); // Scrollbalken immer sichtbar
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(6));
        sv.addView(box);

        // Kopf: Logo, Titel, Versions-Chip
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("EHV Live", 18, C_TEXT, true));
        titles.addView(text("Live-Ticker für den EHV NRW", 12, C_MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chip = text("v" + appVersion(), 11, Color.WHITE, true);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(Color.rgb(34, 38, 43));
        chipBg.setCornerRadius(dp(10));
        chip.setBackground(chipBg);
        head.addView(chip);
        box.addView(head);

        // Streifen in den Farben des Logos
        LinearLayout stripe = new LinearLayout(this);
        stripe.addView(colorBlock(C_GREEN), new LinearLayout.LayoutParams(0, -1, 1f));
        stripe.addView(colorBlock(Color.rgb(225, 228, 232)), new LinearLayout.LayoutParams(0, -1, 1f));
        stripe.addView(colorBlock(C_ACCENT), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, dp(3));
        stLp.topMargin = dp(14);
        stLp.bottomMargin = dp(4);
        box.addView(stripe, stLp);

        section(box, "Spielliste");
        item(box, "\uD83D\uDD04", "„Aktualisieren“ lädt alle laufenden Spiele neu");
        item(box, "\uD83C\uDF31", "Sparsam: abgefragt werden nur Ligen, die heute spielen (Statuszeile z. B. „5/28 Ligen“); den Spielplan lädt die App höchstens alle 30 Min.");
        item(box, "\uD83D\uDC46", "Spiel antippen startet das Floating-Display – nochmal antippen entfernt es (📌 = angepinnt)");
        item(box, "\uD83D\uDFE8", "Gelb hinterlegt: hervorgehobene Spiele (Standard: Neuss)");
        item(box, "\uD83D\uDD52", "„Heute noch“: anstehende Spiele, blass dargestellt");
        item(box, "\u23F0", "Antippen merkt das Spiel vor – das Floating erscheint bei Spielbeginn (nochmal antippen = aufheben). In den Optionen alternativ: sofort, blass bis zum Bully");
        item(box, "\uD83D\uDCCB", "Lange drücken: Aufstellung beider Teams (sobald eingetragen) – auch per Knopf im Detailfenster");
        item(box, "\u23F2", "Aktualisiert sich automatisch, solange die App offen ist");

        section(box, "Reiter unten");
        item(box, "\uD83C\uDFD2", "„Live“: die heutige Spielliste");
        item(box, "\uD83D\uDCC5", "„Spielplan“: „Nächste“ = Spiele der nächsten 7 Tage · „Ergebnisse“ = letzte 7 Tage");
        item(box, "\u2B50", "„Meine“: kommende Spiele und letzte Ergebnisse der hervorgehobenen Teams (Optionen → Hervorhebung)");
        item(box, "\uD83D\uDCCA", "„Tabellen“: alle Ligen nach Altersklasse, Ligen der eigenen Teams oben – antippen zeigt die Tabelle");
        item(box, "\u2194", "Nach links/rechts wischen wechselt den Reiter, „Zurück“ führt zur Live-Liste, \u21BB lädt den Spielplan neu");
        item(box, "\uD83D\uDC46", "Spiel antippen: Steckbrief mit Halle, Drittelergebnissen und Torschützen");
        item(box, "\uD83D\uDCCD", "Halle antippen öffnet die Route in der Karten-App, „Kalender“ trägt das Spiel ein, „Tabelle“ zeigt die Liga");
        item(box, "\u2753", "Läuft heute nichts mehr, erscheint in der Liste der Knopf „Nächste Spiele anzeigen“");

        section(box, "Floating-Display");
        item(box, "\u2195", "Ziehen verschiebt – die Position wird gespeichert");
        item(box, "\uD83D\uDC46", "Antippen: Stufe 1 (klein) → 2 (Infozeile) → 3 (Ereignisliste, volle Breite) → 1");
        item(box, "\u23F3", "Stufe 2 und 3 fallen von selbst auf Stufe 1 zurück (Standard 3 s bzw. 10 s – einstellbar)");
        item(box, "\uD83D\uDCDA", "Mehrere Floatings stehen untereinander: wird eins größer, rutschen die anderen mit. Das oberste zieht den Stapel mit, jedes andere lässt sich herausziehen");
        item(box, "\uD83D\uDCCB", "Lange drücken: Stufe 4 – Detailfenster mit allen Ereignissen + Display-Wecker für dieses Spiel; schließt nur über „Schließen“");
        item(box, "\u2715", "Nach unten auf das ✕ ziehen: schließen");
        item(box, "\uD83D\uDEA8", "Bei einem Tor: Stand kurz gelb und „TOR!“");
        item(box, "\u23F1", "Aktualisierung alle 15 Sekunden");

        section(box, "Kürzel in der Ereignisliste");
        item(box, "2'", "Strafe mit Minuten; Name in Klammern = abgesessen von");
        item(box, "PP", "Tor in Überzahl (Powerplay)");
        item(box, "SH", "Tor in Unterzahl");
        item(box, "EN", "Tor ins leere Tor");
        item(box, "PS", "Penaltyschuss");
        item(box, "\uD83E\uDDD1\u200D\u2696\uFE0F", "Schiri: HSR = Hauptschiedsrichter, LSR = Linienrichter, SR = beide Schiris im Zwei-Mann-System – "
                + "aus den Einteilungen auf esrw.de, sofern dort eingetragen (oben in Stufe 3 und im Detailfenster)");

        section(box, "Beenden");
        item(box, "\uD83D\uDED1", "„App beenden“ unten in den Optionen (\u2699) schließt alle Displays und die App – "
                + "oder „Beenden“ in der Benachrichtigung");
        item(box, "\u23F0", "Gibt es Vormerkungen, fragt die App: „Alles beenden“ oder „Nur App schließen“ (Vormerkungen bleiben)");

        section(box, "Berechtigungen");
        item(box, "\uD83E\uDE9F", "„Über anderen Apps einblenden“ – nötig für das Floating-Display");
        item(box, "\uD83D\uDD14", "Benachrichtigungen – optional, zeigt den Stand in der Statusleiste");

        section(box, "Optionen (Zahnrad oben)");
        item(box, "\u23F1", "Intervalle für Spielliste und Floating-Display");
        item(box, "\uD83D\uDCF3", "Vibration bei Tor");
        item(box, "\uD83D\uDD0E", "Hervorhebung: Suchbegriffe für Teamnamen, mehrere mit Komma");

        section(box, "Display-Wecker");
        item(box, "\uD83D\uDCF1", "In den Optionen einschalten: Bei Tor, Strafe, Bully, Drittel- und Spielende geht das "
                + "Display an und zeigt die Meldung 8 Sekunden über dem Sperrbildschirm – ideal im Auto-Halter");
        item(box, "\uD83D\uDC46", "Tippen schließt die Meldung sofort. Gilt für Spiele mit Floating oder Vormerkung");
        item(box, "\uD83D\uDD14", "Optional dezenter Ton – läuft wie eine Navi-Ansage auch über Bluetooth im Auto");
        item(box, "\uD83D\uDD14", "Je Spiel an/aus (Floating lange drücken) – auch wenn der Standard aus ist; 🔔 in der Infozeile = aktiv");

        section(box, "Updates");
        item(box, "\u2B06", "Die App prüft höchstens einmal am Tag, ob es eine neue Version gibt (in den Optionen abschaltbar). "
                + "Dann erscheint oben „⬆ Update“ – Herunterladen öffnet die APK im Browser, installiert wird nur von dir");

        section(box, "Datenquelle");
        TextView src = text("ehv-nrw.de / hockeydata.net, Schiedsrichter-Einteilungen von esrw.de · "
                + "inoffizielle App ohne Verbindung zum EHV NRW oder den Schiedsrichtern im Westen", 12, C_MUTED, false);
        src.setPadding(0, dp(2), 0, 0);
        box.addView(src);

        android.widget.Button btLog = new android.widget.Button(this);
        btLog.setText("\uD83D\uDCDC  Changelog – was ist neu?");
        Ui.outline(btLog, C_GREEN);
        btLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangelog();
            }
        });
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-2, -2);
        logLp.gravity = Gravity.CENTER_HORIZONTAL;
        logLp.topMargin = dp(16);
        box.addView(btLog, logLp);

        // Credits
        View line = colorBlock(Color.rgb(225, 228, 232));
        LinearLayout.LayoutParams lnLp = new LinearLayout.LayoutParams(-1, dp(1));
        lnLp.topMargin = dp(16);
        lnLp.bottomMargin = dp(10);
        box.addView(line, lnLp);
        TextView credits = text("Gebaut von Justus Jonas & Michael Nowak\n– mit Claude an der Bande \uD83C\uDFD2", 12, C_MUTED, false);
        credits.setGravity(Gravity.CENTER);
        credits.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        box.addView(credits, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout frame = withScrollHint(sv);

        Ui.dialog(new android.app.AlertDialog.Builder(this)
                .setView(frame)
                .setPositiveButton("Alles klar", null)
                .show());
    }

    /** ScrollView mit "▼ weiter scrollen"-Hinweis unten, solange es weiter nach unten geht */
    private FrameLayout withScrollHint(final ScrollView sv) {
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(48));
        sv.setScrollbarFadingEnabled(false);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -2));
        final TextView more = text("\u25BC  weiter scrollen", 11, Color.WHITE, true);
        more.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable moreBg = new GradientDrawable();
        moreBg.setColor(Color.argb(215, 34, 38, 43));
        moreBg.setCornerRadius(dp(12));
        more.setBackground(moreBg);
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        moreLp.bottomMargin = dp(6);
        frame.addView(more, moreLp);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sv.smoothScrollBy(0, sv.getHeight() * 2 / 3);
            }
        });
        final Runnable updateMore = new Runnable() {
            @Override
            public void run() {
                more.setVisibility(sv.canScrollVertically(1) ? View.VISIBLE : View.GONE);
            }
        };
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                updateMore.run();
            }
        });
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                updateMore.run();
            }
        });

        return frame;
    }

    // ------------------------------------------------------------------ Changelog
    private static final String[][] CHANGELOG = {
            {"2.7", "Neue Reiter unten: Live · Spielplan · Meine · Tabellen – wischen zum Wechseln\n"
                    + "„Tabellen“: alle Ligen nach Altersklasse, Ligen der eigenen Teams oben\n"
                    + "Kopfleiste nur noch Optionen und Hilfe, „App beenden“ jetzt in den Optionen"},
            {"2.6", "Schiedsrichter im Ticker: aus den Einteilungen von esrw.de (Stufe 3 und Detailfenster)\n"
                    + "Update-Prüfung: einmal am Tag (abschaltbar), „⬆ Update“ oben, Abschnitt „Updates“ in den Optionen"},
            {"2.51", "Neues App-Icon: Puck auf Eis mit EHV-Farbstreifen (wie die Windows-Version)"},
            {"2.5", "Weniger Datenverbrauch: nur Ligen mit heutigen Spielen werden abgefragt, Spielplan höchstens alle 30 Min.\n"
                    + "Spielliste standardmäßig jede Minute (30 Sekunden entfallen)\n"
                    + "Detailfenster: Knopf „Aufstellung“"},
            {"2.4", "Benachrichtigung: mehrere Spiele stehen sauber untereinander (aufklappen)\n"
                    + "Ereignisliste: Torvorlagen in einer zweiten Zeile („Vorl. …“)\n"
                    + "Ereignisliste: Tore und Strafen stehen spaltengenau untereinander"},
            {"2.3", "Mehrere Floatings ordnen sich untereinander an und rutschen bei Stufe 2 mit\n"
                    + "Floating Stufe 2 zeigt die Altersklasse/Liga, z. B. „U15 RL A · 2. Drittel“\n"
                    + "Tabelle mit Teamkürzeln (keine abgeschnittenen Namen mehr)\n"
                    + "Steckbrief: „Endstand“ ohne die Anstoßzeit\n"
                    + "Server-Ausfall: schnelle, deutliche Meldung, alter Stand bleibt sichtbar"},
            {"2.23", "Vereinswappen im Spielplan, im Steckbrief und in der Tabelle"},
            {"2.22", "Hallen-Adresse: Unterstreichung korrigiert (die 2. Zeile war durchgestrichen)"},
            {"2.21", "Titelleiste nur noch mit Symbolen (Spielplan, Optionen, Hilfe, Beenden) – der Titel ist wieder frei\n"
                    + "Steckbrief: „Route“-Knopf entfällt, die Halle selbst antippen\nAlle Knöpfe mittig ausgerichtet"},
            {"2.2", "Spielplan (Kalender-Symbol oben): Nächste Spiele, Ergebnisse der letzten 7 Tage, Meine Spiele\n"
                    + "Steckbrief je Spiel mit Halle, Drittelergebnissen und Torschützen\n"
                    + "Route zur Halle, Spiel in den Kalender eintragen, Tabelle der Liga\n"
                    + "Knopf „Nächste Spiele anzeigen“, wenn heute nichts mehr läuft\n"
                    + "Halle bei anstehenden Spielen in der Liste; Tabelle auch im Detailfenster"},
            {"2.16", "Angepinntes Spiel in der Liste nochmal antippen entfernt das Floating; 📌 markiert angepinnte Spiele\n"
                    + "Langes Drücken aufs Floating: kein ✕ mehr nach dem Öffnen des Detailfensters, weniger empfindlich\n"
                    + "Kleines EHV-Logo in der Titelleiste"},
            {"2.15", "Floating-Stufen: Stufe 2 und 3 fallen nach einstellbarer Zeit auf Stufe 1 zurück, "
                    + "Detailfenster (Stufe 4) schließt nur über den Knopf und setzt auf Stufe 1 zurück\n"
                    + "Vormerken minimiert die App nicht mehr – nur das Anpinnen eines laufenden Spiels\n"
                    + "Changelog wandert in die Hilfe\nNeue runde Knöpfe in EHV-Farben"},
            {"2.14", "Optionen neu sortiert (eigener Bereich Floating-Display) + „weiter scrollen“\n"
                    + "Ereignisliste klappt nach einstellbarer Zeit wieder zu (Standard 3 s)\n"
                    + "Detailfenster schließt nach einstellbarer Zeit (Standard 10 s)\n"
                    + "App legt sich nach dem Anpinnen/Vormerken in den Hintergrund (abschaltbar)\n"
                    + "Display-Wecker je Spiel auch einschaltbar, wenn er generell aus ist; 🔔 im Floating\n"
                    + "Changelog; Fußzeile gekürzt"},
            {"2.13", "Ereignisliste in voller Breite, Schrift etwas größer\n"
                    + "Lange drücken aufs Floating: Detailfenster mit allen Ereignissen und Display-Wecker je Spiel\n"
                    + "Dezenter Ton zur Display-Meldung (auch über Bluetooth)"},
            {"2.12", "Display-Wecker: Meldung groß über dem Sperrbildschirm, mit Testknopf\nAndroid Auto entfernt"},
            {"2.11", "Android Auto: Testmeldung zur Fehlersuche"},
            {"2.1", "Android Auto: Spielereignisse als Nachricht (Experiment)"},
            {"2.01", "Zahnrad-Symbol, Schalter statt Auswahlliste beim Vormerken\n"
                    + "„Beenden“ fragt nach, wenn noch Spiele vorgemerkt sind"},
            {"2.0", "„Heute noch“: anstehende Spiele blass in der Liste, Vormerken bis zum Bully\n"
                    + "Vibration bei Spielbeginn, Aufstellung per langem Druck, Hervorhebung (z. B. Neuss), Optionen"},
            {"1.x", "Liste der laufenden EHV-NRW-Spiele, Floating-Display mit Logos, Stand und Infozeile\n"
                    + "Verschieben, Schließen über das ✕, Ereignisliste per Antippen, Hilfe, Beenden"},
    };

    private void showChangelog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(6));
        box.addView(text("Changelog", 18, C_TEXT, true));
        for (String[] e : CHANGELOG) {
            section(box, "Version " + e[0]);
            for (String l : e[1].split("\n")) item(box, "•", l);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        Ui.dialog(new android.app.AlertDialog.Builder(this).setView(withScrollHint(sv)).setPositiveButton("Schließen", null).show());
    }

    // ------------------------------------------------------------------ Optionen
    private void showOptions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(6));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        box.addView(text("Optionen", 18, C_TEXT, true));
        LinearLayout stripe = new LinearLayout(this);
        stripe.addView(colorBlock(C_GREEN), new LinearLayout.LayoutParams(0, -1, 1f));
        stripe.addView(colorBlock(Color.rgb(225, 228, 232)), new LinearLayout.LayoutParams(0, -1, 1f));
        stripe.addView(colorBlock(C_ACCENT), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, dp(3));
        stLp.topMargin = dp(10);
        stLp.bottomMargin = dp(4);
        box.addView(stripe, stLp);

        section(box, "Spielliste");
        final Spinner spList = spinner(Prefs.LIST_NAMES, Prefs.indexOf(Prefs.LIST_STEPS, Prefs.listSec(this)));
        box.addView(labeled("Aktualisierung", spList));
        final Switch swMin = toggle("App nach dem Anpinnen eines laufenden Spiels in den Hintergrund", Prefs.minimizeOnPin(this));
        box.addView(swMin);

        section(box, "Floating-Display");
        final Spinner spTick = spinner(Prefs.TICK_NAMES, Prefs.indexOf(Prefs.TICK_STEPS, Prefs.tickSec(this)));
        box.addView(labeled("Aktualisierung", spTick));
        final Spinner spInfo = spinner(Prefs.INFO_NAMES, Prefs.indexOf(Prefs.INFO_STEPS, Prefs.infoSec(this)));
        box.addView(labeled("Stufe 2 (Infozeile) zurück nach", spInfo));
        final Spinner spEvents = spinner(Prefs.EVENTS_NAMES, Prefs.indexOf(Prefs.EVENTS_STEPS, Prefs.eventsSec(this)));
        box.addView(labeled("Stufe 3 (Ereignisliste) zurück nach", spEvents));
        final Switch swVib = toggle("Vibration bei Tor", Prefs.vibrate(this));
        box.addView(swVib);

        section(box, "Anstehende Spiele (antippen)");
        final Switch swRemind = toggle("Vormerken", Prefs.remindMode(this));
        box.addView(swRemind);
        final TextView remindHint = text("", 12, C_MUTED, false);
        remindHint.setPadding(0, 0, 0, dp(6));
        box.addView(remindHint);
        final Runnable updHint = new Runnable() {
            @Override
            public void run() {
                remindHint.setText(swRemind.isChecked()
                        ? "An: Das Floating erscheint automatisch bei Spielbeginn."
                        : "Aus: Das Floating erscheint sofort – blass bis zum Bully.");
            }
        };
        updHint.run();
        swRemind.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                updHint.run();
            }
        });
        final Switch swStart = toggle("Bei Spielbeginn vibrieren", Prefs.startVibrate(this));
        box.addView(swStart);

        section(box, "Display-Wecker");
        final Switch swWake = toggle("Standard: für alle Spiele an", Prefs.wakeOn(this));
        box.addView(swWake);
        TextView wakeHint = text("Tor, Strafe, Bully, Drittel- und Spielende erscheinen 8 Sekunden groß über dem "
                + "Sperrbildschirm – nur wenn das Display aus oder gesperrt ist. Das Display geht danach "
                + "mit der normalen Bildschirm-Zeitsperre wieder aus. Einzelne Spiele: Floating lange drücken – "
                + "geht auch, wenn der Standard aus ist.", 12, C_MUTED, false);
        box.addView(wakeHint);
        final Switch swSound = toggle("Dezenter Ton dazu (auch über Bluetooth im Auto)", Prefs.wakeSound(this));
        box.addView(swSound);
        TextView soundHint = text("Kurzes „Ding-Ding“ in Medienlautstärke; laufende Musik wird dafür kurz leiser.",
                12, C_MUTED, false);
        box.addView(soundHint);
        android.widget.Button btTest = new android.widget.Button(this);
        btTest.setText("\uD83D\uDCF1  Test: in 5 Sekunden");
        Ui.pill(btTest, Ui.DARK);
        btTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.saveWake(MainActivity.this, swWake.isChecked(), swSound.isChecked());
                Toast.makeText(MainActivity.this, "Jetzt Display ausschalten – Testmeldung kommt in 5 Sekunden",
                        Toast.LENGTH_LONG).show();
                final android.content.Context app = getApplicationContext();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Game t = new Game();
                        t.id = "test";
                        t.league = "Testmeldung";
                        t.homeShort = "SGNM";
                        t.awayShort = "NEU";
                        t.homeScore = 2;
                        t.awayScore = 1;
                        try {
                            WakeActivity.show(app, t, "TOR SGNM!", "#77 Thelen\nVorlage #12 Douben\nÜberzahl · 15:25");
                        } catch (Exception e) {
                            Toast.makeText(app, "Display-Wecker: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                        }
                    }
                }, 5000);
            }
        });
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(-2, -2);
        btLp.gravity = Gravity.CENTER_HORIZONTAL;
        btLp.topMargin = dp(6);
        box.addView(btTest, btLp);

        section(box, "Hervorhebung in der Spielliste");
        final Switch swFav = toggle("Spiele hervorheben, wenn ein Teamname enthält:", Prefs.favOn(this));
        box.addView(swFav);
        final EditText etFav = new EditText(this);
        etFav.setText(Prefs.favTerms(this));
        etFav.setSingleLine(true);
        etFav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(etFav, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("mehrere Begriffe mit Komma trennen, z. B.  Neuss, Moers", 12, C_MUTED, false);
        box.addView(hint);

        final Switch[] swUpd = new Switch[1];
        updateSection(box, swUpd);

        Ui.dialog(new android.app.AlertDialog.Builder(this)
                .setView(withScrollHint(sv))
                .setNeutralButton("App beenden", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        quitApp(); // v2.7: aus der Kopfleiste hierher verschoben
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.save(MainActivity.this,
                                Prefs.LIST_STEPS[spList.getSelectedItemPosition()],
                                Prefs.TICK_STEPS[spTick.getSelectedItemPosition()],
                                swVib.isChecked(), swFav.isChecked(), etFav.getText().toString().trim());
                        Prefs.saveUpcoming(MainActivity.this, swRemind.isChecked(), swStart.isChecked());
                        Update.setAuto(MainActivity.this, swUpd[0].isChecked());
                        Prefs.saveWake(MainActivity.this, swWake.isChecked(), swSound.isChecked());
                        Prefs.saveFloat(MainActivity.this,
                                Prefs.INFO_STEPS[spInfo.getSelectedItemPosition()],
                                Prefs.EVENTS_STEPS[spEvents.getSelectedItemPosition()],
                                swMin.isChecked());
                        adapter.notifyDataSetChanged(); // Hervorhebung sofort neu zeichnen
                        scheduleAuto();
                        Toast.makeText(MainActivity.this, "Optionen gespeichert", Toast.LENGTH_SHORT).show();
                    }
                })
                .show());
    }

    private Spinner spinner(String[] names, int sel) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel);
        return sp;
    }

    private LinearLayout labeled(String label, View v) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 14, C_TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(dp(160), -2));
        return row;
    }

    private Switch toggle(String label, boolean on) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setTextColor(C_TEXT);
        sw.setChecked(on);
        sw.setPadding(0, dp(6), 0, dp(6));
        return sw;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View colorBlock(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        return v;
    }

    private void section(LinearLayout box, String title) {
        TextView h = text(title.toUpperCase(Locale.GERMANY), 11, C_ACCENT, true);
        h.setLetterSpacing(0.08f);
        h.setPadding(0, dp(14), 0, dp(4));
        box.addView(h);
    }

    private void item(LinearLayout box, String symbol, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView sym = text(symbol, 12, C_TEXT, true);
        sym.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(sym, new LinearLayout.LayoutParams(dp(30), -2));
        row.addView(text(body, 13, C_TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(row);
    }

    /** Beendet alles: Floating-Dienst stoppen (entfernt alle Displays) und App aus der Uebersicht nehmen. */
    private void quitApp() {
        int n = OverlayService.pendingCount();
        if (n == 0) {
            quitAll();
            return;
        }
        Ui.dialog(new android.app.AlertDialog.Builder(this)
                .setTitle("Beenden")
                .setMessage(n == 1 ? "Es ist noch 1 Spiel vorgemerkt." : "Es sind noch " + n + " Spiele vorgemerkt.")
                .setPositiveButton("Alles beenden", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        quitAll();
                    }
                })
                .setNegativeButton("Nur App schließen", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        // Dienst laeuft weiter: Vormerkungen und Floatings bleiben erhalten
                        finishAndRemoveTask();
                    }
                })
                .setNeutralButton("Abbrechen", null)
                .show());
    }

    private void quitAll() {
        stopService(new Intent(this, OverlayService.class));
        finishAndRemoveTask();
    }

    private void openOverlaySettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private String lastOk = "";

    private void refresh(final boolean force) {
        if (loading) return;
        loading = true;
        refreshBtn.setEnabled(false);
        status.setText("Suche laufende Spiele …");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                List<Game> res = null;
                String err = null;
                try {
                    res = Api.fetchLiveGames();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                if (res != null) {
                    List<Game> up = Api.upcomingToday(force); // nur, wenn der Server antwortet
                    java.util.HashSet<String> live = new java.util.HashSet<>();
                    for (Game g : res) live.add(g.id);
                    boolean sep = false;
                    for (Game g : up) {
                        if (live.contains(g.id)) continue; // inzwischen live
                        if (!sep) {
                            Game s = new Game();
                            s.type = 2;
                            s.id = "sep";
                            res.add(s);
                            sep = true;
                        }
                        res.add(g);
                    }
                }
                final List<Game> fRes = res;
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        refreshBtn.setEnabled(true);
                        scheduleAuto();
                        String time = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(new Date());
                        if (fRes == null) {
                            // alter Stand bleibt stehen; deutlich rot melden, neuer Versuch im normalen Intervall
                            status.setTextColor(C_ACCENT);
                            status.setText("\u26A0 " + (fErr == null ? "Fehler" : fErr) + " · Versuch " + time
                                    + (lastOk.isEmpty() ? "" : " · letzter Stand " + lastOk));
                            emptyTxt.setText("\u26A0 Server nicht erreichbar – es wird automatisch erneut versucht.");
                            return;
                        }
                        status.setTextColor(Color.DKGRAY);
                        emptyTxt.setText("Heute läuft kein Spiel mehr.");
                        lastOk = time;
                        games.clear();
                        games.addAll(fRes);
                        adapter.notifyDataSetChanged();
                        int nLive = 0, nUp = 0, fav = 0;
                        for (Game g : games) {
                            if (g.type == 0) nLive++;
                            if (g.type == 1) nUp++;
                            if (g.type != 2 && Prefs.isFav(MainActivity.this, g)) fav++;
                        }
                        status.setText((nLive == 0 ? "Kein Spiel live" : nLive + " live") + " · " + nUp + " heute noch"
                                + (fav > 0 ? " · " + fav + " hervorgehoben" : "") + " · " + time
                                + (Api.lastAsked >= 0 ? " · " + Api.lastAsked + "/" + Api.divisionCount() + " Ligen" : "")
                                + (Api.lastFailed > 0 ? " · \u26A0 " + Api.lastFailed + " Ligen ohne Antwort" : ""));
                    }
                });
            }
        });
    }

    private void startTicker(Game g) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bitte zuerst „Über anderen Apps einblenden“ erlauben.", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        boolean remind = g.status == 0 && Prefs.remindMode(this);
        if (OverlayService.liveGame(g.id) != null) {
            // schon angepinnt -> nochmal antippen entfernt das Floating
            Intent r = new Intent(this, OverlayService.class);
            r.setAction(OverlayService.ACTION_REMOVE);
            r.putExtra(OverlayService.EXTRA_ID, g.id);
            startForegroundService(r);
            Toast.makeText(this, "Floating " + g.homeShort + " – " + g.awayShort + " entfernt", Toast.LENGTH_SHORT).show();
        } else if (remind && OverlayService.isPending(g.id)) {
            Intent u = new Intent(this, OverlayService.class);
            u.setAction(OverlayService.ACTION_UNREMIND);
            u.putExtra(OverlayService.EXTRA_ID, g.id);
            startForegroundService(u);
            Toast.makeText(this, "Vormerkung für " + g.homeShort + " – " + g.awayShort + " aufgehoben", Toast.LENGTH_SHORT).show();
        } else {
            Intent i = new Intent(this, OverlayService.class);
            i.setAction(OverlayService.ACTION_ADD);
            i.putExtra(OverlayService.EXTRA_GAME, g.toJson().toString());
            startForegroundService(i);
            Toast.makeText(this, remind
                    ? "\u23F0 Vorgemerkt – das Floating erscheint bei Spielbeginn"
                    : g.homeShort + " – " + g.awayShort + " schwebt jetzt auf dem Bildschirm", Toast.LENGTH_SHORT).show();
            if (!remind && Prefs.minimizeOnPin(this)) { // beim Vormerken offen lassen
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        moveTaskToBack(true); // App in den Hintergrund, Floating bleibt
                    }
                }, 700);
            }
        }
        // Markierung in der Liste nachziehen, sobald der Dienst die Vormerkung uebernommen hat
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        }, 500);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ Liste
    private class GameAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return games.size();
        }

        @Override
        public Object getItem(int i) {
            return games.get(i);
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
            return games.get(pos).type == 2 ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int pos) {
            return games.get(pos).type != 2;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            if (games.get(pos).type == 2) {
                TextView sep = cv instanceof TextView ? (TextView) cv : new TextView(MainActivity.this);
                sep.setText("Heute noch");
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                sep.setTypeface(Typeface.DEFAULT_BOLD);
                sep.setLetterSpacing(0.08f);
                sep.setAllCaps(true);
                sep.setTextColor(Color.rgb(110, 116, 124));
                sep.setBackgroundColor(Color.rgb(238, 238, 238));
                sep.setPadding(dp(8), dp(6), dp(8), dp(6));
                return sep;
            }
            Row row = cv instanceof Row ? (Row) cv : new Row();
            row.bind(games.get(pos));
            return row;
        }
    }

    private class Row extends LinearLayout {
        final TeamBadge home, away;
        final TextView league, teams, score, info;
        String boundId = "";

        Row() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(4), dp(10), dp(4), dp(10));

            league = new TextView(getContext());
            league.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            league.setTextColor(Color.GRAY);
            league.setMaxLines(1);
            addView(league);

            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(6), 0, 0);
            home = new TeamBadge(getContext(), dp(36), false);
            away = new TeamBadge(getContext(), dp(36), false);
            line.addView(home);
            score = new TextView(getContext());
            score.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            score.setTypeface(Typeface.DEFAULT_BOLD);
            score.setTextColor(Color.BLACK);
            score.setGravity(Gravity.CENTER);
            line.addView(score, new LayoutParams(dp(78), -2));
            line.addView(away);
            teams = new TextView(getContext());
            teams.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            teams.setTextColor(Color.rgb(30, 30, 30));
            teams.setPadding(dp(12), 0, 0, 0);
            line.addView(teams, new LayoutParams(0, -2, 1f));
            addView(line);

            info = new TextView(getContext());
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            info.setTextColor(Color.rgb(0, 110, 190));
            info.setPadding(0, dp(4), 0, 0);
            addView(info);
        }

        void bind(final Game g) {
            boundId = g.id;
            setBackgroundColor(Prefs.isFav(MainActivity.this, g) ? Color.rgb(255, 246, 214) : Color.TRANSPARENT);
            league.setText(g.league.trim() + " · " + g.division.trim());
            score.setText(g.type == 1 ? "–" : g.score());
            boolean pend = OverlayService.isPending(g.id);
            setAlpha(g.type == 1 && !pend ? 0.55f : 1f);
            teams.setText(g.homeName + "\n" + g.awayName);
            boolean pinned = OverlayService.liveGame(g.id) != null;
            String ph = g.phaseText() + (g.type == 1 && !g.arenaShort.isEmpty() ? " · \uD83D\uDCCD " + g.arenaShort : "");
            info.setText(pinned ? "\uD83D\uDCCC angepinnt · " + ph
                    : pend ? "\u23F0 vorgemerkt · " + ph : ph);
            info.setTextColor(pend ? Color.rgb(200, 110, 0) : Color.rgb(0, 110, 190));
            home.setTeam(g.homeShort, null);
            away.setTeam(g.awayShort, null);
            bg.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap h = Api.logo(g.homeLogo);
                    final Bitmap a = Api.logo(g.awayLogo);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!g.id.equals(boundId)) return; // Zeile wurde inzwischen neu belegt
                            home.setTeam(g.homeShort, h);
                            away.setTeam(g.awayShort, a);
                        }
                    });
                }
            });
        }
    }
}
