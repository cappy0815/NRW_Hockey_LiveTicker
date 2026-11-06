// Datenabruf ehv-nrw.de / hockeydata.net – Logik 1:1 aus EHV_NRW_LiveTicker_v2.0.au3 uebernommen.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	pageURL   = "https://ehv-nrw.de/leagues/next-games/"
	apiBase   = "https://api.hockeydata.net/data/ebel/"
	woLive    = "%7B%22noScorers%22%3Atrue%2C%22live%22%3Atrue%7D"                          // {"noScorers":true,"live":true}
	woAll     = "%7B%22live%22%3Afalse%2C%22semantic%22%3Atrue%2C%22noScorers%22%3Atrue%7D" // ganzer Spielplan
	failAbort = 3                                                                           // so viele Ligen hintereinander ohne Antwort -> Abbruch
)

// Fallback-Werte (Stand 17.10.2026), falls die EHV-Seite beim Start nicht lesbar ist
var (
	apiKey = "f4b71c8702bd3a6eadc89becf6da5a4e"
	divs   = strings.Split("21419,21420,21623,21679,22359,21691,21692,21693,22386,22387,22388,21680,21681,21627,"+
		"21628,21632,21633,21637,21648,21652,21656,21660,21664,21668,21669,21673,21674,21675", ",")
)

var (
	httpQuick  = &http.Client{Timeout: 8 * time.Second}
	httpNormal = &http.Client{Timeout: 20 * time.Second}
)

func httpGet(url string, quick bool) string {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return ""
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) EHV-LiveTicker/3.0")
	req.Header.Set("Referer", "https://ehv-nrw.de/")
	c := httpNormal
	if quick {
		c = httpQuick
	}
	resp, err := c.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return ""
	}
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return ""
	}
	return string(b)
}

// liest apiKey und divisionIds von der EHV-Seite (sonst bleiben die Fallback-Werte)
func loadConfig() string {
	html := httpGet(pageURL, true)
	if html == "" {
		return "Fallback-Liste"
	}
	mk := regexp.MustCompile(`"apiKey"\s*:\s*"([0-9a-fA-F]+)"`).FindStringSubmatch(html)
	ids := regexp.MustCompile(`"divisionId"\s*:\s*"(\d+)"`).FindAllStringSubmatch(html, -1)
	if mk == nil || len(ids) == 0 {
		return "Fallback-Liste"
	}
	seen := map[string]bool{}
	var list []string
	for _, m := range ids {
		if !seen[m[1]] {
			seen[m[1]] = true
			list = append(list, m[1])
		}
	}
	apiKey = mk[1]
	divs = list
	return fmt.Sprintf("%d Ligen", len(list))
}

var jsonpRe = regexp.MustCompile(`(?s)^[^(]*\((.*)\)\s*;?\s*$`)

// API-Abruf; Fehler: 1 = keine Antwort, 2 = kein JSON, 3 = statusId <> 1
func apiGet(method, query string, quick bool) (map[string]any, int) {
	s := httpGet(apiBase+method+"?apiKey="+apiKey+"&lang=de&referer=ehv-nrw.de&"+query, quick)
	if s == "" {
		return nil, 1
	}
	if !strings.HasPrefix(strings.TrimLeft(s, " \t\r\n"), "{") {
		s = jsonpRe.ReplaceAllString(s, "$1")
	}
	var m map[string]any
	if json.Unmarshal([]byte(s), &m) != nil {
		return nil, 2
	}
	if jInt(m, "statusId", 0) != 1 {
		return nil, 3
	}
	return m, 0
}

// ---------------------------------------------------------------- JSON-Helfer (Pfad mit Punkten)
func jGet(v any, path string) (any, bool) {
	cur := v
	for _, k := range strings.Split(path, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil, false
		}
		cur, ok = m[k]
		if !ok || cur == nil {
			return nil, false
		}
	}
	return cur, true
}

func jStr(v any, path string) string {
	x, ok := jGet(v, path)
	if !ok {
		return ""
	}
	switch t := x.(type) {
	case string:
		return t
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(t)
	}
	return ""
}

func jInt(v any, path string, def int) int {
	x, ok := jGet(v, path)
	if !ok {
		return def
	}
	switch t := x.(type) {
	case float64:
		return int(t)
	case string:
		if n, err := strconv.Atoi(strings.TrimSpace(t)); err == nil {
			return n
		}
	case bool:
		if t {
			return 1
		}
		return 0
	}
	return def
}

