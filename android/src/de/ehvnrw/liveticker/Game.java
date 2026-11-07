package de.ehvnrw.liveticker;

import org.json.JSONException;
import org.json.JSONObject;

/** Ein Spiel mit den Daten, die Liste und Floating-Display brauchen. */
public class Game {
    public String id = "";
    public String league = "";
    public String division = "";
    public String homeName = "", awayName = "";
    public String homeShort = "", awayShort = "";
    public String homeLogo = "", awayLogo = "";
    public int homeScore, awayScore;
    public String phase = "";
    public int liveTime;
    public int status = -1; // 0 = nicht begonnen, 1 = laeuft, 4 = beendet
    public String scheduledTime = "";
    /** 0 = live, 1 = heute anstehend, 2 = Trennzeile (nur fuer die Liste) */
    public int type = 0;
    /** Tore und Strafen, neueste zuerst (nur aus GetGameReport, wird nicht serialisiert). */
    public java.util.List<String> events = new java.util.ArrayList<>();
    /** fuer den Display-Wecker: Anzahl Strafen, letzte Tor-/Strafen-Beschreibung (ausfuehrlich) */
    public int penCount = -1;
    public String lastGoalLong = "", lastPenLong = "", lastGoalTeam = "", lastPenTeam = "";
    /** Spielplan-Daten (am 19.10.2026 gegen die Schedule-Abfrage geprueft) */
    public String divisionId = "";
    public long startMs;          // gameUtcTimestamp (echte UTC-Millisekunden)
    public String dateText = "";  // "24.10.2026"
    /** v2.6: Schiedsrichter von esrw.de, z. B. "HSR … · LSR …" ("" = nicht gefunden) */
    public String refs = "";
    public String arenaShort = "", arenaLong = "", arenaAddress = "";
    public String periods = "";   // "(0:0,0:1,0:3)"
    public String homeScorers = "", awayScorers = "";
    public boolean overtime, shootout;
    // v2.8: Livestream aus "streams"/"broadcasters" der Schedule-Abfrage (z. B. RED+)
    public String streamUrl = "";   // direkter Link zum Spiel (redirect.red.sport/…)
    public String streamDay = "";   // nur Tagesprogramm des Senders (wenn kein direkter Link)
    public String streamName = "";  // "RED+" o. ä.

    public boolean hasStream() {
        return !streamUrl.isEmpty() || !streamDay.isEmpty();
    }

    public boolean isRedPlus() {
        String u = streamUrl.isEmpty() ? streamDay : streamUrl;
        return u.contains("red.sport") || streamName.toUpperCase(java.util.Locale.ROOT).startsWith("RED");
    }

    /** Aus einer Zeile der Schedule-Abfrage. */
    public static Game fromScheduleRow(JSONObject r) {
        Game g = new Game();
        g.id = r.optString("id", "");
        g.league = r.optString("leagueName", "");
        g.division = r.optString("divisionName", "");
        g.homeName = r.optString("homeTeamLongName", "");
        g.awayName = r.optString("awayTeamLongName", "");
        g.homeShort = r.optString("homeTeamShortName", "");
        g.awayShort = r.optString("awayTeamShortName", "");
        g.homeLogo = r.optString("homeTeamLogoUrl", "");
        g.awayLogo = r.optString("awayTeamLogoUrl", "");
        g.homeScore = r.optInt("homeTeamScore", 0);
        g.awayScore = r.optInt("awayTeamScore", 0);
        g.phase = r.isNull("liveTimeGamePhase") ? "" : r.optString("liveTimeGamePhase", "");
        g.liveTime = r.optInt("liveTime", 0);
        g.status = r.optInt("gameStatus", -1);
        g.scheduledTime = r.optBoolean("timeIsToBeDetermined", false) ? "n.n." : r.optString("scheduledTime", "");
        g.divisionId = r.optString("divisionId", "");
        g.startMs = r.optLong("gameUtcTimestamp", 0);
        JSONObject sd = r.optJSONObject("scheduledDate");
        if (sd != null) g.dateText = sd.optString("value", "");
        g.periods = r.isNull("periodResults") ? "" : r.optString("periodResults", "");
        g.overtime = r.optBoolean("isOvertime", false);
        g.shootout = r.optBoolean("isShootOut", false);
        g.homeScorers = scorers(r.optJSONObject("homeScorers"));
        g.awayScorers = scorers(r.optJSONObject("awayScorers"));
        readStream(g, r);
        JSONObject loc = r.optJSONObject("location");
        if (loc != null) {
            g.arenaShort = loc.optString("shortname", "");
            g.arenaLong = loc.optString("longname", "");
            // "address" ist ein JSON-Text: headline1..3, street1/2, zipcode, city, region, country
            try {
                JSONObject a = new JSONObject(loc.optString("address", "{}"));
                String street = a.optString("street1", "").trim();
                String city = (a.optString("zipcode", "").trim() + " " + a.optString("city", "").trim()).trim();
                g.arenaAddress = street.isEmpty() ? city : (city.isEmpty() ? street : street + ", " + city);
            } catch (JSONException ignored) {
            }
        }
        return g;
    }

