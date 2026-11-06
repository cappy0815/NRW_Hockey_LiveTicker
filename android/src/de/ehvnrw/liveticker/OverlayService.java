package de.ehvnrw.liveticker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vordergrund-Dienst mit schwebenden, verschiebbaren Live-Displays (eins pro Spiel).
 * Tippen: kompakt -> Infozeile -> Ereignisliste · Ziehen: verschieben · auf das X unten ziehen oder lange druecken: schliessen.
 */
public class OverlayService extends Service {
    public static final String ACTION_ADD = "de.ehvnrw.liveticker.ADD";
    public static final String ACTION_STOP = "de.ehvnrw.liveticker.STOP";
    public static final String ACTION_UNREMIND = "de.ehvnrw.liveticker.UNREMIND";
    public static final String ACTION_REMOVE = "de.ehvnrw.liveticker.REMOVE";
    public static final String EXTRA_ID = "id";
    private static final long PENDING_POLL_MS = 60000;

    /** Vorgemerkte Spiele (fuer die Anzeige in der Liste). */
    private static final java.util.Set<String> PENDING_IDS = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static boolean isPending(String id) {
        return PENDING_IDS.contains(id);
    }

    public static int pendingCount() {
        return PENDING_IDS.size();
    }

    private final Map<String, Game> pending = new LinkedHashMap<>();
    private final Runnable pendingTask = new Runnable() {
        @Override
        public void run() {
            checkPending();
        }
    };
    public static final String EXTRA_GAME = "game";

    private static final String CHANNEL = "ticker";
    private static final int NOTIF_ID = 1;
    private static final long FLASH_MS = 12000;
    private static final int MAX_EVENTS = 8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    private final Map<String, Ticker> tickers = new LinkedHashMap<>();
    private WindowManager wm;
    private SharedPreferences prefs;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences("overlay", MODE_PRIVATE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Live-Ticker", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        nm.deleteNotificationChannel("auto"); // Altlast aus v2.1/2.11 (Android Auto) entfernen
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForeground muss nach startForegroundService sofort erfolgen
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            // z. B. Start aus dem Hintergrund nicht erlaubt -> sauber beenden
            removeAll();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            removeAll();
            return START_NOT_STICKY;
        }
        if (ACTION_REMOVE.equals(action)) {
            // Floating ueber die Spielliste wieder entfernen
            Ticker t = tickers.get(intent.getStringExtra(EXTRA_ID));
            if (t != null) {
                removeTicker(t);
                return START_NOT_STICKY;
            }
        }
        if (ACTION_UNREMIND.equals(action)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (id != null) {
                pending.remove(id);
                PENDING_IDS.remove(id);
            }
        }
        if (ACTION_ADD.equals(action)) {
            String js = intent.getStringExtra(EXTRA_GAME);
            if (js != null) {
                Game g = Game.fromJson(js);
                if (g.status == 0 && Prefs.remindMode(this)) addPending(g);
                else addTicker(g);
            }
        }
        if (tickers.isEmpty() && pending.isEmpty()) stopSelfCompletely();
        else updateNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        hideDismissTarget();
        ui.removeCallbacks(pendingTask);
        pending.clear();
        PENDING_IDS.clear();
        for (Ticker t : tickers.values()) t.detach();
        tickers.clear();
        bg.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Verwaltung
    private static OverlayService INSTANCE;

    /** Floating zurueck auf Stufe 1 (z. B. nach dem Schliessen des Detailfensters) */
    static void compact(String id) {
        if (INSTANCE == null || id == null) return;
        Ticker t = INSTANCE.tickers.get(id);
        if (t != null) t.toCompact();
    }

    /** laufende Floatings fuer die Detailansicht (nur Hauptthread) */
    private static final Map<String, Game> LIVE = new LinkedHashMap<>();

    static Game liveGame(String id) {
        return id == null ? null : LIVE.get(id);
    }

