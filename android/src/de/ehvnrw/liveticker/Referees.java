package de.ehvnrw.liveticker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schiedsrichter-Einteilungen von https://www.esrw.de/einteilungen.html (Eishockey-Schiedsrichter im
 * Westen e.V., oeffentliche Seite). Aufbau am 06.11.2026 geprueft: Tabelle "einteilung", je Spiel eine
 * Zeile mit <time datetime="2026-11-06T20:00:00+01:00">, Begegnung "Liga: Heim - Gast", Spalten HSR und (L)SR.
 * Die Seite wird hoechstens alle 30 Minuten geladen; gefundene Namen bleiben je Spiel gemerkt,
 * auch wenn das Spiel spaeter von der Seite verschwindet.
 */
final class Referees {
    static final String URL_PAGE = "https://www.esrw.de/einteilungen.html";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;

    private static final class Row {
        String date, time, home, away, hsr, lsr;
    }

    private static List<Row> rows = new ArrayList<>();
    private static long loadedAt = 0, failedAt = 0;
    private static final Map<String, String> found = new HashMap<>();

    private Referees() {
    }

    /** Text fuer ein Spiel, z. B. "HSR Steven, Patrick · LSR Heffler, Philipp / Kudal, Lars", sonst "". Nicht im UI-Thread aufrufen. */
    static synchronized String forGame(Game g) {
        String known = found.get(g.id);
        if (known != null) return known;
        long now = System.currentTimeMillis();
        if ((rows.isEmpty() || now - loadedAt > CACHE_MS) && now - failedAt > RETRY_MS) load();
        Row r = match(g);
        if (r == null) return "";
        String txt;
        if (r.hsr.isEmpty()) txt = "SR " + r.lsr;              // Zwei-Mann-System: beide stehen unter (L)SR
        else if (r.lsr.isEmpty()) txt = "HSR " + r.hsr;
        else txt = "HSR " + r.hsr + " · LSR " + r.lsr;
        if (r.hsr.isEmpty() && r.lsr.isEmpty()) return "";
        found.put(g.id, txt);
        return txt;
    }

    private static void load() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL_PAGE).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "EHV-Live (privat)");
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 2_000_000) bo.write(buf, 0, n);
            in.close();
            rows = parse(bo.toString("UTF-8"));
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            failedAt = System.currentTimeMillis();
        }
    }

    private static final Pattern ROW = Pattern.compile("<tr class=\"einteilung_spiel.*?</tr>", Pattern.DOTALL);
    private static final Pattern DT = Pattern.compile("datetime=\"(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}:\\d{2})");
    private static final Pattern CELL = Pattern.compile("<td data-title=\"([^\"]*)\">(.*?)</td>", Pattern.DOTALL);

    static List<Row> parse(String html) {
        List<Row> out = new ArrayList<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            String tr = m.group();
            Matcher d = DT.matcher(tr);
            if (!d.find()) continue;
            Row r = new Row();
            r.date = d.group(3) + "." + d.group(2) + "." + d.group(1);
            r.time = d.group(4);
            r.home = r.away = r.hsr = r.lsr = "";
            Matcher c = CELL.matcher(tr);
            while (c.find()) {
                String title = c.group(1), val = clean(c.group(2));
                if (title.equals("Begegnung")) {
                    int colon = val.indexOf(": ");
                    String teams = colon >= 0 ? val.substring(colon + 2) : val;
                    int dash = teams.indexOf(" - ");
                    if (dash < 0) continue;
                    r.home = teams.substring(0, dash).trim();
                    r.away = teams.substring(dash + 3).trim();
                } else if (title.equals("HSR")) {
                    r.hsr = val;
                } else if (title.equals("(L)SR")) {
                    r.lsr = val;
                }
            }
            if (!r.home.isEmpty()) out.add(r);
        }
        return out;
    }

    private static String clean(String s) {
        s = s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&#40;", "(").replace("&#41;", ")");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String norm(String s) {
        s = s.toLowerCase(Locale.GERMANY).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return s.replaceAll("[^a-z0-9]", "");
    }

    private static boolean same(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        // Spielgemeinschaft, z. B. "SpG Neusser EV / GSC Moers" enthaelt "Neusser EV" –
        // aber nicht "Neusser EV 1b" vs. "Neusser EV" (sonst falsche Mannschaft)
        boolean spgA = a.contains("/") || a.toLowerCase(Locale.GERMANY).startsWith("spg");
        boolean spgB = b.contains("/") || b.toLowerCase(Locale.GERMANY).startsWith("spg");
        return (spgB && y.contains(x)) || (spgA && x.contains(y));
    }

    /**
     * gleicher Tag + beide Teams + gleiche Uhrzeit, oder gleicher Tag + gleiche Uhrzeit + ein Team.
     * v2.95: Uhrzeit auch beim Vollvergleich – sonst bekam bei zwei Spielen derselben Teams am selben Tag
     * (z. B. Dortmund – Dinslaken 13:30 U13 und 19:00 U20) das zweite Spiel die Schiris des ersten.
     * Fehlt eine Uhrzeit (Spiel oder esrw.de), reichen Tag + beide Teams – aber nur, wenn es genau eine solche Zeile gibt.
     */
    private static Row match(Game g) {
        String date = g.dateText;
        if (date == null || date.isEmpty())
            date = new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new java.util.Date());
        String time = g.scheduledTime == null ? "" : g.scheduledTime.trim();
        if (!time.matches("\\d{1,2}:\\d{2}")) time = ""; // z. B. „n.n.“ = Zeit noch offen
        Row half = null, noTime = null;
        int noTimeCount = 0;
        for (Row r : rows) {
            if (!r.date.equals(date)) continue;
            boolean h = same(r.home, g.homeName), a = same(r.away, g.awayName);
            boolean sameTime = !time.isEmpty() && r.time.trim().equals(time);
            if (h && a && sameTime) return r;
            if (h && a && (time.isEmpty() || r.time.trim().isEmpty())) {
                noTimeCount++;
                if (noTime == null) noTime = r;
            }
            if ((h || a) && sameTime && half == null) half = r;
        }
        if (noTimeCount == 1) return noTime;
        return half;
    }
}
