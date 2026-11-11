package de.ehvnrw.liveticker;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** v2.95: Optionen-Fenster (vorher in MainActivity). Speichern meldet sich über MainActivity.optionsSaved(). */
final class OptionsDialog {
    private OptionsDialog() {
    }

    static void show(final MainActivity m) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(m, 20), Ui.dp(m, 18), Ui.dp(m, 20), Ui.dp(m, 6));
        ScrollView sv = new ScrollView(m);
        sv.addView(box);

        box.addView(Ui.text(m, "Optionen", 18, Ui.TEXT, true));
        Ui.stripe(m, box, 10);

        Ui.section(m, box, "Spielliste");
        final Spinner spList = spinner(m, Prefs.LIST_NAMES, Prefs.indexOf(Prefs.LIST_STEPS, Prefs.listSec(m)));
        box.addView(labeled(m, "Aktualisierung", spList));
        final Switch swMin = Ui.toggle(m, "App nach dem Anpinnen eines laufenden Spiels in den Hintergrund", Prefs.minimizeOnPin(m));
        box.addView(swMin);

        Ui.section(m, box, "Floating-Display");
        final Spinner spTick = spinner(m, Prefs.TICK_NAMES, Prefs.indexOf(Prefs.TICK_STEPS, Prefs.tickSec(m)));
        box.addView(labeled(m, "Aktualisierung", spTick));
        final Spinner spInfo = spinner(m, Prefs.INFO_NAMES, Prefs.indexOf(Prefs.INFO_STEPS, Prefs.infoSec(m)));
        box.addView(labeled(m, "Stufe 2 (Infozeile) zurück nach", spInfo));
        final Spinner spEvents = spinner(m, Prefs.EVENTS_NAMES, Prefs.indexOf(Prefs.EVENTS_STEPS, Prefs.eventsSec(m)));
        box.addView(labeled(m, "Stufe 3 (Ereignisliste) zurück nach", spEvents));
        final Switch swVib = Ui.toggle(m, "Vibration bei Tor", Prefs.vibrate(m));
        box.addView(swVib);

        Ui.section(m, box, "Anstehende Spiele (antippen)");
        final Switch swRemind = Ui.toggle(m, "Vormerken", Prefs.remindMode(m));
        box.addView(swRemind);
        final TextView remindHint = Ui.text(m, "", 12, Ui.MUTED, false);
        remindHint.setPadding(0, 0, 0, Ui.dp(m, 6));
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
        swRemind.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                updHint.run();
            }
        });
        final Switch swStart = Ui.toggle(m, "Bei Spielbeginn vibrieren", Prefs.startVibrate(m));
        box.addView(swStart);

        Ui.section(m, box, "Display-Wecker");
        final Switch swWake = Ui.toggle(m, "Standard: für alle Spiele an", Prefs.wakeOn(m));
        box.addView(swWake);
        box.addView(Ui.text(m, "Tor, Strafe, Bully, Drittel- und Spielende erscheinen 8 Sekunden groß über dem "
                + "Sperrbildschirm – nur wenn das Display aus oder gesperrt ist. Das Display geht danach "
                + "mit der normalen Bildschirm-Zeitsperre wieder aus. Einzelne Spiele: beim Vormerken, im Steckbrief "
                + "(Live-Liste lange drücken) oder Floating lange drücken – geht auch, wenn der Standard aus ist.", 12, Ui.MUTED, false));
        final Switch swSound = Ui.toggle(m, "Dezenter Ton dazu (auch über Bluetooth im Auto)", Prefs.wakeSound(m));
        box.addView(swSound);
        box.addView(Ui.text(m, "Kurzes „Ding-Ding“ in Medienlautstärke; laufende Musik wird dafür kurz leiser.", 12, Ui.MUTED, false));
        Button btTest = new Button(m);
        btTest.setText("📱  Test: in 5 Sekunden");
        Ui.pill(btTest, Ui.DARK);
        btTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.saveWake(m, swWake.isChecked(), swSound.isChecked());
                Toast.makeText(m, "Jetzt Display ausschalten – Testmeldung kommt in 5 Sekunden", Toast.LENGTH_LONG).show();
                final android.content.Context app = m.getApplicationContext();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
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
        btLp.topMargin = Ui.dp(m, 6);
        box.addView(btTest, btLp);

        Ui.section(m, box, "Hervorhebung in der Spielliste");
        final Switch swFav = Ui.toggle(m, "Spiele hervorheben, wenn ein Teamname enthält:", Prefs.favOn(m));
        box.addView(swFav);
        final EditText etFav = new EditText(m);
        etFav.setText(Prefs.favTerms(m));
        etFav.setSingleLine(true);
        etFav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(etFav, new LinearLayout.LayoutParams(-1, -2));
        box.addView(Ui.text(m, "mehrere Begriffe mit Komma trennen, z. B.  Neuss, Moers", 12, Ui.MUTED, false));

        Ui.section(m, box, "Livestream");
        final Switch swStream = Ui.toggle(m, "RED+-Link anzeigen, wenn vorhanden", Prefs.streamOn(m));
        box.addView(swStream);
        box.addView(Ui.text(m, "Knopf „Watch on RED+“ in den Spiel-Details und RED+-Logo in den Listen. "
                + "Der Link kommt aus dem EHV-Spielplan; Ansehen über die RED+-Seite (ggf. kostenpflichtig).",
                12, Ui.MUTED, false));

        final Switch[] swUpd = new Switch[1];
        updateSection(m, box, swUpd);

        // v2.95: zwei klar getrennte Aktionen, gleiche Begriffe wie Benachrichtigung und App-Symbol
        Ui.section(m, box, "App");
        LinearLayout qRow = new LinearLayout(m);
        Button btOff = new Button(m);
        btOff.setText("Alles deaktivieren");
        Ui.outline(btOff, Ui.DARK);
        Button btQuit = new Button(m);
        btQuit.setText("App beenden");
        Ui.outline(btQuit, Ui.RED);
        for (Button b : new Button[]{btOff, btQuit}) {
            b.setSingleLine(true);
            b.setPadding(Ui.dp(m, 8), Ui.dp(m, 10), Ui.dp(m, 8), Ui.dp(m, 10));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
        }
        qRow.addView(btOff, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(0, -2, 1f);
        qLp.leftMargin = Ui.dp(m, 8);
        qRow.addView(btQuit, qLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = Ui.dp(m, 4);
        rowLp.bottomMargin = Ui.dp(m, 8);
        box.addView(qRow, rowLp);
        box.addView(Ui.text(m, "„Alles deaktivieren“: alle Floatings und Vormerkungen aus, die App bleibt offen. "
                + "„App beenden“: zusätzlich die App schließen.", 12, Ui.MUTED, false));

        final AlertDialog dlg = new AlertDialog.Builder(m)
                .setView(Ui.withScrollHint(sv))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Prefs.save(m,
                                Prefs.LIST_STEPS[spList.getSelectedItemPosition()],
                                Prefs.TICK_STEPS[spTick.getSelectedItemPosition()],
                                swVib.isChecked(), swFav.isChecked(), etFav.getText().toString().trim());
                        Prefs.saveUpcoming(m, swRemind.isChecked(), swStart.isChecked());
                        Update.setAuto(m, swUpd[0].isChecked());
                        Prefs.setStreamOn(m, swStream.isChecked());
                        Prefs.saveWake(m, swWake.isChecked(), swSound.isChecked());
                        Prefs.saveFloat(m,
                                Prefs.INFO_STEPS[spInfo.getSelectedItemPosition()],
                                Prefs.EVENTS_STEPS[spEvents.getSelectedItemPosition()],
                                swMin.isChecked());
                        m.optionsSaved();
                        Toast.makeText(m, "Optionen gespeichert", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
        Ui.dialog(dlg);
        btOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                m.deactivateAll(true);
            }
        });
        btQuit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                m.quitApp();
            }
        });
    }

    /** Abschnitt „Updates“: Status, täglich prüfen, Jetzt prüfen / Download */
    private static void updateSection(final MainActivity m, LinearLayout box, final Switch[] autoOut) {
        Ui.section(m, box, "Updates");
        final TextView st = Ui.text(m, "", 13, Ui.TEXT, false);
        box.addView(st);
        final Switch swAuto = Ui.toggle(m, "Täglich automatisch prüfen (es wird nichts automatisch installiert)", Update.auto(m));
        box.addView(swAuto);
        autoOut[0] = swAuto;
        LinearLayout row = new LinearLayout(m);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        final Button chk = new Button(m);
        chk.setText("Jetzt prüfen");
        Ui.pill(chk, Ui.DARK);
        final Button dl = new Button(m);
        dl.setText("⬆ Download");
        Ui.pill(dl, Ui.GREEN);
        // v2.82: beide Knöpfe gleich breit, einzeilig
        for (Button b : new Button[]{chk, dl}) {
            b.setSingleLine(true);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setPadding(Ui.dp(m, 8), Ui.dp(m, 10), Ui.dp(m, 8), Ui.dp(m, 10));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
        }
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                boolean avail = Update.available(m);
                String l = Update.latest(m);
                st.setText((Update.isBusy() ? "Prüfe …" : avail ? "Version " + l + " ist verfügbar"
                        : l.isEmpty() ? "Noch nicht geprüft" : "Aktuell (neueste Version " + l + ")")
                        + "\nInstalliert: " + Update.installed(m) + " · zuletzt geprüft: " + Update.lastText(m));
                st.setTextColor(avail ? Ui.GREEN : Ui.MUTED);
                dl.setVisibility(avail ? View.VISIBLE : View.GONE);
                chk.setEnabled(!Update.isBusy());
                m.invalidateOptionsMenu();
            }
        };
        chk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Update.check(m, new Update.Done() {
                    @Override
                    public void done(boolean ok) {
                        show.run();
                        if (!ok) {
                            st.setText("Update-Server nicht erreichbar – Internet prüfen");
                            st.setTextColor(Ui.RED);
                        }
                    }
                });
                show.run();
            }
        });
        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Update.openDownload(m);
            }
        });
        row.addView(chk, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = Ui.dp(m, 8);
        row.addView(dl, lp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(m, 4);
        box.addView(row, rlp);
        show.run();
    }

    private static Spinner spinner(MainActivity m, String[] names, int sel) {
        Spinner sp = new Spinner(m);
        ArrayAdapter<String> ad = new ArrayAdapter<>(m, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel);
        return sp;
    }

    private static LinearLayout labeled(MainActivity m, String label, View v) {
        LinearLayout row = new LinearLayout(m);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(m, label, 14, Ui.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(Ui.dp(m, 160), -2));
        return row;
    }
}
