package de.ehvnrw.liveticker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Datenzugriff wie auf https://ehv-nrw.de/leagues/next-games/ (hockeydata.net-Widgets).
 * Am 17.10.2026 gegen Live-Daten geprueft.
 */
public final class Api {
    private static final String PAGE_URL = "https://ehv-nrw.de/leagues/next-games/";
    private static final String API_BASE = "https://api.hockeydata.net/data/ebel/";
    private static final String WO_LIVE = "%7B%22noScorers%22%3Atrue%2C%22live%22%3Atrue%7D";
    // ganzer Spielplan (die API hat keinen Datumsfilter -> heutige Spiele werden lokal herausgesucht)
    private static final String WO_ALL = "%7B%22live%22%3Afalse%2C%22semantic%22%3Atrue%2C%22noScorers%22%3Atrue%7D";
    private static final long UPCOMING_MS = 10 * 60 * 1000L;   // EHV-Seite nach Fehlschlag erneut lesen
    private static final long SEASON_MS = 30 * 60 * 1000L;     // v2.5: Saisonplan nur alle 30 Min. (vorher 10)

    // Fallback (Stand 17.10.2026), falls die EHV-Seite nicht lesbar ist
    private static volatile String apiKey = "f4b71c8702bd3a6eadc89becf6da5a4e";
    private static volatile List<String> divisions = Arrays.asList(
            "21419", "21420", "21623", "21679", "22359", "21691", "21692", "21693", "22386", "22387",
            "22388", "21680", "21681", "21627", "21628", "21632", "21633", "21637", "21648", "21652",
            "21656", "21660", "21664", "21668", "21669", "21673", "21674", "21675");
    private static volatile boolean configLoaded = false;
    private static volatile long configTriedAt = 0;
    /** Anzahl Ligen, die beim letzten Abruf nicht antworteten (0 = alles gut) */
    static volatile int lastFailed = 0;

    private static final Map<String, Bitmap> LOGOS = new HashMap<>();

    private Api() {
    }

