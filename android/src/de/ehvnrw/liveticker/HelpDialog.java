package de.ehvnrw.liveticker;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** v2.95: Hilfe und Changelog (vorher in MainActivity), Hilfe nach Bereichen neu geordnet. */
final class HelpDialog {
    private HelpDialog() {
    }

    static void show(final Activity a, String version) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 18), Ui.dp(a, 20), Ui.dp(a, 6));

        // Kopf: Logo, Titel, Versions-Chip
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(a, 12), 0, 0, 0);
        titles.addView(Ui.text(a, "EHV Live", 18, Ui.TEXT, true));
        titles.addView(Ui.text(a, "Live-Ticker für den EHV NRW", 12, Ui.MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chip = Ui.text(a, "v" + version, 11, Color.WHITE, true);
        chip.setPadding(Ui.dp(a, 8), Ui.dp(a, 3), Ui.dp(a, 8), Ui.dp(a, 3));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(Ui.TEXT);
        chipBg.setCornerRadius(Ui.dp(a, 10));
        chip.setBackground(chipBg);
        head.addView(chip);
        box.addView(head);
        Ui.stripe(a, box, 14);

        Ui.section(a, box, "Live-Liste");
        item(a, box, "👆", "Laufendes Spiel antippen: Floating-Display starten – nochmal antippen entfernt es (📌 = angepinnt)");
        item(a, box, "⏰", "„Heute noch“ (blass): antippen merkt das Spiel vor, das Floating erscheint bei Spielbeginn (nochmal antippen = aufheben). "
                + "Beim Vormerken fragt die App nach dem Display-Wecker. In den Optionen alternativ: sofort, blass bis zum Bully");
        item(a, box, "📋", "Lange drücken: Steckbrief des Spiels (wie im Spielplan) – mit Aufstellung, Tabelle, Halle und RED+; "
                + "bei vorgemerkten Spielen auch der Display-Wecker");
        item(a, box, "🟨", "Gelb hinterlegt: hervorgehobene Spiele (Standard: Neuss)");
        item(a, box, "↻", "Lädt die Liste sofort neu; sonst automatisch, solange die App offen ist");
        item(a, box, "🌱", "Sparsam: abgefragt werden nur Ligen, die heute spielen (Statuszeile z. B. „5/28 Ligen“); den Spielplan lädt die App höchstens alle 30 Min.");
        item(a, box, "❓", "Läuft heute nichts mehr, führt „Nächste Spiele anzeigen“ in den Spielplan");

        Ui.section(a, box, "Reiter unten");
        item(a, box, "🏒", "„Live“: die heutige Spielliste");
        item(a, box, "📅", "„Spielplan“: „Nächste“ = Spiele der nächsten 7 Tage · „Ergebnisse“ = letzte 7 Tage");
        item(a, box, "⭐", "„Meine“: kommende Spiele und letzte Ergebnisse der hervorgehobenen Teams (Optionen → Hervorhebung)");
        item(a, box, "📊", "„Tabellen“: alle Ligen nach Altersklasse, Ligen der eigenen Teams oben – antippen zeigt die Tabelle");
        item(a, box, "↔", "Nach links/rechts wischen wechselt den Reiter, „Zurück“ führt zur Live-Liste, ↻ lädt neu");

        Ui.section(a, box, "Steckbrief");
        item(a, box, "👆", "Spielplan und Meine: Spiel antippen · Live-Liste: Spiel lange drücken");
        item(a, box, "📄", "Halle, Anstoß, Drittelergebnisse und Torschützen");
        item(a, box, "📍", "Halle antippen öffnet die Route in der Karten-App");
        item(a, box, "🔘", "Knöpfe: „Kalender“ trägt das Spiel ein, „Tabelle“ zeigt die Liga, „Aufstellung“ beide Teams (sobald eingetragen)");

        Ui.section(a, box, "Tabelle und Statistik");
        item(a, box, "📊", "Tabelle einer Liga: oben umschalten oder wischen zwischen Tabelle · Scorer · Torhüter · Strafen "
                + "(gesamte Liga, wie auf ehv-nrw.de). Spieler hervorgehobener Teams sind gelb markiert.");
        item(a, box, "☆", "Aufstellung: Stern mit 1, 2 oder 3 vor dem Namen = die drei besten Scorer der Aufstellung "
                + "(Punkte der Saison in dieser Liga). T/A/SM daneben zählen nur das aktuelle Spiel. Lange Namen: Tabelle seitlich schieben.");

        Ui.section(a, box, "Schiedsrichter");
        item(a, box, "\uD83E\uDDD1\u200D\u2696\uFE0F", "App-Symbol lange drücken → „Schiedsrichter“: alle Einteilungen von esrw.de (ca. die nächsten 12 Tage) nach Tagen, "
                + "eigene Teams gelb, Schalter „Nur meine Teams“. Spiele mit › öffnen den Steckbrief. Grün = eingeteilt, grau = noch offen");
        item(a, box, "\uD83C\uDFD2", "Im Floating (Stufe 3) und im Detailfenster stehen die Schiris des Spiels direkt dabei");

        Ui.section(a, box, "Livestream (RED+)");
        item(a, box, "▶", "Hat ein Spiel einen RED+-Stream, steht das RED+-Logo in der Live-, Spielplan- und Meine-Liste – antippen öffnet den Stream. "
                + "Im Steckbrief und im Floating-Detailfenster öffnet der Knopf „Watch on RED+“ das Spiel im Browser. "
                + "Ohne direkten Link öffnet sich das RED+-Tagesprogramm. Abschaltbar in den Optionen unter „Livestream“.");

        Ui.section(a, box, "Floating-Display");
        item(a, box, "↕", "Ziehen verschiebt – die Position wird gespeichert");
        item(a, box, "👆", "Antippen: Stufe 1 (klein) → 2 (Infozeile) → 3 (Ereignisliste, volle Breite) → 1");
        item(a, box, "⏳", "Stufe 2 und 3 fallen von selbst auf Stufe 1 zurück (Standard 3 s bzw. 10 s – einstellbar)");
        item(a, box, "📚", "Mehrere Floatings stehen untereinander: wird eins größer, rutschen die anderen mit. Das oberste zieht den Stapel mit, jedes andere lässt sich herausziehen");
        item(a, box, "📋", "Lange drücken: Stufe 4 – Detailfenster mit allen Ereignissen, Aufstellung, Tabelle und Display-Wecker für dieses Spiel; schließt nur über „Schließen“");
        item(a, box, "✕", "Nach unten auf das ✕ ziehen: schließen");
        item(a, box, "🚨", "Bei einem Tor: Stand kurz gelb und „TOR!“");
        item(a, box, "⏱", "Aktualisierung alle 15 Sekunden");

        Ui.section(a, box, "Kürzel in der Ereignisliste");
        item(a, box, "2'", "Strafe mit Minuten; Name in Klammern = abgesessen von");
        item(a, box, "PP", "Tor in Überzahl (Powerplay)");
        item(a, box, "SH", "Tor in Unterzahl");
        item(a, box, "EN", "Tor ins leere Tor");
        item(a, box, "PS", "Penaltyschuss");
        item(a, box, "🧑‍⚖️", "Schiri: HSR = Hauptschiedsrichter, LSR = Linienrichter, SR = beide Schiris im Zwei-Mann-System – "
                + "aus den Einteilungen auf esrw.de, sofern dort eingetragen (oben in Stufe 3 und im Detailfenster)");

        Ui.section(a, box, "Display-Wecker");
        item(a, box, "📱", "In den Optionen einschalten: Bei Tor, Strafe, Bully, Drittel- und Spielende geht das "
                + "Display an und zeigt die Meldung 8 Sekunden über dem Sperrbildschirm – ideal im Auto-Halter");
        item(a, box, "👆", "Tippen schließt die Meldung sofort. Gilt für Spiele mit Floating oder Vormerkung");
        item(a, box, "🔔", "Optional dezenter Ton – läuft wie eine Navi-Ansage auch über Bluetooth im Auto");
        item(a, box, "🔔", "Je Spiel an/aus – auch wenn der Standard aus ist: vor dem Bully beim Vormerken oder im Steckbrief (Live-Liste lange drücken), "
                + "danach im Floating-Detailfenster. 🔔 in Liste und Infozeile = aktiv");

        Ui.section(a, box, "Deaktivieren und Beenden");
        item(a, box, "⏸", "„Alles deaktivieren“ entfernt alle Floatings und Vormerkungen, die App bleibt offen – "
                + "in den Optionen (⚙) ganz unten, in der Benachrichtigung oder am App-Symbol (lange drücken, ohne die App zu öffnen)");
        item(a, box, "🛑", "„App beenden“ (Optionen ganz unten) deaktiviert alles und schließt die App. "
                + "Gibt es Vormerkungen, fragt die App, ob sie bleiben sollen");
        item(a, box, "📲", "App-Symbol lange drücken: außerdem direkt „Meine Spiele“, „Tabellen“ oder „Schiedsrichter“");

        Ui.section(a, box, "Optionen (Zahnrad oben)");
        item(a, box, "⏱", "Intervalle für Spielliste und Floating-Display");
        item(a, box, "📳", "Vibration bei Tor und bei Spielbeginn");
        item(a, box, "🔎", "Hervorhebung: Suchbegriffe für Teamnamen, mehrere mit Komma");

        Ui.section(a, box, "Berechtigungen");
        item(a, box, "🪟", "„Über anderen Apps einblenden“ – nötig für das Floating-Display");
        item(a, box, "🔔", "Benachrichtigungen – optional, zeigt den Stand in der Statusleiste");

        Ui.section(a, box, "Updates");
        item(a, box, "⬆", "Die App prüft höchstens einmal am Tag, ob es eine neue Version gibt (in den Optionen abschaltbar). "
                + "Dann erscheint oben „⬆ Update“ – Herunterladen öffnet die APK im Browser, installiert wird nur von dir");

        Ui.section(a, box, "Datenquelle");
        TextView src = Ui.text(a, "ehv-nrw.de / hockeydata.net, Schiedsrichter-Einteilungen von esrw.de · "
                + "inoffizielle App ohne Verbindung zum EHV NRW oder den Schiedsrichtern im Westen", 12, Ui.MUTED, false);
        src.setPadding(0, Ui.dp(a, 2), 0, 0);
        box.addView(src);

        Button btLog = new Button(a);
        btLog.setText("📜  Changelog – was ist neu?");
        Ui.outline(btLog, Ui.GREEN);
        btLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changelog(a);
            }
        });
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-2, -2);
        logLp.gravity = Gravity.CENTER_HORIZONTAL;
        logLp.topMargin = Ui.dp(a, 16);
        box.addView(btLog, logLp);

        // Credits
        LinearLayout.LayoutParams lnLp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 1));
        lnLp.topMargin = Ui.dp(a, 16);
        lnLp.bottomMargin = Ui.dp(a, 10);
        box.addView(Ui.colorBlock(a, Ui.LINE), lnLp);
        TextView credits = Ui.text(a, "Gebaut von Justus Jonas & Michael Nowak\n– mit Claude an der Bande 🏒", 12, Ui.MUTED, false);
        credits.setGravity(Gravity.CENTER);
        credits.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        box.addView(credits, new LinearLayout.LayoutParams(-1, -2));
        TextView more = Ui.text(a, "Mehr Projekte und Downloads: cappy0815.github.io", 12, Ui.MUTED, false);
        more.setGravity(Gravity.CENTER);
        box.addView(more, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        Ui.dialog(new AlertDialog.Builder(a)
                .setView(Ui.withScrollHint(sv))
                .setPositiveButton("Alles klar", null)
                .show());
    }

    private static void item(Activity a, LinearLayout box, String sym, String body) {
        Ui.item(a, box, sym, body);
    }

    // ------------------------------------------------------------------ Changelog
    private static final String[][] CHANGELOG = {
            {"2.96", "App-Symbol lange drücken → „Schiedsrichter“: alle Einteilungen von esrw.de nach Tagen, eigene Teams gelb, "
                    + "„Nur meine Teams“, Antippen öffnet den Steckbrief\n"
                    + "„Spielplan“ weicht dafür im Schnellmenü (die meisten Launcher zeigen höchstens 4 Einträge) – bleibt als Reiter unten"},
            {"2.95", "Aufgeräumt: Spiel in der Live-Liste lange drücken öffnet jetzt den Steckbrief (mit Aufstellung, Tabelle, RED+, "
                    + "bei Vormerkungen Display-Wecker)\n"
                    + "Einheitlich „Alles deaktivieren“ (Floatings und Vormerkungen aus) und „App beenden“, beides in den Optionen\n"
                    + "Hilfe neu geordnet, Live-Liste mit ↻ wie die anderen Reiter\n"
                    + "Schiedsrichter: bei zwei Spielen derselben Teams am selben Tag zählt jetzt auch die Uhrzeit"},
            {"2.94", "App-Symbol lange drücken: „Alles deaktivieren“, „Meine Spiele“, „Spielplan“, „Tabellen“"},
            {"2.93", "Display-Wecker schon beim Vormerken je Spiel einstellbar (Abfrage beim Vormerken, "
                    + "vorgemerktes Spiel lange drücken), 🔔 in der Liste"},
            {"2.92", "Scorer/Torhüter/Strafen: Name zweizeilig mit Trikotnummer und Team, kein seitliches Schieben mehr – Wischen wechselt wieder sauber die Seite"},
            {"2.91", "Topscorer-Stern: Top 3 der aufgestellten Spieler (vorher des ganzen Kaders), Stern größer\n"
                    + "Aufstellung und Statistik seitlich scrollbar, Namen vollständig"},
            {"2.9", "Tabelle mit wischbaren Seiten Scorer · Torhüter · Strafen der ganzen Liga\n"
                    + "Aufstellung: Topscorer 1–3 je Team mit Stern markiert"},
            {"2.82", "Optionen: Knöpfe „Jetzt prüfen“/„Download“ und Speichern/Abbrechen sauber nebeneinander, "
                    + "„App beenden“ als eigener Knopf ganz unten"},
            {"2.81", "RED+-Logo auch in Live-, Spielplan- und Meine-Liste (antippen öffnet den Stream)\n"
                    + "Liga ohne „Hauptrunde 26/27“, Halle im Spielplan in eigener Zeile"},
            {"2.8", "RED+-Livestream: Knopf „Watch on RED+“ in den Spiel-Details, „▶ RED+“ in den Listen\n"
                    + "Abschaltbar in den Optionen unter „Livestream“"},
            {"2.71", "Reiterleiste mit modernen Linien-Symbolen, aktiver Reiter grün hinterlegt"},
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

    static void changelog(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 18), Ui.dp(a, 20), Ui.dp(a, 6));
        box.addView(Ui.text(a, "Changelog", 18, Ui.TEXT, true));
        for (String[] e : CHANGELOG) {
            Ui.section(a, box, "Version " + e[0]);
            for (String l : e[1].split("\n")) Ui.item(a, box, "•", l);
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        Ui.dialog(new AlertDialog.Builder(a).setView(Ui.withScrollHint(sv)).setPositiveButton("Schließen", null).show());
    }
}
