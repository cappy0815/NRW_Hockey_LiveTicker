// Spielplan, Tabelle und Aufstellung: Daten aufbereiten (Logik aus v2.0: __GameParse, _TableShow, __LuFill).
package main

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

type sgame struct {
	id, div, league, divName      string
	home, away, hShort, aShort    string
	hID, aID                      string
	hs, as, status                int
	utcMs                         float64
	date, time                    string // dd.mm.yyyy, HH:MM ("" = Zeit offen)
	hall, address, periods, extra string
	scorersH, scorersA            string
	key                           string // yyyymmddHHMM
}

func scorers(v any, path string) string {
	x, ok := jGet(v, path)
	if !ok {
		return ""
	}
	arr, _ := x.([]any)
	var out []string
	for _, e := range arr {
		if s, ok := e.(string); ok && strings.TrimSpace(s) != "" {
			out = append(out, strings.TrimSpace(s))
		}
	}
	return strings.Join(out, ", ")
}

func parseSched(r map[string]any) sgame {
	g := sgame{
		id: jStr(r, "id"), div: jStr(r, "divisionId"),
		league: strings.TrimSpace(jStr(r, "leagueName")), divName: strings.TrimSpace(jStr(r, "divisionName")),
		home: jStr(r, "homeTeamLongName"), away: jStr(r, "awayTeamLongName"),
		hShort: jStr(r, "homeTeamShortName"), aShort: jStr(r, "awayTeamShortName"),
		hID: jStr(r, "homeTeamId"), aID: jStr(r, "awayTeamId"),
		hs: jInt(r, "homeTeamScore", 0), as: jInt(r, "awayTeamScore", 0), status: jInt(r, "gameStatus", -1),
		utcMs: jNum(r, "gameUtcTimestamp"), date: jStr(r, "scheduledDate.value"), hall: jStr(r, "location.longname"),
		scorersH: scorers(r, "homeScorers.rows"), scorersA: scorers(r, "awayScorers.rows"),
	}
	if !jBool(r, "timeIsToBeDetermined") {
		g.time = jStr(r, "scheduledTime")
	}
	// "address" ist selbst ein JSON-Text (street1, zipcode, city …)
	if a := jStr(r, "location.address"); a != "" {
		var m map[string]any
		if json.Unmarshal([]byte(a), &m) == nil {
			street := strings.TrimSpace(jStr(m, "street1"))
			city := strings.TrimSpace(strings.TrimSpace(jStr(m, "zipcode")) + " " + strings.TrimSpace(jStr(m, "city")))
			g.address = strings.Trim(street+", "+city, ", ")
		}
	}
	g.periods = strings.NewReplacer("(", "", ")", "", ",", "  ·  ").Replace(jStr(r, "periodResults"))
	if jBool(r, "isShootOut") {
		g.extra = "n.P."
	} else if jBool(r, "isOvertime") {
		g.extra = "n.V."
	}
	t := g.time
	if t == "" {
		t = "00:00"
	}
	if len(g.date) == 10 {
		g.key = g.date[6:10] + g.date[3:5] + g.date[0:2] + strings.ReplaceAll(t, ":", "")
	}
	return g
}

var (
	reNRW    = regexp.MustCompile(`^NRW\s*-\s*`)
	reSeason = regexp.MustCompile(`\s*\d{4}/\d{2,4}$`)
	reHall   = regexp.MustCompile(`\s*-\s*.*$`)
)

// "U15 · Regionalliga A Hauptrunde" (ohne "NRW - " und Saison)
func leagueShort(league, div string) string {
	l := reNRW.ReplaceAllString(league, "")
	d := reSeason.ReplaceAllString(div, "")
	if d == "" {
		return l
	}
	return l + " · " + d
}

func hallShort(h string) string { return reHall.ReplaceAllString(h, "") }

// "Mo, 26.10." zu "dd.mm.yyyy"
func dayLabel(d string) string {
	t, err := time.ParseInLocation("02.01.2006", d, time.Local)
	if err != nil {
		return d
	}
	wd := []string{"So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"}[t.Weekday()]
	return wd + ", " + d[:6]
}

func dayKey(offset int) string { return time.Now().AddDate(0, 0, offset).Format("20060102") }