    private void addTicker(Game g) {
        if (g.id.isEmpty() || tickers.containsKey(g.id)) return;
        Ticker t = new Ticker(g, tickers.size());
        tickers.put(g.id, t);
        LIVE.put(g.id, g);
        t.attach();
        t.poll();
        updateNotification();
    }

    // ------------------------------------------------------------------ Stapel
    /** oberstes Floating des Stapels (bestimmt die Position aller gestapelten) */
    private Ticker anchor() {
        for (Ticker t : tickers.values()) if (t.stacked && t.attached) return t;
        return null;
    }

    /**
     * Gestapelte Floatings untereinander anordnen: jedes sitzt unter dem vorigen.
     * Waechst eins auf Stufe 2, rutschen die darunter nach unten (und spaeter wieder hoch).
     * Stufe 3 (volle Breite) zaehlt mit seiner kleinen Hoehe – dafuer weicht niemand aus.
     */
    void relayout() {
        Ticker a = anchor();
        if (a == null) return;
        int x = a.mode == 2 ? prefs.getInt("x", dp(16)) : a.lp.x;
        int nextY = a.lp.y + a.stackHeight() + dp(8);
        for (Ticker t : tickers.values()) {
            if (t == a || !t.stacked || !t.attached) continue;
            if (t.mode != 2) t.lp.x = x;
            if (t.lp.y != nextY || t.mode != 2) {
                t.lp.y = nextY;
                try {
                    wm.updateViewLayout(t.root, t.lp);
                } catch (Exception ignored) {
                }
            }
            nextY += t.stackHeight() + dp(8);
        }
    }

    private void removeTicker(Ticker t) {
        // war es das oberste? -> das naechste rueckt an seine Stelle
        if (t == anchor()) {
            for (Ticker n : tickers.values()) {
                if (n == t || !n.stacked || !n.attached) continue;
                n.lp.y = t.lp.y;
                if (n.mode != 2) n.lp.x = t.mode == 2 ? prefs.getInt("x", dp(16)) : t.lp.x;
                try {
                    wm.updateViewLayout(n.root, n.lp);
                } catch (Exception ignored) {
                }
                break;
            }
        }
        t.detach();
        tickers.remove(t.game.id);
        ui.post(new Runnable() {
            @Override
            public void run() {
                relayout();
            }
        });
        LIVE.remove(t.game.id);
        if (tickers.isEmpty() && pending.isEmpty()) stopSelfCompletely();
        else updateNotification();
    }

    private void removeAll() {
        LIVE.clear();
        for (Ticker t : tickers.values()) t.detach();
        tickers.clear();
        pending.clear();
        PENDING_IDS.clear();
        ui.removeCallbacks(pendingTask);
        stopSelfCompletely();
    }

    // ------------------------------------------------------------------ Vormerkungen
    private void addPending(Game g) {
        if (g.id.isEmpty() || tickers.containsKey(g.id) || pending.containsKey(g.id)) return;
        pending.put(g.id, g);
        PENDING_IDS.add(g.id);
        ui.removeCallbacks(pendingTask);
        ui.post(pendingTask); // sofort einmal pruefen (vielleicht laeuft es schon)
    }

