package de.ehvnrw.liveticker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Detailansicht zu einem Floating (lange druecken): alle Ereignisse + Display-Wecker je Spiel.
 * Aktualisiert sich alle 5 s aus dem laufenden Floating; schliesst nur ueber "Schliessen".
 */
public class DetailActivity extends Activity {

    private static final String EX_ID = "id";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String gameId;
    private LinearLayout streamBox;
    private TextView tvTitle, tvScore, tvPhase, tvEvents, tvWakeHint, tvArena, tvRefs;
    private Switch swWake;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            bind();
            ui.postDelayed(this, 5000);
        }
    };

    static void open(Context c, String gameId) {
        Intent i = new Intent(c, DetailActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EX_ID, gameId);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        gameId = getIntent().getStringExtra(EX_ID);
        setFinishOnTouchOutside(false); // Stufe 4 schliesst nur ueber den Knopf

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(Color.rgb(24, 26, 30));
        bgd.setCornerRadius(dp(18));
        root.setBackground(bgd);

        tvTitle = text(13, Color.rgb(170, 170, 170), false);
        root.addView(tvTitle);
        tvScore = text(26, Color.WHITE, true);
        tvScore.setPadding(0, dp(4), 0, 0);
        root.addView(tvScore);
        tvPhase = text(14, Color.rgb(170, 200, 230), false);
        root.addView(tvPhase);
        // v2.6: Schiedsrichter aus esrw.de
        tvRefs = text(13, Color.rgb(200, 200, 200), false);
        tvRefs.setPadding(0, dp(4), 0, 0);
        root.addView(tvRefs);
        // v2.8: Livestream-Knopf (RED+), wird in bind() einmalig gefüllt
        streamBox = new LinearLayout(this);
        streamBox.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(streamBox, new LinearLayout.LayoutParams(-1, -2));
        tvArena = text(13, Color.rgb(127, 211, 255), false);
        tvArena.setPadding(0, dp(4), 0, 0);
        tvArena.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Game g = OverlayService.liveGame(gameId);
                if (g != null) GameSheet.maps(DetailActivity.this, g);
            }
        });
        root.addView(tvArena);

        // Display-Wecker fuer dieses Spiel
        swWake = new Switch(this);
        swWake.setText("Display-Wecker für dieses Spiel");
        swWake.setTextColor(Color.WHITE);
        swWake.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        swWake.setPadding(0, dp(14), 0, 0);
        swWake.setChecked(Prefs.wakeForGame(this, gameId));
        swWake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setWakeForGame(DetailActivity.this, gameId, on);
            }
        });
        root.addView(swWake);
        tvWakeHint = text(12, Color.rgb(150, 150, 150), false);
        tvWakeHint.setGravity(Gravity.START);
        root.addView(tvWakeHint);

        TextView head = text(13, Color.rgb(170, 170, 170), true);
        head.setGravity(Gravity.START);
        head.setText("Alle Ereignisse");
        head.setPadding(0, dp(16), 0, dp(4));
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        tvEvents = new TextView(this);
        tvEvents.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvEvents.setTypeface(Typeface.MONOSPACE);
        tvEvents.setTextColor(Color.rgb(225, 225, 225));
        tvEvents.setLineSpacing(dp(2), 1f);
        sv.addView(tvEvents);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button close = new Button(this);
        close.setText("Schließen");
        Ui.pill(close, Ui.RED);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        // Knopfzeile: Tabelle (links) + Schliessen (rechts)
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER); // Knoepfe mittig
        Button table = new Button(this);
        table.setText("Tabelle");
        Ui.outline(table, Color.WHITE);
        table.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Game g = OverlayService.liveGame(gameId);
                if (g == null || g.divisionId.isEmpty()) {
                    android.widget.Toast.makeText(DetailActivity.this, "Keine Tabelle verfügbar", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                GameSheet.standings(DetailActivity.this, g.divisionId, GameSheet.leagueShort(g));
            }
        });
        bar.addView(table);
        // v2.5: Aufstellung auch hier (bisher nur per langem Druck in der Liste)
        Button lineup = new Button(this);
        lineup.setText("Aufstellung");
        Ui.outline(lineup, Color.WHITE);
        lineup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Game g = OverlayService.liveGame(gameId);
                if (g == null) {
                    android.widget.Toast.makeText(DetailActivity.this, "Keine Aufstellung verfügbar", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                LineupSheet.show(DetailActivity.this, g);
            }
        });
        LinearLayout.LayoutParams gapL = new LinearLayout.LayoutParams(-2, -2);
        gapL.leftMargin = dp(8);
        bar.addView(lineup, gapL);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = dp(8);
        bar.addView(close, gap);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(10);
        root.addView(bar, cl);

        setContentView(root);
        // Dialog fast bildschirmbreit, max. 85 % hoch
        getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) OverlayService.compact(gameId); // Floating zurueck auf Stufe 1
        super.onDestroy();
    }

    private void bind() {
        Game g = OverlayService.liveGame(gameId);
        if (g == null) {
            tvScore.setText("Floating geschlossen");
            tvEvents.setText("");
            return;
        }
        tvTitle.setText(g.league);
        tvArena.setText(g.arenaLong.isEmpty() ? "" : "\uD83D\uDCCD " + g.arenaLong + "  ›");
        GameSheet.underlineAfterIcon(tvArena);
        tvArena.setVisibility(g.arenaLong.isEmpty() ? View.GONE : View.VISIBLE);
        tvScore.setText(g.homeShort + "   " + (g.status == 0 ? "– : –" : g.homeScore + " : " + g.awayScore) + "   " + g.awayShort);
        tvPhase.setText(g.phaseText());
        tvRefs.setText(g.refs.isEmpty() ? "" : "\uD83E\uDDD1\u200D\u2696\uFE0F " + g.refs);
        tvRefs.setVisibility(g.refs.isEmpty() ? View.GONE : View.VISIBLE);
        if (streamBox.getChildCount() == 0) {
            View sv = GameSheet.streamView(this, g, true);
            if (sv != null) streamBox.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        }
        tvWakeHint.setText("Standard in den Optionen: " + (Prefs.wakeOn(this) ? "an" : "aus")
                + " – dieser Schalter gilt nur für dieses Spiel (mit Ton, falls in den Optionen an)");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.events.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(g.events.get(i));
        }
        tvEvents.setText(sb.length() == 0 ? "Noch keine Tore oder Strafen" : sb.toString());
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
