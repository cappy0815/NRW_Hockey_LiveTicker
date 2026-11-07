package de.ehvnrw.liveticker;

import android.content.Context;
import android.content.SharedPreferences;

/** Optionen der App (SharedPreferences "settings"). */
public final class Prefs {
    // v2.5: 30-s-Stufe entfaellt (Serverlast), Standard 1 Minute
    public static final int[] LIST_STEPS = {60, 120, 300, 0};
    public static final String[] LIST_NAMES = {"1 Minute", "2 Minuten", "5 Minuten", "aus"};
    public static final int[] TICK_STEPS = {10, 15, 30};
    public static final String[] TICK_NAMES = {"10 Sekunden", "15 Sekunden", "30 Sekunden"};

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Spielliste automatisch aktualisieren (Sekunden, 0 = aus). */
    public static int listSec(Context c) {
        int v = sp(c).getInt("listSec", 60);
        for (int s : LIST_STEPS) if (s == v) return v;
        return 60; // alter Wert (z. B. 30 s aus 2.4) -> neuer Standard
    }

    /** Floating-Display aktualisieren (Sekunden). */
    public static int tickSec(Context c) {
        return sp(c).getInt("tickSec", 15);
    }

    public static boolean vibrate(Context c) {
        return sp(c).getBoolean("vibrate", false);
    }

    /** Anstehende Spiele: true = vormerken (Floating erscheint bei Spielbeginn), false = sofort blass anzeigen. */
    public static boolean remindMode(Context c) {
        return sp(c).getBoolean("remindMode", true);
    }

    public static boolean startVibrate(Context c) {
        return sp(c).getBoolean("startVibrate", true);
    }

    public static void saveUpcoming(Context c, boolean remindMode, boolean startVibrate) {
        sp(c).edit().putBoolean("remindMode", remindMode).putBoolean("startVibrate", startVibrate).apply();
    }

    /** Display-Wecker: bei Ereignissen Bildschirm einschalten und Meldung zeigen */
    public static boolean wakeOn(Context c) {
        return sp(c).getBoolean("wakeOn", false);
    }

    /** dezenter Ton zur Display-Meldung */
    public static boolean wakeSound(Context c) {
        return sp(c).getBoolean("wakeSound", false);
    }

    public static void saveWake(Context c, boolean on, boolean sound) {
        sp(c).edit().putBoolean("wakeOn", on).putBoolean("wakeSound", sound)
                .remove("autoOn").remove("autoLong").apply();
    }

    /** Display-Wecker je Spiel: eigene Einstellung (an/aus) geht vor, sonst Standard aus den Optionen */
    public static boolean wakeForGame(Context c, String id) {
        if (id == null) return wakeOn(c);
        if (sp(c).getStringSet("wakeOnIds", new java.util.HashSet<String>()).contains(id)) return true;
        if (sp(c).getStringSet("wakeOff", new java.util.HashSet<String>()).contains(id)) return false;
        return wakeOn(c);
    }

    public static void setWakeForGame(Context c, String id, boolean on) {
        java.util.Set<String> onS = new java.util.HashSet<>(sp(c).getStringSet("wakeOnIds", new java.util.HashSet<String>()));
        java.util.Set<String> offS = new java.util.HashSet<>(sp(c).getStringSet("wakeOff", new java.util.HashSet<String>()));
        if (onS.size() + offS.size() > 80) { // alte Spiele nicht ewig sammeln
            onS.clear();
            offS.clear();
        }
        onS.remove(id);
        offS.remove(id);
        (on ? onS : offS).add(id);
        sp(c).edit().putStringSet("wakeOnIds", onS).putStringSet("wakeOff", offS).apply();
    }

    // ------------------------------------------------------------------ Floating
    public static final int[] INFO_STEPS = {3, 5, 10, 20, 0};
    public static final String[] INFO_NAMES = {"3 Sekunden", "5 Sekunden", "10 Sekunden", "20 Sekunden", "nie"};
    public static final int[] EVENTS_STEPS = {5, 10, 20, 30, 0};
    public static final String[] EVENTS_NAMES = {"5 Sekunden", "10 Sekunden", "20 Sekunden", "30 Sekunden", "nie"};

    /** Stufe 2 (Infozeile) faellt nach x Sekunden auf Stufe 1 zurueck (0 = nie) */
    public static int infoSec(Context c) {
        return sp(c).getInt("infoSec", 3);
    }

    /** Stufe 3 (Ereignisliste) faellt nach x Sekunden auf Stufe 1 zurueck (0 = nie) */
    public static int eventsSec(Context c) {
        return sp(c).getInt("eventsSec", 10);
    }

    /** App nach dem Anpinnen eines laufenden Spiels in den Hintergrund legen (nicht beim Vormerken) */
    public static boolean minimizeOnPin(Context c) {
        return sp(c).getBoolean("minimizeOnPin", true);
    }

    public static void saveFloat(Context c, int infoSec, int eventsSec, boolean minimize) {
        sp(c).edit().putInt("infoSec", infoSec).putInt("eventsSec", eventsSec)
                .putBoolean("minimizeOnPin", minimize).remove("collapseSec").remove("detailSec").apply();
    }

    /** v2.8: Livestream-Knopf (RED+) im Steckbrief/Detailfenster und Hinweis in den Listen */
    public static boolean streamOn(Context c) {
        return sp(c).getBoolean("streamOn", true);
    }

    public static void setStreamOn(Context c, boolean on) {
        sp(c).edit().putBoolean("streamOn", on).apply();
    }

    public static boolean favOn(Context c) {
        return sp(c).getBoolean("favOn", true);
    }

    public static String favTerms(Context c) {
        return sp(c).getString("favTerms", "Neuss");
    }

    public static void save(Context c, int listSec, int tickSec, boolean vibrate, boolean favOn, String favTerms) {
        sp(c).edit().putInt("listSec", listSec).putInt("tickSec", tickSec).putBoolean("vibrate", vibrate)
                .putBoolean("favOn", favOn).putString("favTerms", favTerms).apply();
    }

    /** true, wenn einer der Teamnamen einen der Suchbegriffe enthaelt (Gross/Klein egal, Begriffe mit Komma). */
    public static boolean isFav(Context c, Game g) {
        return isFavName(c, g.homeName + "|" + g.awayName);
    }

    public static boolean isFavName(Context c, String text) {
        if (!favOn(c)) return false;
        String names = text.toLowerCase(java.util.Locale.GERMANY);
        for (String t : favTerms(c).split(",")) {
            String term = t.trim().toLowerCase(java.util.Locale.GERMANY);
            if (!term.isEmpty() && names.contains(term)) return true;
        }
        return false;
    }

    public static int indexOf(int[] steps, int value) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == value) return i;
        return 0;
    }
}
