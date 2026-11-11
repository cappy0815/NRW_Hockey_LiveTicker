package de.ehvnrw.liveticker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Display-Wecker: schaltet bei einem Spielereignis den Bildschirm ein und zeigt die Meldung
 * ein paar Sekunden ueber dem Sperrbildschirm (ohne Entsperren). Danach schliesst sie sich;
 * das Display geht nach der normalen Bildschirm-Zeitsperre wieder aus.
 * Start aus dem Hintergrund ist erlaubt, weil die App "Ueber anderen Apps einblenden" hat.
 */
public class WakeActivity extends Activity {

    static final long SHOW_MS = 8000;
    private static final String EX_GAME = "game", EX_HEAD = "head", EX_DETAIL = "detail";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable closer = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };
    private TextView tvLeague, tvHead, tvScore, tvHome, tvAway, tvDetail;
    private ImageView ivHome, ivAway;

    /** Meldung anzeigen (aus Dienst oder Testknopf) */
    static void show(Context c, Game g, String head, String detail) {
        Intent i = new Intent(c, WakeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(EX_GAME, g.toJson().toString());
        i.putExtra(EX_HEAD, head);
        i.putExtra(EX_DETAIL, detail == null ? "" : detail);
        c.startActivity(i);
        if (Prefs.wakeSound(c)) Chime.play(c);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ueber der Sperre anzeigen + Display einschalten
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // nur solange die Meldung steht

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvLeague = text(15, Color.rgb(170, 170, 170), false);
        root.addView(tvLeague);

        tvHead = text(44, Color.WHITE, true);
        tvHead.setPadding(0, dp(10), 0, dp(18));
        root.addView(tvHead);

        // Logos + Stand
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        ivHome = new ImageView(this);
        ivAway = new ImageView(this);
        tvScore = text(64, Color.WHITE, true);
        tvScore.setPadding(dp(18), 0, dp(18), 0);
        row.addView(ivHome, new LinearLayout.LayoutParams(dp(72), dp(72)));
        row.addView(tvScore);
        row.addView(ivAway, new LinearLayout.LayoutParams(dp(72), dp(72)));
        root.addView(row);

        LinearLayout names = new LinearLayout(this);
        names.setGravity(Gravity.CENTER);
        tvHome = text(18, Color.rgb(220, 220, 220), true);
        tvAway = text(18, Color.rgb(220, 220, 220), true);
        TextView gap = text(18, Color.TRANSPARENT, false);
        gap.setText("   ·   ");
        names.addView(tvHome);
        names.addView(gap);
        names.addView(tvAway);
        names.setPadding(0, dp(6), 0, 0);
        root.addView(names);

        tvDetail = text(19, Color.rgb(230, 230, 230), false);
        tvDetail.setPadding(0, dp(26), 0, 0);
        root.addView(tvDetail);

        TextView hint = text(13, Color.rgb(110, 110, 110), false);
        hint.setText("Tippen zum Schließen");
        hint.setPadding(0, dp(36), 0, 0);
        root.addView(hint);

        setContentView(root);
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        bind(i); // neue Meldung waehrend die alte noch steht
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(closer);
        super.onDestroy();
    }

    private void bind(Intent i) {
        Game g = Game.fromJson(i.getStringExtra(EX_GAME) == null ? "{}" : i.getStringExtra(EX_GAME));
        String head = i.getStringExtra(EX_HEAD);
        if (head == null) head = "";
        tvLeague.setText(g.league);
        tvLeague.setVisibility(g.league == null || g.league.isEmpty() ? View.GONE : View.VISIBLE);
        tvHead.setText(head);
        // Farbe nach Ereignis: Tor gelb, Strafe rot, sonst hellblau
        tvHead.setTextColor(head.startsWith("TOR") ? Color.rgb(255, 213, 79)
                : head.startsWith("Strafe") ? Color.rgb(239, 83, 80) : Color.rgb(144, 202, 249));
        tvScore.setText(g.homeScore + " : " + g.awayScore);
        tvHome.setText(g.homeShort);
        tvAway.setText(g.awayShort);
        String d = i.getStringExtra(EX_DETAIL);
        tvDetail.setText(d == null ? "" : d);
        tvDetail.setVisibility(d == null || d.isEmpty() ? View.GONE : View.VISIBLE);
        loadLogo(ivHome, g.homeLogo);
        loadLogo(ivAway, g.awayLogo);
        ui.removeCallbacks(closer);
        ui.postDelayed(closer, SHOW_MS);
    }

    private void loadLogo(final ImageView iv, final String url) {
        iv.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm;
                Bitmap t = null;
                try {
                    t = Api.logo(url);
                } catch (Exception ignored) {
                }
                bm = t;
                if (bm != null) ui.post(new Runnable() {
                    @Override
                    public void run() {
                        iv.setImageBitmap(bm);
                    }
                });
            }
        }).start();
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = Ui.text(this, "", sp, color, bold);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