func jBool(v any, path string) bool {
	x, ok := jGet(v, path)
	if !ok {
		return false
	}
	b, _ := x.(bool)
	return b
}

func jRows(m map[string]any) []map[string]any {
	x, ok := jGet(m, "data.rows")
	if !ok {
		return nil
	}
	arr, _ := x.([]any)
	var out []map[string]any
	for _, r := range arr {
		if mm, ok := r.(map[string]any); ok {
			out = append(out, mm)
		}
	}
	return out
}

// ---------------------------------------------------------------- Server-Test
func serverOk() bool {
	s := httpGet(apiBase+"Schedule?apiKey="+apiKey+"&lang=de&referer=ehv-nrw.de&divisionId="+divs[0]+"&widgetOptions="+woLive, true)
	return strings.Contains(s, `"statusId"`)
}

// ---------------------------------------------------------------- Spielzeilen
type game struct {
	id, div, league, home, away, score, timeText string
	kind                                         int // 0 live, 1 anstehend, 2 Trennzeile
	fav                                          bool
	sortKey                                      string
}

func leagueText(r map[string]any) string {
	return strings.TrimSpace(jStr(r, "leagueName")) + " · " + strings.TrimSpace(jStr(r, "divisionName"))
}

// Spielplan aller Ligen: anstehende Spiele von heute + Ligen, die heute spielen.
// Rueckgabe ok=false bei Server-Ausfall (alter Stand bleibt).
type upcomingResult struct {
	games      []game
	divsToday  []string
	todayKnown bool
	haveAny    bool
	all        []sgame // ganzer Spielplan aller Ligen (fuer das Spielplan-Fenster)
}

func loadUpcoming(status func(string)) (upcomingResult, bool) {
	var res upcomingResult
	if !serverOk() {
		return res, false
	}
	today := time.Now().Format("02.01.2006")
	have, seq := 0, 0
	for i, d := range divs {
		status(fmt.Sprintf("Lade Spielplan … (%d/%d)", i+1, len(divs)))
		m, err := apiGet("Schedule", "divisionId="+d+"&widgetOptions="+woAll, false)
		if err != 0 {
			if err == 1 {
				seq++
				if seq >= failAbort {
					return res, false
				}
			}
			continue
		}
		seq = 0
		have++
		playsToday := false
		for _, r := range jRows(m) {
			res.all = append(res.all, parseSched(r))
			if jStr(r, "scheduledDate.value") != today {
				continue
			}
			playsToday = true
			if jInt(r, "gameStatus", -1) != 0 {
				continue
			}
			t := jStr(r, "scheduledTime")
			if jBool(r, "timeIsToBeDetermined") {
				t = "n.n."
			}
			hall := jStr(r, "location.shortname")
			txt := "Bully " + t + " Uhr"
			if hall != "" {
				txt += " · " + hall
			}
			res.games = append(res.games, game{
				id: jStr(r, "id"), div: jStr(r, "divisionId"), league: leagueText(r),
				home: jStr(r, "homeTeamLongName"), away: jStr(r, "awayTeamLongName"),
				score: "–", timeText: txt, kind: 1, sortKey: t,
			})
		}
		if playsToday {
			res.divsToday = append(res.divsToday, d)
		}
	}
	res.haveAny = have > 0
	res.todayKnown = have == len(divs) // nur bei vollstaendigem Spielplan, sonst lieber alle Ligen fragen
	sort.SliceStable(res.games, func(a, b int) bool { return res.games[a].sortKey < res.games[b].sortKey })
	return res, true
}