    /**
     * "streams": Liste von JSON-Texten {"targetUrl":"https://redirect.red.sport/…","imgUrl":"…watch_on_red_plus.png"}.
     * "broadcasters": [{"broadcasterName":"RED+ (DE)","gameBroadcastUrl":"https://red.sport/de-de/schedule?date=…"}].
     * Am 07.11.2026 an echten Spielen geprueft (ECL – NEV: redirect -> red.sport/de-de/livestream/match/…).
     */
    private static void readStream(Game g, JSONObject r) {
        org.json.JSONArray st = r.optJSONArray("streams");
        if (st != null) {
            for (int i = 0; i < st.length() && g.streamUrl.isEmpty(); i++) {
                Object o = st.opt(i);
                JSONObject j = o instanceof JSONObject ? (JSONObject) o : null;
                if (j == null && o instanceof String) {
                    try {
                        j = new JSONObject((String) o);
                    } catch (JSONException ignored) {
                    }
                }
                if (j != null) {
                    String u = j.optString("targetUrl", "").trim();
                    if (u.startsWith("http")) g.streamUrl = u;
                }
            }
        }
        org.json.JSONArray bc = r.optJSONArray("broadcasters");
        if (bc != null && bc.length() > 0) {
            JSONObject b = bc.optJSONObject(0);
            if (b != null) {
                g.streamName = b.optString("broadcasterName", "").replaceAll("\\s*\\(.*\\)$", "").trim();
                String u = b.optString("gameBroadcastUrl", "").trim();
                if (u.startsWith("http")) g.streamDay = u;
            }
        }
        if (g.streamName.isEmpty() && g.streamUrl.contains("red.sport")) g.streamName = "RED+";
    }