    /** Fragt alle vorgemerkten Spiele ab; laeuft eines, erscheint das Floating. */
    private void checkPending() {
        if (pending.isEmpty()) return;
        final java.util.List<Game> copy = new java.util.ArrayList<>();
        for (Game g : pending.values()) copy.add(Game.fromJson(g.toJson().toString()));
        bg.execute(new Runnable() {
            @Override
            public void run() {
                for (Game g : copy) {
                    try {
                        Api.refreshGame(g);
                    } catch (Exception ignored) {
                        g.status = 0; // Netzfehler -> spaeter erneut
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Game g : copy) {
                            if (!pending.containsKey(g.id)) continue; // inzwischen aufgehoben
                            if (g.status == 0) continue;
                            pending.remove(g.id);
                            PENDING_IDS.remove(g.id);
                            if (g.status == 4) continue; // schon vorbei
                            addTicker(g);
                            gameStarted(g);
                        }
                        if (tickers.isEmpty() && pending.isEmpty()) {
                            stopSelfCompletely();
                            return;
                        }
                        updateNotification();
                        if (!pending.isEmpty()) ui.postDelayed(pendingTask, PENDING_POLL_MS);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ Display-Wecker
    /**
     * Zeigt die Meldung ueber dem Sperrbildschirm und schaltet das Display ein.
     * Nur wenn das Display aus oder gesperrt ist – bei entsperrtem Handy reicht das Floating.
     */
    private void wakeNotify(Game g, String head, String detail) {
        if (!Prefs.wakeForGame(this, g.id)) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (pm.isInteractive() && !km.isKeyguardLocked()) return;
            WakeActivity.show(this, g, head, detail);
        } catch (Exception e) {
            Toast.makeText(this, "Display-Wecker: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    /** Hinweis bei Spielbeginn: Vibration (abschaltbar) + kurze Meldung */
    private void gameStarted(Game g) {
        wakeNotify(g, "Bully!", "Das Spiel hat begonnen");
        if (Prefs.startVibrate(this)) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator())
                    v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 400, 150, 400}, -1));
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "\uD83C\uDFD2 " + g.homeShort + " – " + g.awayShort + " läuft!", Toast.LENGTH_LONG).show();
    }

    private void stopSelfCompletely() {
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------ Benachrichtigung
    /** "U15 · NEV 2:1 KEV · 2. Drittel" – eine Zeile je Spiel fuer die Benachrichtigung */
    private static String notifLine(Game g) {
        String cls = Game.classShort(g);
        return (cls == null || cls.isEmpty() ? "" : cls + " · ") + g.homeShort + " " + g.homeScore + ":"
                + g.awayScore + " " + g.awayShort + " · " + g.phaseText();
    }

    private Notification buildNotification() {
        String title, text;
        // v2.4: bei mehreren Spielen jedes Spiel in einer eigenen Zeile (InboxStyle)
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Ticker t : tickers.values()) lines.add(notifLine(t.game));
        for (Game g : pending.values()) {
            String cls = Game.classShort(g);
            lines.add("\u23F0 " + (cls == null || cls.isEmpty() ? "" : cls + " · ") + g.homeShort + " – " + g.awayShort
                    + (g.scheduledTime.isEmpty() ? "" : " · " + g.scheduledTime + " Uhr"));
        }
        if (tickers.isEmpty() && !pending.isEmpty()) {
            title = pending.size() == 1 ? "EHV Live – 1 Spiel vorgemerkt" : "EHV Live – " + pending.size() + " Spiele vorgemerkt";
            text = lines.get(0);
        } else if (tickers.isEmpty()) {
            title = "EHV Live";
            text = "Live-Display wird gestartet …";
        } else if (tickers.size() == 1) {
            Game g = tickers.values().iterator().next().game;
            title = g.homeShort + "  " + g.homeScore + ":" + g.awayScore + "  " + g.awayShort;
            text = g.phaseText();
            if (!pending.isEmpty()) text += "  ·  \u23F0 " + pending.size() + " vorgemerkt";
        } else {
            title = "EHV Live – " + tickers.size() + " Spiele live";
            text = lines.get(0) + "  …";
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(null, "Beenden", stop).build();
        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(stopAction);
        if (lines.size() > 1) {
            Notification.InboxStyle inbox = new Notification.InboxStyle().setBigContentTitle(title);
            for (String l : lines) inbox.addLine(l);
            nb.setStyle(inbox);
        }
        return nb.build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }

    /** kurzes Doppel-Vibrieren bei einem Tor */
    private void vibrateGoal() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator())
                v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 150, 100, 150}, -1));
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ "X" zum Entfernen (unten mittig)
    private TextView dismissView;
    private GradientDrawable dismissBg;
    private boolean dismissShown, dismissOver;

    private void showDismissTarget() {
        if (dismissShown) return;
        if (dismissView == null) {
            dismissView = new TextView(this);
            dismissView.setText("✕");
            dismissView.setTextColor(Color.WHITE);
            dismissView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            dismissView.setGravity(Gravity.CENTER);
            dismissBg = new GradientDrawable();
            dismissBg.setShape(GradientDrawable.OVAL);
            dismissBg.setStroke(dp(2), Color.WHITE);
            dismissView.setBackground(dismissBg);
        }
        dismissOver = false;
        dismissBg.setColor(Color.argb(200, 40, 40, 40));
        dismissView.setScaleX(1f);
        dismissView.setScaleY(1f);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(64), dp(64),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(56);
        p.alpha = 0.8f; // Android 12+: nicht beruehrbare Overlays duerfen max. 0.8 deckend sein
        try {
            wm.addView(dismissView, p);
            dismissShown = true;
        } catch (Exception ignored) {
        }
    }

    private void hideDismissTarget() {
        if (!dismissShown) return;
        try {
            wm.removeView(dismissView);
        } catch (Exception ignored) {
        }
        dismissShown = false;
    }

    /** Liegt der Finger ueber dem X? (grosszuegiger Radius) */
    private boolean isOverDismissTarget(float rawX, float rawY) {
        if (!dismissShown || dismissView.getWidth() == 0) return false;
        int[] loc = new int[2];
        dismissView.getLocationOnScreen(loc);
        float cx = loc[0] + dismissView.getWidth() / 2f;
        float cy = loc[1] + dismissView.getHeight() / 2f;
        float dx = rawX - cx, dy = rawY - cy;
        float r = dp(70);
        return dx * dx + dy * dy <= r * r;
    }

    private void setDismissHighlight(boolean over) {
        if (!dismissShown || over == dismissOver) return;
        dismissOver = over;
        dismissBg.setColor(over ? Color.argb(230, 211, 47, 47) : Color.argb(200, 40, 40, 40));
        dismissView.animate().scaleX(over ? 1.3f : 1f).scaleY(over ? 1.3f : 1f).setDuration(120).start();
        if (over) dismissView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ------------------------------------------------------------------ Ein schwebendes Display
    private class Ticker {
        final Game game;
        final LinearLayout root;
        final TeamBadge home, away;
        final TextView score, info, events;
        final WindowManager.LayoutParams lp;
        boolean attached, ended;
        /** gehoert zum Stapel (false, wenn von Hand aus dem Stapel gezogen) */
        boolean stacked = true;
        /** Hoehe auf Stufe 1/2 – Stufe 3 verdraengt niemanden */
        int smallH = 0;

        int stackHeight() {
            if (mode == 2) return smallH > 0 ? smallH : root.getHeight();
            return root.getHeight() > 0 ? root.getHeight() : smallH;
        }
        int mode = 0; // Stufe 1 = kompakt, Stufe 2 = mit Infozeile, Stufe 3 = Ereignisliste (Stufe 4 = Detailfenster)
        int lastGoals = -1;
        int lastPens = -1;
        String lastPhase = null;
        long flashUntil;
        /** Stufe 2/3 faellt nach der eingestellten Zeit auf Stufe 1 zurueck */
        final Runnable collapseTask = new Runnable() {
            @Override
            public void run() {
                toCompact();
            }
        };

        void toCompact() {
            ui.removeCallbacks(collapseTask);
            if (mode == 0) return;
            mode = 0;
            render();
        }

        final Runnable pollTask = new Runnable() {
            @Override
            public void run() {
                poll();
            }
        };

        Ticker(Game g, int index) {
            game = g;
            mode = 0; // Stufe 1: kleines Floating

            root = new LinearLayout(OverlayService.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(10), dp(8), dp(10), dp(7));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(Color.argb(235, 24, 26, 30));
            shape.setCornerRadius(dp(22));
            shape.setStroke(dp(1), Color.argb(70, 255, 255, 255));
            root.setBackground(shape);
            root.setElevation(dp(6));

            LinearLayout line = new LinearLayout(OverlayService.this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            // Zeile ist so breit wie das Floating -> Inhalt horizontal UND vertikal zentrieren
            line.setGravity(Gravity.CENTER);
            home = new TeamBadge(OverlayService.this, dp(34), true);
            away = new TeamBadge(OverlayService.this, dp(34), true);
            score = new TextView(OverlayService.this);
            score.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            score.setTypeface(Typeface.DEFAULT_BOLD);
            score.setTextColor(Color.WHITE);
            score.setGravity(Gravity.CENTER);
            score.setMinWidth(dp(66));
            score.setPadding(dp(8), 0, dp(8), 0);
            line.addView(home);
            line.addView(score);
            line.addView(away);
            root.addView(line, new LinearLayout.LayoutParams(-1, -2));

            info = new TextView(OverlayService.this);
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            info.setTextColor(Color.rgb(170, 200, 230));
            info.setGravity(Gravity.CENTER);
            info.setPadding(0, dp(3), 0, 0);
            info.setMaxLines(1);
            root.addView(info, new LinearLayout.LayoutParams(-1, -2));

            events = new TextView(OverlayService.this);
            events.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            events.setTypeface(Typeface.MONOSPACE);
            events.setTextColor(Color.rgb(225, 225, 225));
            events.setLineSpacing(dp(1), 1f);
            events.setPadding(dp(4), dp(6), dp(4), dp(2));
            LinearLayout.LayoutParams evLp = new LinearLayout.LayoutParams(-1, -2);
            evLp.topMargin = dp(5);
            root.addView(events, evLp);

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = prefs.getInt("x", dp(16));
            lp.y = prefs.getInt("y", dp(140)) + index * dp(84); // wird gleich per Stapel ausgerichtet

            render();
            installTouch();
            loadLogos();
            // Hoehe aendert sich (Stufe 1 <-> 2) -> Stapel neu ordnen
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (b - t == ob - ot) return;
                    if (mode != 2) smallH = b - t;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            relayout();
                        }
                    });
                }
            });
        }

        void attach() {
            if (attached) return;
            try {
                wm.addView(root, lp);
                attached = true;
            } catch (Exception e) {
                Toast.makeText(OverlayService.this, "Overlay nicht erlaubt – bitte Berechtigung prüfen.", Toast.LENGTH_LONG).show();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        removeTicker(Ticker.this);
                    }
                });
            }
        }

        void detach() {
            ui.removeCallbacks(pollTask);
            ui.removeCallbacks(collapseTask);
            if (attached) {
                try {
                    wm.removeView(root);
                } catch (Exception ignored) {
                }
                attached = false;
            }
        }

        /** vor dem Bully halb durchsichtig, damit man es nicht mit einem laufenden Spiel verwechselt */
        float baseAlpha() {
            return game.status == 0 ? 0.5f : 1f;
        }

        /** aufgeklappt (Ereignisliste) = volle Displaybreite, sonst so schmal wie noetig */
        void applyWidth() {
            int w = mode == 2 ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
            int x = mode == 2 ? 0 : prefs.getInt("x", dp(16));
            if (lp.width == w && (mode != 2 || lp.x == 0)) return;
            lp.width = w;
            lp.x = x;
            if (attached) try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        }

        void render() {
            applyWidth();
            root.setAlpha(baseAlpha());
            score.setText(game.status == 0 ? "– : –" : game.homeScore + " : " + game.awayScore);
            boolean flash = System.currentTimeMillis() < flashUntil;
            score.setTextColor(flash ? Color.rgb(255, 210, 74) : Color.WHITE);
            String phase = game.phaseText();
            String cls = Game.classShort(game);
            if (!cls.isEmpty()) phase = cls + " · " + phase;
            // Glocke = Display-Wecker fuer dieses Spiel aktiv
            if (Prefs.wakeForGame(OverlayService.this, game.id)) phase = "\uD83D\uDD14 " + phase;
            info.setText(flash ? "TOR!  ·  " + phase : phase);
            info.setVisibility(mode >= 1 ? View.VISIBLE : View.GONE);
            if (mode == 2) {
                StringBuilder sb = new StringBuilder();
                if (!game.refs.isEmpty()) sb.append("Schiri: ").append(game.refs).append('\n'); // v2.6
                int n = Math.min(MAX_EVENTS, game.events.size());
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(game.events.get(i));
                }
                if (game.events.size() > n) sb.append("\n… ").append(game.events.size() - n).append(" weitere");
                if (game.events.isEmpty()) sb.append(lastGoals < 0 ? "Lade Ereignisse …" : "Noch keine Tore oder Strafen");
                events.setText(sb.toString());
                events.setVisibility(View.VISIBLE);
            } else {
                events.setVisibility(View.GONE);
            }
        }

        void loadLogos() {
            home.setTeam(game.homeShort, null);
            away.setTeam(game.awayShort, null);
            bg.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap h = Api.logo(game.homeLogo);
                    final Bitmap a = Api.logo(game.awayLogo);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            home.setTeam(game.homeShort, h);
                            away.setTeam(game.awayShort, a);
                        }
                    });
                }
            });
        }

        void poll() {
            if (!tickers.containsKey(game.id)) return;
            bg.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok;
                    final Game fresh = Game.fromJson(game.toJson().toString());
                    try {
                        Api.refreshGame(fresh);
                        ok = true;
                    } catch (Exception e) {
                        ok = false;
                    }
                    // v2.6: Schiedsrichter (esrw.de, hoechstens alle 30 Min. geladen, je Spiel gemerkt)
                    if (fresh.refs.isEmpty()) fresh.refs = Referees.forGame(fresh);
                    final boolean fOk = ok;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!tickers.containsKey(game.id)) return;
                            if (fOk) {
                                int goals = fresh.homeScore + fresh.awayScore;
                                if (lastGoals >= 0 && goals > lastGoals) {
                                    flashUntil = System.currentTimeMillis() + FLASH_MS;
                                    if (Prefs.vibrate(OverlayService.this)) vibrateGoal();
                                    score.animate().scaleX(1.25f).scaleY(1.25f).setDuration(180).withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            score.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                                        }
                                    }).start();
                                    ui.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            render();
                                        }
                                    }, FLASH_MS + 100);
                                }
                                // Display-Wecker (nicht beim allerersten Abruf)
                                boolean first = lastPens < 0 && lastPhase == null;
                                if (!first) {
                                    if (game.status != 4 && fresh.status == 4) {
                                        wakeNotify(fresh, "Spielende", "Endstand");
                                    } else if (lastGoals >= 0 && goals > lastGoals) {
                                        wakeNotify(fresh, "TOR " + fresh.lastGoalTeam + "!", fresh.lastGoalLong);
                                    } else if (fresh.phase.startsWith("INTERMISSION") && (lastPhase == null || !lastPhase.startsWith("INTERMISSION"))) {
                                        wakeNotify(fresh, "Drittelende", fresh.phaseText());
                                    } else if (fresh.penCount > lastPens && lastPens >= 0) {
                                        wakeNotify(fresh, "Strafe " + fresh.lastPenTeam, fresh.lastPenLong);
                                    }
                                }
                                lastPens = fresh.penCount;
                                lastPhase = fresh.phase;
                                lastGoals = goals;
                                game.homeScore = fresh.homeScore;
                                game.awayScore = fresh.awayScore;
                                game.phase = fresh.phase;
                                game.liveTime = fresh.liveTime;
                                if (game.status == 0 && fresh.status == 1) gameStarted(fresh);
                                game.status = fresh.status;
                                game.events = fresh.events;
                                if (!fresh.refs.isEmpty()) game.refs = fresh.refs;
                                ended = game.status == 4;
                                render();
                                updateNotification();
                            }
                            // Nach Spielende nicht mehr abfragen; Endstand bleibt sichtbar
                            if (!ended) ui.postDelayed(pollTask, Prefs.tickSec(OverlayService.this) * 1000L);
                        }
                    });
                }
            });
        }

        void installTouch() {
            final int slop = ViewConfiguration.get(OverlayService.this).getScaledTouchSlop();
            final long longPress = ViewConfiguration.getLongPressTimeout() + 150;
            final int dragSlop = slop * 2; // etwas Zittern beim langen Druecken ist kein Verschieben
            root.setOnTouchListener(new View.OnTouchListener() {
                float downRawX, downRawY;
                int startX, startY;
                boolean moved, closed;
                final Runnable closeTask = new Runnable() {
                    @Override
                    public void run() {
                        if (!moved) {
                            closed = true; // Tipp-Aktion beim Loslassen unterdruecken
                            hideDismissTarget();
                            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            try {
                                DetailActivity.open(OverlayService.this, game.id);
                            } catch (Exception ex) {
                                Toast.makeText(OverlayService.this, "Detailansicht: " + ex.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                };

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = e.getRawX();
                            downRawY = e.getRawY();
                            startX = lp.x;
                            startY = lp.y;
                            moved = false;
                            closed = false;
                            root.setAlpha(baseAlpha() * 0.85f);
                            ui.postDelayed(closeTask, longPress);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (closed) return true; // Detailfenster ist schon auf -> kein ✕ mehr einblenden
                            float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                            if (!moved && (Math.abs(dx) > dragSlop || Math.abs(dy) > dragSlop)) {
                                moved = true;
                                // oberstes Floating zieht den Stapel mit; jedes andere wird herausgeloest
                                if (stacked && Ticker.this != anchor()) {
                                    stacked = false;
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            relayout();
                                        }
                                    });
                                }
                                ui.removeCallbacks(closeTask);
                                showDismissTarget();
                            }
                            if (moved && attached) {
                                lp.x = mode == 2 ? 0 : startX + Math.round(dx); // volle Breite: nur hoch/runter
                                lp.y = startY + Math.round(dy);
                                try {
                                    wm.updateViewLayout(root, lp);
                                } catch (Exception ignored) {
                                }
                                if (stacked) relayout(); // Stapel folgt dem obersten
                                boolean over = isOverDismissTarget(e.getRawX(), e.getRawY());
                                setDismissHighlight(over);
                                root.setAlpha(baseAlpha() * (over ? 0.45f : 0.85f));
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            ui.removeCallbacks(closeTask);
                            root.setAlpha(baseAlpha());
                            boolean dropOnX = moved && e.getActionMasked() == MotionEvent.ACTION_UP
                                    && isOverDismissTarget(e.getRawX(), e.getRawY());
                            hideDismissTarget();
                            if (closed) return true;
                            if (dropOnX) {
                                // auf das X gezogen -> Display entfernen (Position bleibt die vorherige)
                                removeTicker(Ticker.this);
                                return true;
                            }
                            if (moved && !stacked) {
                                // herausgeloestes Floating: Position nicht als Stapel-Position merken
                            } else if (moved) {
                                if (mode == 2) prefs.edit().putInt("y", Math.max(0, lp.y)).apply();
                                else prefs.edit().putInt("x", Math.max(0, lp.x)).putInt("y", Math.max(0, lp.y)).apply();
                            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                                // Stufe 1 -> 2 -> 3 -> 1; Stufe 2 und 3 fallen nach eingestellter Zeit zurueck
                                mode = (mode + 1) % 3;
                                render();
                                ui.removeCallbacks(collapseTask);
                                int cs = mode == 1 ? Prefs.infoSec(OverlayService.this)
                                        : mode == 2 ? Prefs.eventsSec(OverlayService.this) : 0;
                                if (cs > 0) ui.postDelayed(collapseTask, cs * 1000L);
                            }
                            return true;
                    }
                    return false;
                }
            });
        }
    }
}