// laufende Spiele der angegebenen Ligen; ok=false bei Server-Ausfall
func loadLive(ask []string, status func(string)) (live []game, errCount int, ok bool) {
	status("Prüfe Verbindung zu hockeydata.net …")
	if !serverOk() {
		return nil, 0, false
	}
	seen := map[string]bool{}
	seq := 0
	for i, d := range ask {
		status(fmt.Sprintf("Suche laufende Spiele … (%d/%d)", i+1, len(ask)))
		m, err := apiGet("Schedule", "divisionId="+d+"&widgetOptions="+woLive, false)
		if err != 0 {
			errCount++
			seq++
			if seq >= failAbort {
				return nil, errCount, false
			}
			continue
		}
		seq = 0
		for _, r := range jRows(m) {
			if jInt(r, "gameStatus", -1) != 1 {
				continue
			}
			id := jStr(r, "id")
			if id == "" || seen[id] {
				continue
			}
			seen[id] = true
			live = append(live, game{
				id: id, div: jStr(r, "divisionId"), league: leagueText(r),
				home: jStr(r, "homeTeamLongName"), away: jStr(r, "awayTeamLongName"),
				score:    fmt.Sprintf("%d : %d", jInt(r, "homeTeamScore", 0), jInt(r, "awayTeamScore", 0)),
				timeText: phaseText(jStr(r, "liveTimeGamePhase"), jInt(r, "liveTime", 0), 1),
			})
		}
	}
	return live, errCount, true
}

var (
	rePeriod = regexp.MustCompile(`^PERIOD (\d+)`)
	rePause  = regexp.MustCompile(`^INTERMISSION (\d+)`)
)

func phaseText(phase string, sec, status int) string {
	if status == 4 {
		return "Spielende"
	}
	if status == 0 {
		return "Noch nicht begonnen"
	}
	t := fmt.Sprintf("%02d:%02d", sec/60, sec%60)
	if m := rePeriod.FindStringSubmatch(phase); m != nil {
		return m[1] + ". Drittel  ·  " + t
	}
	if m := rePause.FindStringSubmatch(phase); m != nil {
		return "Pause nach dem " + m[1] + ". Drittel"
	}
	if strings.HasPrefix(phase, "OVERTIME") {
		return "Verlängerung  ·  " + t
	}
	if phase == "SHOOTOUT" {
		return "Penaltyschießen"
	}
	if phase == "END OF GAME" {
		return "Spielende"
	}
	return phase + "  ·  " + t
}

// "Neuss, Moers" -> (?i)(Neuss|Moers)
func favRegex(terms string) *regexp.Regexp {
	var alt []string
	for _, t := range strings.Split(terms, ",") {
		t = strings.TrimSpace(t)
		if t != "" {
			alt = append(alt, regexp.QuoteMeta(t))
		}
	}
	if len(alt) == 0 {
		return nil
	}
	return regexp.MustCompile("(?i)(" + strings.Join(alt, "|") + ")")
}

// ---------------------------------------------------------------- Spielbericht (Ticker)
const logoBase = "https://api.hockeydata.net/img/icehockey/ebel/team-logos/"

type report struct {
	gameID                 string
	status, hs, as         int
	hShort, aShort         string
	hLong, aLong, division string
	timeText, periods      string
	phase                  string
	events                 []string
	pens                   int
	homeID, awayID         string
	schedDate, schedTime   string // Datum (jjjj-mm-tt) und Anstoßzeit für die Schiedsrichter-Zuordnung
}