// Zeilen fuer das Spielplan-Fenster. kind: 0 Spiel, 1 Tages-Ueberschrift, 2 Abschnitt
type srow struct {
	kind int
	g    *sgame
	text string
	div  string // kind 3: Liga (Division-ID)
	sub  string // kind 3: "x von y Spielen gespielt"
	fav  bool   // kind 3: Liga mit hervorgehobenem Team
}

func schedRows(all []sgame, tab int, fav func(string) bool) ([]srow, int) {
	if tab == 3 {
		return leagueRows(all, fav)
	}
	today := dayKey(0)
	seen := map[string]bool{}
	var pick []*sgame
	for i := range all {
		g := &all[i]
		if g.id == "" || seen[g.id] || len(g.key) < 8 {
			continue
		}
		k := g.key[:8]
		ok := false
		switch tab {
		case 0:
			ok = g.status != 4 && k >= today && k <= dayKey(7)
		case 1:
			ok = g.status == 4 && k >= dayKey(-7) && k <= today
		case 2:
			ok = fav(g.home+"|"+g.away) && (g.status == 4 || k >= today)
		}
		if ok {
			seen[g.id] = true
			pick = append(pick, g)
		}
	}
	sort.SliceStable(pick, func(a, b int) bool { return pick[a].key < pick[b].key })
	var order []*sgame // nil = Abschnittswechsel bei "Meine Spiele"
	switch tab {
	case 1: // Ergebnisse: neueste zuerst
		for i := len(pick) - 1; i >= 0; i-- {
			order = append(order, pick[i])
		}
	case 2: // kommende aufsteigend, dann die letzten 10 Ergebnisse absteigend
		for _, g := range pick {
			if g.status != 4 {
				order = append(order, g)
			}
		}
		n := 0
		for i := len(pick) - 1; i >= 0 && n < 10; i-- {
			if pick[i].status == 4 {
				if n == 0 {
					order = append(order, nil)
				}
				order = append(order, pick[i])
				n++
			}
		}
	default:
		order = pick
	}
	var rows []srow
	if tab == 2 && len(order) > 0 && order[0] != nil {
		rows = append(rows, srow{kind: 2, text: "KOMMENDE SPIELE"})
	}
	last := ""
	for _, g := range order {
		if g == nil {
			rows = append(rows, srow{kind: 2, text: "LETZTE ERGEBNISSE"})
			last = ""
			continue
		}
		day := dayLabel(g.date)
		switch g.key[:8] {
		case today:
			day = "Heute · " + day
		case dayKey(1):
			day = "Morgen · " + day
		case dayKey(-1):
			day = "Gestern · " + day
		}
		if day != last {
			rows = append(rows, srow{kind: 1, text: day})
			last = day
		}
		rows = append(rows, srow{kind: 0, g: g})
	}
	return rows, len(pick)
}

// ---------------------------------------------------------------- Tabellen-Uebersicht (wie Android 2.7)
// Alle Ligen aus dem Saisonplan, nach Altersklasse gruppiert; Ligen meiner Teams oben.
// Keine zusaetzlichen Abfragen: die Liste entsteht aus dem ohnehin geladenen Spielplan.
var (
	reSeasonAny = regexp.MustCompile(`\s*\d{2,4}/\d{2,4}`)
	reU         = regexp.MustCompile(`^(U\d+)`)
	reUExact    = regexp.MustCompile(`^U(\d+)$`)
)

// "NRW - U15 Regionalliga A" -> "U15", "NRW - Senioren" -> "Senioren"
func ligaGroup(league string) string {
	l := strings.TrimSpace(reNRW.ReplaceAllString(league, ""))
	if m := reU.FindString(l); m != "" {
		return m
	}
	if l == "" {
		return "Sonstige"
	}
	return l
}

// Titel ohne Altersklasse und Saison: "Regionalliga A · Hauptrunde"
func ligaTitle(league, div, gr string) string {
	l := strings.TrimSpace(reNRW.ReplaceAllString(league, ""))
	l = strings.TrimSpace(strings.TrimPrefix(l, gr))
	d := strings.TrimSpace(reSeasonAny.ReplaceAllString(div, ""))
	if l == "" {
		if d == "" {
			return gr
		}
		return d
	}
	if d == "" || strings.HasPrefix(d, l) {
		return d
	}
	return l + " · " + d
}