    /** {"rows":["E. JUNEMANN (2)","T. ORTH "]} -> "E. JUNEMANN (2), T. ORTH" */
    private static String scorers(JSONObject o) {
        org.json.JSONArray a = o == null ? null : o.optJSONArray("rows");
        if (a == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (s.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }

    /** Aktualisiert Stand/Zeit aus "gameData" des GetGameReport. */
    public void updateFromReport(JSONObject gd) {
        homeScore = gd.optInt("homeTeamScore", homeScore);
        awayScore = gd.optInt("awayTeamScore", awayScore);
        if (!gd.isNull("liveTimeGamePhase")) phase = gd.optString("liveTimeGamePhase", phase);
        liveTime = gd.optInt("liveTime", liveTime);
        status = gd.optInt("gameStatus", status);
        if (!gd.isNull("scheduledTime")) scheduledTime = gd.optString("scheduledTime", scheduledTime);
        if (homeShort.isEmpty()) homeShort = gd.optString("homeTeamShortname", "");
        if (awayShort.isEmpty()) awayShort = gd.optString("awayTeamShortname", "");
        if (homeName.isEmpty()) homeName = gd.optString("homeTeamLongname", "");
        if (awayName.isEmpty()) awayName = gd.optString("awayTeamLongname", "");
    }

    /**
     * Kurzform fuer das Floating: "NRW - U15" + "Regionalliga A Hauptrunde 2026/27" -> "U15 RL A",
     * "NRW - Senioren" + "Landesliga NRW Hauptrunde" -> "Senioren LL". Pokal/Freundschaftsspiel: nur Klasse.
     */
    public static String classShort(Game g) {
        String l = g.league == null ? "" : g.league.trim().replaceFirst("^NRW\\s*-\\s*", "");
        String d = g.division == null ? "" : g.division;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(Regionalliga|Landesliga|Bezirksliga|Oberliga)(?:\\s+([A-C])\\b)?").matcher(d);
        if (m.find()) {
            String lvl = m.group(1).startsWith("Reg") ? "RL" : m.group(1).startsWith("Lan") ? "LL"
                    : m.group(1).startsWith("Bez") ? "BL" : "OL";
            return (l + " " + lvl + (m.group(2) != null ? " " + m.group(2) : "")).trim();
        }
        return l;
    }

    public String score() {
        return homeScore + " : " + awayScore;
    }

    /** Ausfuehrlicher Text, z. B. "2. Drittel · 12:34". */
    public String phaseText() {
        if (status == 4) return "Spielende";
        if (status == 0) return scheduledTime.isEmpty() ? "Noch nicht begonnen" : "Bully um " + scheduledTime + " Uhr";
        String t = String.format("%02d:%02d", liveTime / 60, liveTime % 60);
        String p = phase == null ? "" : phase;
        if (p.startsWith("PERIOD ")) return p.substring(7).trim() + ". Drittel · " + t;
        if (p.startsWith("INTERMISSION ")) return "Pause nach dem " + p.substring(13).trim() + ". Drittel";
        if (p.startsWith("OVERTIME")) return "Verlängerung · " + t;
        if (p.equals("SHOOTOUT")) return "Penaltyschießen";
        if (p.equals("END OF GAME")) return "Spielende";
        return p.isEmpty() ? t : p + " · " + t;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("league", league); o.put("division", division);
            o.put("homeName", homeName); o.put("awayName", awayName);
            o.put("homeShort", homeShort); o.put("awayShort", awayShort);
            o.put("homeLogo", homeLogo); o.put("awayLogo", awayLogo);
            o.put("homeScore", homeScore); o.put("awayScore", awayScore);
            o.put("phase", phase); o.put("liveTime", liveTime); o.put("status", status); o.put("scheduledTime", scheduledTime);
            o.put("divisionId", divisionId); o.put("startMs", startMs); o.put("dateText", dateText);
            o.put("arenaShort", arenaShort); o.put("arenaLong", arenaLong); o.put("arenaAddress", arenaAddress);
            o.put("refs", refs);
            o.put("streamUrl", streamUrl); o.put("streamDay", streamDay); o.put("streamName", streamName);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Game fromJson(String s) {
        Game g = new Game();
        try {
            JSONObject o = new JSONObject(s);
            g.id = o.optString("id"); g.league = o.optString("league"); g.division = o.optString("division");
            g.homeName = o.optString("homeName"); g.awayName = o.optString("awayName");
            g.homeShort = o.optString("homeShort"); g.awayShort = o.optString("awayShort");
            g.homeLogo = o.optString("homeLogo"); g.awayLogo = o.optString("awayLogo");
            g.homeScore = o.optInt("homeScore"); g.awayScore = o.optInt("awayScore");
            g.phase = o.optString("phase"); g.liveTime = o.optInt("liveTime"); g.status = o.optInt("status", -1); g.scheduledTime = o.optString("scheduledTime", "");
            g.divisionId = o.optString("divisionId", ""); g.startMs = o.optLong("startMs", 0); g.dateText = o.optString("dateText", "");
            g.arenaShort = o.optString("arenaShort", ""); g.arenaLong = o.optString("arenaLong", ""); g.arenaAddress = o.optString("arenaAddress", "");
            g.refs = o.optString("refs", "");
            g.streamUrl = o.optString("streamUrl", ""); g.streamDay = o.optString("streamDay", ""); g.streamName = o.optString("streamName", "");
        } catch (JSONException ignored) {
        }
        return g;
    }
}
