package main

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// Teams auswählen (wie Android 2.98): einzelne Mannschaften je Altersklasse hervorheben.
// Schlüssel "Altersklasse|Teamname" – so übersteht die Auswahl neue Runden mit anderer Liga-ID.
var (
	favTeams = map[string]bool{}
	tableGrp string // Altersklasse der geöffneten Tabelle
	tpTerms  string
	tpSel    map[string]bool
	tpRows   []tpRow
	tpStatus string
)

type tpRow struct {
	hdr         bool
	group, team string
	leagues     string
	rank        int
}

const tpRowH = 30

// Altersklasse aus dem Liganamen: "NRW - U15 Regionalliga A" -> "U15", "Senioren Oberliga" -> "Senioren"
func teamGroupOf(league string) string {
	l := strings.TrimSpace(reNRW.ReplaceAllString(league, ""))
	if m := reU.FindString(l); m != "" {
		return m
	}
	if f := strings.Fields(l); len(f) > 0 {
		return f[0]
	}
	return "Sonstige"
}

func parseTeamKeys(s string) map[string]bool {
	m := map[string]bool{}
	for _, k := range strings.Split(s, ";") {
		if k = strings.TrimSpace(k); strings.Contains(k, "|") {
			m[k] = true
		}
	}
	return m
}

// Mit ausgewählten Teams zählt nur die Auswahl, sonst die Suchbegriffe im Teamnamen
func favHit(group, team string) bool {
	if !favOn || team == "" {
		return false
	}
	if len(favTeams) > 0 {
		return favTeams[group+"|"+team]
	}
	return favRe != nil && favRe.MatchString(team)
}

func favGame(group, home, away string) bool {
	return favHit(group, home) || favHit(group, away)
}

func sFav(g *sgame) bool { return favGame(teamGroupOf(g.league), g.home, g.away) }

func favAny() bool { return favRe != nil || len(favTeams) > 0 }

func favStatus() string {
	if len(favTeams) > 0 {
		return fmt.Sprintf("%d Teams ausgewählt: kommende Spiele und letzte Ergebnisse", len(favTeams))
	}
	return "„" + favTerms + "“: kommende Spiele und letzte Ergebnisse"
}

func teamsBtnLabel() string {
	if len(favTeams) == 0 {
		return "Teams auswählen …"
	}
	return fmt.Sprintf("Teams auswählen … (%d)", len(favTeams))
}

// Knopf in den Optionen: Beschriftung mit Anzahl
func optBtnLabel(r optRow) string {
	if r.key == "teams" {
		return teamsBtnLabel()
	}
	return r.label
}

func favTeamsSave() {
	var ks []string
	for k := range favTeams {
		ks = append(ks, k)
	}
	sort.Strings(ks)
	iniWrite(iniFile, "Optionen", "HervorhebenTeams", strings.Join(ks, ";"))
}

func joinSet(m map[string]bool) string {
	var s []string
	for k := range m {
		s = append(s, k)
	}
	sort.Strings(s)
	return strings.Join(s, " · ")
}

// Fenster „Teams auswählen“. terms = Suchbegriffe aus den Optionen (auch ungespeichert)
func teamsOpen(terms string) {
	tpTerms = terms
	tpSel = map[string]bool{}
	for k := range favTeams {
		tpSel[k] = true
	}
	panelOpen("teams", "Teams auswählen", 560, 600, false)
	if p := panels["teams"]; p != nil {
		p.viewTop, p.viewH = 88, 600-88-62
	}
	teamsBuild(true)
}