// Reihenfolge der Altersklassen: Senioren, Frauen, U20 ... U7, dann Freundschaft/Sonstige
func ligaGroupRank(gr string) int {
	if strings.EqualFold(gr, "Senioren") {
		return 0
	}
	if strings.EqualFold(gr, "Frauen") || strings.EqualFold(gr, "Damen") {
		return 1
	}
	if m := reUExact.FindStringSubmatch(gr); m != nil {
		n, _ := strconv.Atoi(m[1])
		return 100 - n
	}
	if strings.Contains(strings.ToLower(gr), "freundschaft") {
		return 300
	}
	return 200
}

// Spielklasse: Regionalliga/DEL, Landesliga, Bezirksliga, Rest
func ligaLevelRank(title string) int {
	t := strings.ToLower(title)
	switch {
	case strings.Contains(t, "dfel"), strings.Contains(t, "regionalliga"):
		return 0
	case strings.Contains(t, "landesliga"):
		return 1
	case strings.Contains(t, "bezirksliga"):
		return 2
	}
	return 3
}

type ligaInfo struct {
	div, group, title string
	games, done, rank int
	fav               bool
}

func leagueRows(all []sgame, fav func(string) bool) ([]srow, int) {
	m := map[string]*ligaInfo{}
	var order []string
	seen := map[string]bool{}
	for i := range all {
		g := &all[i]
		if g.div == "" || g.id == "" || seen[g.id] {
			continue
		}
		seen[g.id] = true
		li := m[g.div]
		if li == nil {
			gr := ligaGroup(g.league)
			li = &ligaInfo{div: g.div, group: gr, title: ligaTitle(g.league, g.divName, gr)}
			li.rank = ligaGroupRank(gr)*10 + ligaLevelRank(li.title)
			m[g.div] = li
			order = append(order, g.div)
		}
		li.games++
		if g.status == 4 {
			li.done++
		}
		if fav(g.home + "|" + g.away) {
			li.fav = true
		}
	}
	list := make([]*ligaInfo, 0, len(order))
	for _, d := range order {
		list = append(list, m[d])
	}
	sort.SliceStable(list, func(a, b int) bool {
		if list[a].rank != list[b].rank {
			return list[a].rank < list[b].rank
		}
		return strings.ToLower(list[a].title) < strings.ToLower(list[b].title)
	})
	rowOf := func(li *ligaInfo) srow {
		return srow{kind: 3, div: li.div, text: li.group + " · " + li.title,
			sub: fmt.Sprintf("%d von %d Spielen gespielt", li.done, li.games), fav: li.fav}
	}
	var rows []srow
	var nFav int
	for _, li := range list {
		if li.fav {
			if nFav == 0 {
				rows = append(rows, srow{kind: 2, text: "LIGEN MEINER TEAMS"})
			}
			nFav++
			rows = append(rows, rowOf(li))
		}
	}
	last := ""
	for _, li := range list {
		if li.group != last {
			rows = append(rows, srow{kind: 1, text: li.group})
			last = li.group
		}
		rows = append(rows, rowOf(li))
	}
	return rows, len(list)
}

// ---------------------------------------------------------------- Tabelle (Abfrage "Standings")
type standing struct {
	rank, team, teamID         string
	gp, won, lost, gf, ga, pts int
}

func parseStandings(d map[string]any) []standing {
	list := asList(d, "data.rows")
	if list == nil {
		list = asList(d, "data")
	}
	var out []standing
	for _, m := range list {
		out = append(out, standing{
			rank: jStr(m, "tableRank"), team: jStr(m, "teamLongname"), teamID: jStr(m, "id"),
			gp:  jInt(m, "gamesPlayed", 0),
			won: jInt(m, "gamesWon", 0) + jInt(m, "gamesWonInOt", 0), lost: jInt(m, "gamesLost", 0) + jInt(m, "gamesLostInOt", 0),
			gf: jInt(m, "goalsFor", 0), ga: jInt(m, "goalsAgainst", 0), pts: jInt(m, "points", 0),
		})
	}
	return out
}

// ---------------------------------------------------------------- Aufstellung (aus GetGameReport)
type lineup struct {
	title, homeName, awayName string
	home, away                [][7]string // #, Name, Pos, Reihe, T, A, SM
}