    // ------------------------------------------------------------------ HTTP
    static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EHV-LiveTicker/1.0");
        c.setRequestProperty("Referer", "https://ehv-nrw.de/");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }

    static JSONObject apiGet(String method, String query) throws Exception {
        String s = getText(API_BASE + method + "?apiKey=" + apiKey + "&lang=de&referer=ehv-nrw.de&" + query).trim();
        if (!s.startsWith("{")) { // JSONP-Huelle entfernen, falls vorhanden
            int a = s.indexOf('('), b = s.lastIndexOf(')');
            if (a >= 0 && b > a) s = s.substring(a + 1, b);
        }
        JSONObject o = new JSONObject(s);
        if (o.optInt("statusId", 0) != 1) throw new Exception("API-Status " + o.optString("statusMsg"));
        return o;
    }

    // ------------------------------------------------------------------ Konfiguration
    static synchronized void loadConfig() {
        if (configLoaded) return;
        // EHV-Seite nicht erreichbar -> Fallback-Werte nutzen und erst in 10 Min. erneut versuchen
        if (System.currentTimeMillis() - configTriedAt < UPCOMING_MS) return;
        configTriedAt = System.currentTimeMillis();
        try {
            String html = getText(PAGE_URL);
            Matcher mk = Pattern.compile("\"apiKey\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(html);
            Matcher md = Pattern.compile("\"divisionId\"\\s*:\\s*\"(\\d+)\"").matcher(html);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            while (md.find()) ids.add(md.group(1));
            if (mk.find() && !ids.isEmpty()) {
                apiKey = mk.group(1);
                divisions = new ArrayList<>(ids);
            }
            configLoaded = true;
        } catch (Exception ignored) {
            // Fallback-Werte bleiben aktiv
        }
    }

    static int divisionCount() {
        return divisions.size();
    }

    // ------------------------------------------------------------------ Laufende Spiele
    /** Fragt alle Ligen parallel ab und liefert nur Spiele mit gameStatus 1 (laeuft). */
    static List<Game> fetchLiveGames() throws Exception {
        loadConfig();
        // Vorab-Test mit einer Liga: antwortet der Server nicht, sofort melden statt 28x Zeitlimit abzuwarten
        try {
            apiGet("Schedule", "divisionId=" + divisions.get(0) + "&widgetOptions=" + WO_LIVE);
        } catch (Exception e) {
            lastFailed = divisions.size();
            throw new Exception("Server nicht erreichbar (hockeydata.net)");
        }
        // v2.5: nur Ligen mit heutigen Spielen abfragen (Saisonplan aus dem 30-Min.-Zwischenspeicher)
        List<String> ask = todayDivisions();
        if (ask == null) ask = divisions;
        lastAsked = ask == divisions ? -1 : ask.size();
        if (ask.isEmpty()) {
            lastFailed = 0;
            return new ArrayList<>();
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<List<Game>>> futures = new ArrayList<>();
        for (final String div : ask) {
            futures.add(pool.submit(new Callable<List<Game>>() {
                @Override
                public List<Game> call() throws Exception {
                    List<Game> out = new ArrayList<>();
                    JSONObject o = apiGet("Schedule", "divisionId=" + div + "&widgetOptions=" + WO_LIVE);
                    JSONObject data = o.optJSONObject("data");
                    JSONArray rows = data == null ? null : data.optJSONArray("rows");
                    if (rows == null) return out;
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.optJSONObject(i);
                        if (r != null && r.optInt("gameStatus", -1) == 1) out.add(Game.fromScheduleRow(r));
                    }
                    return out;
                }
            }));
        }
        List<Game> all = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int errors = 0;
        for (Future<List<Game>> f : futures) {
            try {
                for (Game g : f.get()) {
                    if (!g.id.isEmpty() && seen.add(g.id)) all.add(g);
                }
            } catch (Exception e) {
                errors++;
            }
        }
        pool.shutdown();
        lastFailed = errors;
        if (errors == futures.size()) throw new Exception("Server nicht erreichbar (hockeydata.net)");
        return all;
    }

    // ------------------------------------------------------------------ Spielplan (ganze Saison)
    private static volatile List<Game> seasonCache = new ArrayList<>();
    private static volatile long seasonAt = 0;
    /** true, wenn beim letzten Saison-Abruf alle Ligen geantwortet haben */
    private static volatile boolean seasonComplete = false;
    /** v2.5: Anzahl der zuletzt abgefragten Ligen (fuer die Statuszeile), -1 = alle */
    static volatile int lastAsked = -1;

    /**
     * v2.5: Ligen, die laut Saisonplan heute spielen. Nur bei vollstaendigem Saisonplan –
     * sonst null, dann werden sicherheitshalber alle Ligen gefragt.
     */
    private static List<String> todayDivisions() {
        List<Game> s = season(false);
        if (!seasonComplete || s.isEmpty()) return null;
        final String today = new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(new java.util.Date());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Game g : s) if (today.equals(g.dateText) && !g.divisionId.isEmpty()) ids.add(g.divisionId);
        return new ArrayList<>(ids);
    }

    /**
     * Alle Spiele aller Ligen der Saison (jeder Status), nach Anstoss sortiert.
     * Grosse Abfrage (~3 MB) -> 30 Minuten zwischengespeichert, ausser force = true.
     */
    private static final Object SEASON_LOCK = new Object();

    static List<Game> season(boolean force) {
        // v2.7: mehrere Reiter fragen evtl. gleichzeitig -> nur ein Abruf, die anderen bekommen den Speicher
        long asked = System.currentTimeMillis();
        synchronized (SEASON_LOCK) {
            if (force && seasonAt >= asked && !seasonCache.isEmpty()) return seasonCache;
            return seasonLoad(force);
        }
    }

    private static List<Game> seasonLoad(boolean force) {
        loadConfig();
        if (!force && !seasonCache.isEmpty() && System.currentTimeMillis() - seasonAt < SEASON_MS) return seasonCache;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<List<Game>>> futures = new ArrayList<>();
        for (final String div : divisions) {
            futures.add(pool.submit(new Callable<List<Game>>() {
                @Override
                public List<Game> call() throws Exception {
                    List<Game> out = new ArrayList<>();
                    JSONObject o = apiGet("Schedule", "divisionId=" + div + "&widgetOptions=" + WO_ALL);
                    JSONObject data = o.optJSONObject("data");
                    JSONArray rows = data == null ? null : data.optJSONArray("rows");
                    if (rows == null) return out;
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.optJSONObject(i);
                        if (r != null) out.add(Game.fromScheduleRow(r));
                    }
                    return out;
                }
            }));
        }
        List<Game> all = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int errors = 0;
        for (Future<List<Game>> f : futures) {
            try {
                for (Game g : f.get()) if (!g.id.isEmpty() && seen.add(g.id)) all.add(g);
            } catch (Exception e) {
                errors++;
            }
        }
        pool.shutdown();
        java.util.Collections.sort(all, new java.util.Comparator<Game>() {
            @Override
            public int compare(Game a, Game b) {
                return a.startMs < b.startMs ? -1 : (a.startMs > b.startMs ? 1 : 0);
            }
        });
        if (errors < futures.size()) { // bei Totalausfall alten Stand behalten
            seasonCache = all;
            seasonAt = System.currentTimeMillis();
            seasonComplete = errors == 0;
            return all;
        }
        return seasonCache;
    }

    /** Heute noch anstehende Spiele (gameStatus 0), nach Uhrzeit sortiert – aus dem Saison-Spielplan. */
    static List<Game> upcomingToday(boolean force) {
        final String today = new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).format(new java.util.Date());
        List<Game> out = new ArrayList<>();
        for (Game g : season(force)) {
            if (g.status != 0 || !today.equals(g.dateText)) continue;
            g.type = 1;
            out.add(g);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Game>() {
            @Override
            public int compare(Game a, Game b) {
                return a.scheduledTime.compareTo(b.scheduledTime);
            }
        });
        return out;
    }

    /** Tabelle einer Liga (Abfrage "Standings", am 19.10.2026 geprueft). */
    static List<JSONObject> standings(String divisionId) throws Exception {
        loadConfig();
        JSONObject o = apiGet("Standings", "divisionId=" + divisionId);
        JSONArray rows = null;
        JSONObject data = o.optJSONObject("data");
        if (data != null) rows = data.optJSONArray("rows");
        if (rows == null) rows = o.optJSONArray("data");
        List<JSONObject> out = new ArrayList<>();
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Kompletter Spielbericht (fuer die Aufstellung). */
    static JSONObject reportData(String gameId) throws Exception {
        loadConfig();
        JSONObject data = apiGet("GetGameReport", "gameId=" + gameId).optJSONObject("data");
        if (data == null) throw new Exception("Keine Spieldaten");
        return data;
    }

    /** Aktualisiert ein Spiel ueber GetGameReport. */
    static void refreshGame(Game g) throws Exception {
        loadConfig();
        JSONObject o = apiGet("GetGameReport", "gameId=" + g.id);
        JSONObject data = o.optJSONObject("data");
        JSONObject gd = data == null ? null : data.optJSONObject("gameData");
        if (gd == null) throw new Exception("Keine Spieldaten");
        g.updateFromReport(gd);
        g.events = buildEvents(data, g.homeShort, g.awayShort);
        buildSpoken(data, g);
    }

    // ------------------------------------------------------------------ Texte fuer den Display-Wecker
    /** "THELEN" -> "Thelen", "ALTHÜSER EPPINK" -> "Althüser Eppink" */
    private static String nice(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char ch : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            up = ch == ' ' || ch == '-';
        }
        return b.toString();
    }

    private static String spokenPlayer(JSONObject p) {
        if (p == null) return "";
        String nr = p.isNull("playerJerseyNr") ? "" : p.optString("playerJerseyNr", "");
        String name = nice(p.optString("playerLastname", ""));
        return nr.isEmpty() ? name : "#" + nr + " " + name;
    }

    /**
     * Vorlagengeber eines Tores. Die Feldnamen sind in der API nicht dokumentiert und konnten am
     * 25.10.2026 nicht gegen Live-Daten geprueft werden -> alle Felder mit "assist" im Namen einsammeln
     * (Objekt oder Array, alphabetisch: assist1/assist2 bzw. assistBy/assist2By landen in der richtigen Reihenfolge).
     */
    private static List<JSONObject> assistPlayers(JSONObject m) {
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> it = m.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.toLowerCase().contains("assist")) keys.add(k);
        }
        java.util.Collections.sort(keys);
        List<JSONObject> out = new ArrayList<>();
        for (String k : keys) {
            JSONObject o = m.optJSONObject(k);
            if (o != null) out.add(o);
            JSONArray a = m.optJSONArray(k);
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private static String assists(JSONObject m) {
        List<String> names = new ArrayList<>();
        for (JSONObject p : assistPlayers(m)) {
            String n = spokenPlayer(p);
            if (!n.isEmpty()) names.add(n);
        }
        if (names.isEmpty()) return "";
        return names.size() == 1 ? names.get(0) : names.get(0) + " und " + names.get(1);
    }

    /** juengstes Tor / juengste Strafe als Anzeigetext + Strafenanzahl */
    static void buildSpoken(JSONObject data, Game g) {
        int bestGoal = -1, bestPen = -1, pens = 0;
        String[] sides = {"home", "away"};
        for (String side : sides) {
            String teamLong = side.equals("home") ? g.homeName : g.awayName;
            String teamShort = side.equals("home") ? g.homeShort : g.awayShort;
            JSONArray goals = data.optJSONArray(side + "Goals");
            if (goals != null) for (int i = 0; i < goals.length(); i++) {
                JSONObject m = goals.optJSONObject(i);
                if (m == null || m.optInt("gameTime", 0) < bestGoal) continue;
                bestGoal = m.optInt("gameTime", 0);
                // z. B. "#77 Thelen\nVorlage #12 Douben\nÜberzahl · 15:25"
                StringBuilder t = new StringBuilder();
                String by = spokenPlayer(m.optJSONObject("scoredBy"));
                if (!by.isEmpty()) t.append(by);
                String as = assists(m);
                if (!as.isEmpty()) t.append(t.length() > 0 ? "\n" : "").append("Vorlage ").append(as);
                String str = m.isNull("gameStrength") ? "" : m.optString("gameStrength", "");
                StringBuilder tail = new StringBuilder();
                if (str.startsWith("PP")) tail.append("Überzahl · ");
                if (str.startsWith("SH")) tail.append("Unterzahl · ");
                if (m.optBoolean("isEmptyNet", false)) tail.append("Empty Net · ");
                if (m.optBoolean("isPenaltyShot", false)) tail.append("Penalty · ");
                tail.append(m.optString("gameTimeFormatted", ""));
                t.append(t.length() > 0 ? "\n" : "").append(tail);
                g.lastGoalLong = t.toString();
                g.lastGoalTeam = teamShort;
            }
            JSONArray pa = data.optJSONArray(side + "Penalties");
            if (pa != null) for (int i = 0; i < pa.length(); i++) {
                JSONObject m = pa.optJSONObject(i);
                if (m == null) continue;
                pens++;
                if (m.optInt("gameTime", 0) < bestPen) continue;
                bestPen = m.optInt("gameTime", 0);
                int min = Math.round(m.optInt("penaltyLength", 0) / 60f);
                String who = spokenPlayer(m.optJSONObject("offender"));
                g.lastPenLong = (who.isEmpty() ? "" : who + "\n") + min + " Minuten · "
                        + m.optString("gameTimeFormatted", "");
                g.lastPenTeam = teamShort;
            }
        }
        g.penCount = pens;
    }

    // ------------------------------------------------------------------ Ereignisse
    private static final class Ev {
        final int sec;
        /** bei gleicher Spielzeit: Strafe (0) war zuerst, Tor (1) danach -> Tor steht oben */
        final int rank;
        final String text;

        Ev(int sec, int rank, String text) {
            this.sec = sec;
            this.rank = rank;
            this.text = text;
        }
    }

    /** Tore + Strafen aus dem Spielbericht, neueste zuerst. Felder am 17.10.2026 gegen Live-Daten geprueft. */
    static List<String> buildEvents(JSONObject data, String homeShort, String awayShort) {
        List<Ev> ev = new ArrayList<>();
        addGoals(ev, data.optJSONArray("homeGoals"), homeShort);
        addGoals(ev, data.optJSONArray("awayGoals"), awayShort);
        addPens(ev, data.optJSONArray("homePenalties"), homeShort);
        addPens(ev, data.optJSONArray("awayPenalties"), awayShort);
        java.util.Collections.sort(ev, new java.util.Comparator<Ev>() {
            @Override
            public int compare(Ev a, Ev b) {
                if (a.sec != b.sec) return b.sec - a.sec;
                return b.rank - a.rank;
            }
        });
        List<String> out = new ArrayList<>();
        for (Ev e : ev) out.add(e.text);
        return out;
    }

    private static String player(JSONObject p) {
        if (p == null) return "";
        String name = p.optString("playerLastname", "");
        String nr = p.isNull("playerJerseyNr") ? "" : p.optString("playerJerseyNr", "");
        return nr.isEmpty() ? name : "#" + nr + " " + name;
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    /*
     * Einheitliches Spaltenraster (v2.4), damit Tore und Strafen Zeile fuer Zeile buendig stehen:
     *   Zeit(6) Art(5) Team(5) Stand(6) Spieler
     *   "15:25 TOR  NEV  2:1   #77 THELEN [PP]"
     *   "      Vorl. #12 DOUBEN, #9 MEIER"        <- nur wenn die API Vorlagen liefert
     *   "12:10 2'   KEV        #4 SCHMITZ"
     */
    private static final String INDENT = "      ";

    private static void addGoals(List<Ev> ev, JSONArray a, String team) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null) continue;
            StringBuilder t = new StringBuilder();
            t.append(pad(m.optString("gameTimeFormatted", ""), 6)).append(pad("TOR", 5))
                    .append(pad(team, 5)).append(pad(m.optString("newScore", ""), 6))
                    .append(player(m.optJSONObject("scoredBy")));
            String str = m.isNull("gameStrength") ? "" : m.optString("gameStrength", "");
            if (!str.isEmpty() && !str.equals("EQ")) t.append(" [").append(str).append(']');
            if (m.optBoolean("isEmptyNet", false)) t.append(" [EN]");
            if (m.optBoolean("isPenaltyShot", false)) t.append(" [PS]");
            StringBuilder as = new StringBuilder();
            for (JSONObject p : assistPlayers(m)) {
                String n = player(p);
                if (n.isEmpty()) continue;
                as.append(as.length() == 0 ? "" : ", ").append(n);
            }
            if (as.length() > 0) t.append('\n').append(INDENT).append("Vorl. ").append(as);
            ev.add(new Ev(m.optInt("gameTime", 0), 1, t.toString()));
        }
    }

    private static void addPens(List<Ev> ev, JSONArray a, String team) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null) continue;
            int min = Math.round(m.optInt("penaltyLength", 0) / 60f);
            String who = player(m.optJSONObject("offender"));
            if (who.isEmpty()) {
                String served = player(m.optJSONObject("servedBy"));
                if (!served.isEmpty()) who = "(" + served + ")";
            }
            String t = pad(m.optString("gameTimeFormatted", ""), 6) + pad(min + "'", 5)
                    + pad(team, 5) + pad("", 6) + who;
            ev.add(new Ev(m.optInt("gameTime", 0), 0, t));
        }
    }

    // ------------------------------------------------------------------ Logos
    /** Laedt ein Team-Logo (mit Cache). Liefert null, wenn kein echtes Logo vorhanden ist. */
    /** Wappen-URL zur Team-ID (gleiches Schema wie homeTeamLogoUrl, am 19.10.2026 geprueft) */
    static String logoUrl(String teamId) {
        return teamId == null || teamId.isEmpty() ? "" : "https://api.hockeydata.net/img/icehockey/ebel/team-logos/" + teamId + ".png";
    }

    static Bitmap logo(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (LOGOS) {
            if (LOGOS.containsKey(url)) return LOGOS.get(url);
        }
        Bitmap bmp;
        try {
            byte[] b = getBytes(url);
            // auf ca. 160 px verkleinert dekodieren – im Spielplan kommen viele Wappen zusammen
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, bo);
            int sample = 1;
            while (bo.outWidth / (sample * 2) >= 160 && bo.outHeight / (sample * 2) >= 160) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            bmp = BitmapFactory.decodeByteArray(b, 0, b.length, o2);
            // hockeydata liefert fuer fehlende Logos ein winziges Platzhalterbild
            if (bmp != null && (bmp.getWidth() < 8 || bmp.getHeight() < 8)) bmp = null;
        } catch (Exception e) {
            return null; // Netzwerkfehler nicht cachen, spaeter erneut versuchen
        }
        synchronized (LOGOS) {
            LOGOS.put(url, bmp);
        }
        return bmp;
    }
}