// Kandidaten: alle Teams des Spielplans, deren Name einen Suchbegriff enthält, nach Altersklasse
func teamsBuild(load bool) {
	p := panels["teams"]
	if p == nil {
		return
	}
	mu.Lock()
	all := schedAll
	mu.Unlock()
	re := favRegex(tpTerms)
	tpRows = nil
	p.contentH = 0
	if re == nil {
		tpStatus = "Erst unter Optionen › Hervorhebung einen Suchbegriff eintragen (z. B. Neuss)."
		return
	}
	if len(all) == 0 {
		tpStatus = "Der Spielplan wird geladen – das Fenster gleich erneut öffnen."
		if load {
			startWork(false, true)
		}
		return
	}
	type ent struct {
		row     tpRow
		leagues map[string]bool
	}
	m := map[string]*ent{}
	for i := range all {
		g := &all[i]
		grp := teamGroupOf(g.league)
		for _, team := range []string{g.home, g.away} {
			if team == "" || !re.MatchString(team) {
				continue
			}
			key := grp + "|" + team
			e := m[key]
			if e == nil {
				e = &ent{row: tpRow{group: grp, team: team, rank: ligaGroupRank(grp)}, leagues: map[string]bool{}}
				m[key] = e
			}
			if t := strings.Trim(reHauptrunde.ReplaceAllString(ligaTitle(g.league, g.divName, grp), ""), " ·"); t != "" {
				e.leagues[t] = true
			}
		}
	}
	list := make([]*ent, 0, len(m))
	for _, e := range m {
		list = append(list, e)
	}
	sort.Slice(list, func(i, j int) bool {
		a, b := list[i].row, list[j].row
		if a.rank != b.rank {
			return a.rank < b.rank
		}
		if a.group != b.group {
			return a.group < b.group
		}
		return strings.ToLower(a.team) < strings.ToLower(b.team)
	})
	last := ""
	for _, e := range list {
		if e.row.group != last {
			last = e.row.group
			tpRows = append(tpRows, tpRow{hdr: true, group: last})
		}
		r := e.row
		r.leagues = joinSet(e.leagues)
		tpRows = append(tpRows, r)
	}
	if len(list) == 0 {
		tpStatus = "Kein Team mit „" + tpTerms + "“ im Spielplan gefunden."
	} else {
		tpStatus = fmt.Sprintf("%d Teams mit „%s“ · angehakte werden hervorgehoben", len(list), tpTerms)
	}
	p.contentH = len(tpRows) * tpRowH
}

// Nach dem Laden des Spielplans die Liste nachziehen, solange sie noch leer ist
func teamsRebuild() {
	if p := panels["teams"]; p != nil && len(tpRows) == 0 {
		teamsBuild(false)
		invalidate(p.hwnd)
	}
}

func paintTeams(c *canvas, p *panel, w, h int) {
	c.line(22, 56, w-44, 22, tpStatus, ui(8.5, false), cGreyTxt, DT_LEFT)
	for i, r := range tpRows {
		y := p.viewTop + i*tpRowH - p.scroll
		if y < p.viewTop || y+tpRowH > p.viewTop+p.viewH {
			continue
		}
		if r.hdr {
			c.round(12, y+2, w-24, tpRowH-4, 6, 0xEEF0F3)
			c.line(24, y, w-48, tpRowH, r.group, ui(8.5, true), 0x7C746E, DT_LEFT)
			continue
		}
		on := tpSel[r.group+"|"+r.team]
		bg := uint32(cWhite)
		if p.hover == "trow"+strconv.Itoa(i) {
			bg = cHover
		}
		c.round(12, y+2, w-24, tpRowH-4, 6, bg)
		box := uint32(0xD3D8DE)
		if on {
			box = cGreen
		}
		c.round(22, y+7, 16, 16, 4, box)
		if on {
			c.line(22, y+7, 16, 16, "✓", ui(7, true), cWhite, DT_CENTER)
		}
		c.line(48, y, 300, tpRowH, r.team, ui(9, on), cDark, DT_LEFT)
		c.line(w-330, y, 290, tpRowH, r.leagues, ui(8, false), cGreyTxt, DT_RIGHT)
	}
}

func teamsHit(p *panel, x, y int) string {
	if y >= p.viewTop && y < p.viewTop+p.viewH && x >= 12 && x < p.w-12 {
		i := (y - p.viewTop + p.scroll) / tpRowH
		if i < len(tpRows) && !tpRows[i].hdr {
			return "trow" + strconv.Itoa(i)
		}
		return ""
	}
	return btnHit(p, x, y)
}

func teamsClick(p *panel, hv string) {
	switch {
	case strings.HasPrefix(hv, "trow"):
		i, err := strconv.Atoi(hv[4:])
		if err != nil || i >= len(tpRows) {
			return
		}
		k := tpRows[i].group + "|" + tpRows[i].team
		if tpSel[k] {
			delete(tpSel, k)
		} else {
			tpSel[k] = true
		}
	case hv == "btn0": // Übernehmen
		favTeams = map[string]bool{}
		for k, on := range tpSel {
			if on {
				favTeams[k] = true
			}
		}
		favTeamsSave()
		for i := range rows {
			if rows[i].kind != 2 {
				rows[i].fav = isFav(rows[i])
			}
		}
		invalidate(hMain)
		schedBuild()
		if op := panels["opt"]; op != nil {
			invalidate(op.hwnd)
		}
		panelClose(p)
	case hv == "btn1": // Keins: Auswahl leeren, gespeichert wird erst mit Übernehmen
		tpSel = map[string]bool{}
	case hv == "btn2":
		panelClose(p)
	}
}
