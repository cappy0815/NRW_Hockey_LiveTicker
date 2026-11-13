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
        status = new TextView(this);
        status.setPadding(dp(4), 0, dp(8), 0);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setMaxLines(2);
        status.setTextColor(Color.DKGRAY);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        // v2.95: ↻ wie in Spielplan/Meine/Tabellen (vorher großer Knopf „Aktualisieren“)
        refreshBtn = new Button(this);
        refreshBtn.setText("\u21BB");
        Ui.outline(refreshBtn, Ui.DARK);
        refreshBtn.setMinWidth(0);
        refreshBtn.setMinimumWidth(0);
        refreshBtn.setPadding(dp(14), dp(4), dp(14), dp(4));
        refreshBtn.setContentDescription("Aktualisieren");
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(false); // v2.5: Live-Stände immer frisch, Saisonplan aus dem 30-Min.-Speicher
            }
        });
        bar.addView(refreshBtn, new LinearLayout.LayoutParams(-2, -2));
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
                // v2.95: lange drücken = Steckbrief (wie Antippen im Spielplan); dort Aufstellung und – bei Vormerkung – Wecker
                GameSheet.show(MainActivity.this, g, new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged(); // 🔔 in der Zeile nachziehen
                    }
                });
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
        openTabFromIntent(getIntent());
    }

    /** v2.94: App-Verknüpfungen „Meine Spiele“, „Spielplan“, „Tabellen“ öffnen direkt den Reiter */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openTabFromIntent(intent);
    }

    private void openTabFromIntent(Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        if (a.endsWith(".TAB_MINE")) selectTab(T_MINE);
        else if (a.endsWith(".TAB_PLAN")) selectTab(T_PLAN);
        else if (a.endsWith(".TAB_TABLES")) selectTab(T_TABLES);
        else if (a.endsWith(".SHOW_REFS")) { // v2.96: Schiri-Liste über dem Live-Reiter
            selectTab(T_LIVE);
            intent.setAction(null);           // nicht erneut öffnen, z. B. nach Drehen
            ui.post(new Runnable() {
                @Override
                public void run() {
                    RefereesSheet.show(MainActivity.this);
                }
            });
        }
    }

    // ------------------------------------------------------------------ Reiter (v2.7)
    // v2.71: Linien-Symbole (Vektor, eingefärbt) statt Emojis, aktiver Reiter mit grüner Pille
    private static final int[] TAB_ICONS = {R.drawable.ic_tab_live, R.drawable.ic_tab_plan, R.drawable.ic_tab_mine, R.drawable.ic_tab_tables};
    private static final String[] TAB_NAMES = {"Live", "Spielplan", "Meine", "Tabellen"};
    private final ImageView[] tabIcons = new ImageView[4];

    private LinearLayout tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(226, 229, 233));
        bar.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(Color.WHITE);
        for (int i = 0; i < 4; i++) {
            final int t = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(8), 0, dp(8));
            ImageView iv = new ImageView(this);
            iv.setImageResource(TAB_ICONS[i]);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(dp(18), dp(4), dp(18), dp(4));
            cell.addView(iv, new LinearLayout.LayoutParams(dp(60), dp(32)));
            TextView b = new TextView(this);
            b.setText(TAB_NAMES[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setSingleLine(true);
            b.setPadding(0, dp(3), 0, 0);
            cell.addView(b);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(t);
                }
            });
            tabIcons[i] = iv;
            tabBtns[i] = b;
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        bar.addView(row);
        return bar;
    }

    private void selectTab(int t) {
        curTab = t;
        for (int i = 0; i < 4; i++) {
            panes[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
            boolean on = i == t;
            int col = on ? C_GREEN : C_MUTED;
            tabIcons[i].setImageTintList(android.content.res.ColorStateList.valueOf(col));
            if (on) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(16));
                pill.setColor(Color.rgb(220, 240, 228)); // helles EHV-Grün
                tabIcons[i].setBackground(pill);
            } else {
                tabIcons[i].setBackground(null);
            }
            tabBtns[i].setTextColor(col);
            tabBtns[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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
            HelpDialog.show(this, appVersion());
            return true;
        }
        if (item.getItemId() == 3) {
            OptionsDialog.show(this);
            return true;
        }
        if (item.getItemId() == 5) {
            showUpdate();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ Optionen, Hilfe (v2.95: eigene Klassen)
    private static final int C_MUTED = Ui.MUTED;
    private static final int C_GREEN = Ui.GREEN;

    /** nach „Speichern“ in den Optionen */
    void optionsSaved() {
        adapter.notifyDataSetChanged(); // Hervorhebung sofort neu zeichnen
        scheduleAuto();
    }

    /**
     * v2.93: Display-Wecker für ein gerade vorgemerktes Spiel (das Floating gibt es vor dem Bully noch nicht).
     * Gespeichert je Spiel-ID (Prefs.setWakeForGame), gilt dann ab „Bully!“. Später: Steckbrief (lange drücken).
     */
    private void wakeDialog(final Game g) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(4));
        box.addView(Ui.text(this, g.homeShort + " – " + g.awayShort, 17, Ui.TEXT, true));
        box.addView(Ui.text(this, "\u23F0 vorgemerkt · " + g.phaseText(), 12, C_MUTED, false));
        final Switch sw = Ui.toggle(this, "Display-Wecker für dieses Spiel", Prefs.wakeForGame(this, g.id));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        box.addView(sw, lp);
        box.addView(Ui.text(this, "Ab „Bully!“ erscheinen Tor, Strafe, Drittel- und Spielende groß über dem Sperrbildschirm. "
                + "Später änderbar: Spiel in der Liste lange drücken (Steckbrief) oder – nach dem Bully – Floating lange drücken.", 12, C_MUTED, false));
        Ui.dialog(new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Prefs.setWakeForGame(MainActivity.this, g.id, sw.isChecked());
                        adapter.notifyDataSetChanged();
                    }
                })
                .show());
    }

    /** v2.95: „Alles deaktivieren“ – Floatings und Vormerkungen aus, die App bleibt offen (wie Benachrichtigung/App-Symbol) */
    void deactivateAll(boolean toast) {
        int n = OverlayService.deactivateAll(this);
        if (toast) Toast.makeText(this, OverlayService.deactivatedText(n), Toast.LENGTH_SHORT).show();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        }, 500);
    }


    /** „App beenden“: alles deaktivieren und App schließen; bei Vormerkungen vorher fragen, ob sie bleiben sollen. */
    void quitApp() {
        int n = OverlayService.pendingCount();
        if (n == 0) {
            quitAll();
            return;
        }
        Ui.dialog(new android.app.AlertDialog.Builder(this)
                .setTitle("App beenden")
                .setMessage((n == 1 ? "Es ist noch 1 Spiel vorgemerkt." : "Es sind noch " + n + " Spiele vorgemerkt.")
                        + "\n\n„Alles deaktivieren“: Vormerkungen und Floatings aus, App zu.\n„Behalten“: nur die App schließen, "
                        + "die Vormerkungen bleiben aktiv.")
                .setPositiveButton("Alles deaktivieren", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        quitAll();
                    }
                })
                .setNegativeButton("Behalten", new android.content.DialogInterface.OnClickListener() {
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
                            status.setTextColor(Ui.RED);
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
            if (remind) wakeDialog(g); // v2.93: Display-Wecker gleich beim Vormerken festlegen
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
        return Ui.dp(this, v);
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
        final ImageView red;
        String boundId = "";

        Row() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(4), dp(10), dp(4), dp(10));

            league = new TextView(getContext());
            league.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            league.setTextColor(Color.GRAY);
            league.setMaxLines(1);
            league.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // v2.81: Liga links, RED+-Logo rechts (antippen = Stream)
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(league, new LayoutParams(0, -2, 1f));
            red = GameSheet.streamBadge(MainActivity.this);
            top.addView(red, new LayoutParams(dp(74), dp(24)));
            addView(top);

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
            setBackgroundColor(Prefs.isFav(MainActivity.this, g) ? Ui.FAV : Color.TRANSPARENT);
            league.setText(GameSheet.leagueShort(g));
            GameSheet.bindStreamBadge(MainActivity.this, red, g);
            score.setText(g.type == 1 ? "–" : g.score());
            boolean pend = OverlayService.isPending(g.id);
            setAlpha(g.type == 1 && !pend ? 0.55f : 1f);
            teams.setText(g.homeName + "\n" + g.awayName);
            boolean pinned = OverlayService.liveGame(g.id) != null;
            String ph = g.phaseText() + (g.type == 1 && !g.arenaShort.isEmpty() ? " · \uD83D\uDCCD " + g.arenaShort : "")
                    + GameSheet.streamTag(MainActivity.this, g);
            info.setText(pinned ? "\uD83D\uDCCC angepinnt · " + ph
                    : pend ? "\u23F0 vorgemerkt" + (Prefs.wakeForGame(MainActivity.this, g.id) ? " \uD83D\uDD14" : "") + " · " + ph : ph);
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