func num0(n int) string {
	if n == 0 {
		return ""
	}
	return fmt.Sprint(n)
}

func posOrder(p string) string {
	switch p {
	case "LD":
		return "1"
	case "RD":
		return "2"
	case "D":
		return "3"
	case "LF", "LW":
		return "4"
	case "C":
		return "5"
	case "RF", "RW":
		return "6"
	case "F":
		return "7"
	}
	return "8"
}

func parseLineup(d map[string]any) *lineup {
	data, ok := d["data"].(map[string]any)
	if !ok {
		return nil
	}
	g, _ := data["gameData"].(map[string]any)
	lu := &lineup{
		title:    fmt.Sprintf("%s   %d : %d   %s", jStr(g, "homeTeamShortname"), jInt(g, "homeTeamScore", 0), jInt(g, "awayTeamScore", 0), jStr(g, "awayTeamShortname")),
		homeName: jStr(g, "homeTeamLongname"), awayName: jStr(g, "awayTeamLongname"),
	}
	side := func(s string) [][7]string {
		type row struct {
			key string
			r   [7]string
		}
		var rs []row
		name := func(p map[string]any) string {
			n := jStr(p, "playerLastname")
			if f := jStr(p, "playerFirstname"); f != "" {
				n += " " + f
			}
			return n
		}
		for _, p := range asList(data, s+"GoalKeepers") {
			start := jInt(p, "isStartingPlayer", 0) == 1
			st, k := "", "01"
			if start {
				st, k = "  (Start)", "00"
			}
			rs = append(rs, row{k + fmt.Sprintf("%03d", jInt(p, "playerJerseyNr", 0)), [7]string{jStr(p, "playerJerseyNr"),
				name(p) + st + fmt.Sprintf("  · %d GT", jInt(p, "goalsAgainst", 0)), "G", "",
				num0(jInt(p, "goals", 0)), num0(jInt(p, "assists", 0)), num0(jInt(p, "penaltyMinutes", 0))}})
		}
		for _, p := range asList(data, s+"FieldPlayers") {
			line := jInt(p, "lineupLine", 0)
			ln, lk := "", 99
			if line > 0 {
				ln, lk = fmt.Sprint(line), line
			}
			pos := jStr(p, "position")
			rs = append(rs, row{fmt.Sprintf("1%02d%s%03d", lk, posOrder(pos), jInt(p, "playerJerseyNr", 0)), [7]string{jStr(p, "playerJerseyNr"),
				name(p), pos, ln, num0(jInt(p, "goals", 0)), num0(jInt(p, "assists", 0)), num0(jInt(p, "penaltyMinutes", 0))}})
		}
		sort.SliceStable(rs, func(a, b int) bool { return rs[a].key < rs[b].key })
		var out [][7]string
		for _, r := range rs {
			out = append(out, r.r)
		}
		return out
	}
	lu.home, lu.away = side("home"), side("away")
	return lu
}

// ---------------------------------------------------------------- Kalender (.ics, RFC 5545)
func icsEsc(s string) string {
	return strings.NewReplacer(`\`, `\\`, ";", `\;`, ",", `\,`, "\r\n", `\n`, "\n", `\n`).Replace(s)
}

func icsText(g *sgame) string {
	start := time.UnixMilli(int64(g.utcMs)).UTC()
	f := "20060102T150405Z"
	loc := g.hall
	if g.address != "" {
		loc += ", " + g.address
	}
	return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//EHV Live//DE\r\nBEGIN:VEVENT\r\n" +
		"UID:" + g.id + "@ehv-live\r\nDTSTAMP:" + time.Now().UTC().Format(f) + "\r\n" +
		"DTSTART:" + start.Format(f) + "\r\nDTEND:" + start.Add(150*time.Minute).Format(f) + "\r\n" +
		"SUMMARY:" + icsEsc(g.home+" – "+g.away) + "\r\nLOCATION:" + icsEsc(loc) + "\r\n" +
		"DESCRIPTION:" + icsEsc(leagueShort(g.league, g.divName)+" (eingetragen mit EHV-NRW Live-Ticker)") + "\r\n" +
		"END:VEVENT\r\nEND:VCALENDAR\r\n"
}