func asList(v any, key string) []map[string]any {
	x, ok := jGet(v, key)
	if !ok {
		return nil
	}
	arr, _ := x.([]any)
	var out []map[string]any
	for _, r := range arr {
		if m, ok := r.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func playerOf(p any) string {
	name := jStr(p, "playerLastname")
	if nr := jStr(p, "playerJerseyNr"); nr != "" {
		name = "#" + nr + " " + name
	}
	return name
}

func player(m map[string]any, key string) string {
	x, ok := m[key]
	if !ok {
		return ""
	}
	if _, isMap := x.(map[string]any); !isMap {
		return ""
	}
	return playerOf(x)
}

// Vorlagen: Feldnamen nicht dokumentiert -> alle Felder mit "assist" im Namen, alphabetisch (wie v2.0)
func assists(m map[string]any) string {
	var keys []string
	for k := range m {
		if strings.Contains(k, "assist") {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	var out []string
	for _, k := range keys {
		switch v := m[k].(type) {
		case map[string]any:
			if p := playerOf(v); p != "" {
				out = append(out, p)
			}
		case []any:
			for _, e := range v {
				if mm, ok := e.(map[string]any); ok {
					if p := playerOf(mm); p != "" {
						out = append(out, p)
					}
				}
			}
		}
	}
	return strings.Join(out, ", ")
}

func pad(s string, n int) string {
	for len([]rune(s)) < n {
		s += " "
	}
	return s
}

func jNum(v any, key string) float64 {
	x, ok := jGet(v, key)
	if !ok {
		return 0
	}
	switch t := x.(type) {
	case float64:
		return t
	case string:
		f, _ := strconv.ParseFloat(t, 64)
		return f
	}
	return 0
}

func parseReport(id string, d map[string]any) (*report, bool) {
	data, ok := d["data"].(map[string]any)
	if !ok {
		return nil, false
	}
	g, ok := data["gameData"].(map[string]any)
	if !ok {
		return nil, false
	}
	r := &report{gameID: id}
	r.status = jInt(g, "gameStatus", -1)
	r.hs, r.as = jInt(g, "homeTeamScore", 0), jInt(g, "awayTeamScore", 0)
	r.hShort, r.aShort = jStr(g, "homeTeamShortname"), jStr(g, "awayTeamShortname")
	r.hLong, r.aLong = jStr(g, "homeTeamLongname"), jStr(g, "awayTeamLongname")
	r.division = jStr(g, "divisionLongname")
	r.homeID, r.awayID = jStr(g, "homeTeamId"), jStr(g, "awayTeamId")
	r.schedDate, r.schedTime = jStr(g, "scheduledDate.value"), jStr(g, "scheduledTime")
	r.phase = jStr(g, "liveTimeGamePhase")
	if r.status == 0 {
		r.timeText = "Bully um " + jStr(g, "scheduledTime") + " Uhr"
	} else {
		r.timeText = phaseText(r.phase, jInt(g, "liveTime", 0), r.status)
	}
	// Drittelergebnisse (bei laufendem Spiel nur bis zum aktuellen Drittel)
	cur := jInt(g, "liveTimePeriod", 0)
	var per []string
	for _, p := range asList(g, "periodStats") {
		sp := jStr(p, "period")
		n, isNum := strconv.Atoi(sp)
		if r.status == 1 && isNum == nil && cur > 0 && n > cur {
			continue
		}
		lbl := sp + "  "
		if isNum == nil {
			lbl = sp + ". D  "
		}
		per = append(per, fmt.Sprintf("%s%d:%d", lbl, jInt(p, "homeScore", 0), jInt(p, "awayScore", 0)))
	}
	r.periods = strings.Join(per, "   |   ")

	// Ereignisse: Tore und Strafen, neueste oben (bei gleicher Zeit Tor vor Strafe)
	type ev struct {
		key  float64
		line string
	}
	var evs []ev
	for _, side := range []struct{ key, team string }{{"homeGoals", r.hShort}, {"awayGoals", r.aShort}} {
		for _, m := range asList(data, side.key) {
			l := pad(jStr(m, "gameTimeFormatted"), 6) + "TOR    " + pad(side.team, 5) + " " + pad(jStr(m, "newScore"), 6) + player(m, "scoredBy")
			if a := assists(m); a != "" {
				l += " (" + a + ")"
			}
			if s := jStr(m, "gameStrength"); s != "" && s != "EQ" {
				l += " [" + s + "]"
			}
			if jBool(m, "isEmptyNet") {
				l += " [EN]"
			}
			if jBool(m, "isPenaltyShot") {
				l += " [PS]"
			}
			evs = append(evs, ev{jNum(m, "gameTime")*2 + 1, l})
		}
	}
	for _, side := range []struct{ key, team string }{{"homePenalties", r.hShort}, {"awayPenalties", r.aShort}} {
		list := asList(data, side.key)
		r.pens += len(list)
		for _, m := range list {
			who := player(m, "offender")
			if who == "" {
				if s := player(m, "servedBy"); s != "" {
					who = "abgesessen von " + s
				}
			}
			mins := int(math.Round(jNum(m, "penaltyLength") / 60))
			l := pad(jStr(m, "gameTimeFormatted"), 6) + "STRAFE " + pad(side.team, 5) + " " + pad(fmt.Sprintf("%d'", mins), 6) + who
			if o := jStr(m, "offence"); o != "" {
				l += " – " + o
			}
			evs = append(evs, ev{jNum(m, "gameTime") * 2, l})
		}
	}
	sort.SliceStable(evs, func(a, b int) bool { return evs[a].key > evs[b].key })
	for _, e := range evs {
		r.events = append(r.events, e.line)
	}
	return r, true
}

func httpGetBin(url string) []byte {
	s := httpGet(url, false)
	return []byte(s)
}
