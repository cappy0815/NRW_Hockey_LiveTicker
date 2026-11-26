package main

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

// Schiedsrichter-Reiter im Spielplan (wie Android 2.98): die esrw.de-Einteilungen als Liste.
var refsMine bool // nur Einteilungen meiner Teams

// Eine Zeile der esrw.de-Tabelle, Datum jjjj-mm-tt
type refEntry struct {
	date, tm, league, home, away string
	inf                          refInfo
}

func refsSnapshot() refData {
	refsMu.Lock()
	defer refsMu.Unlock()
	return refsCur
}

// Reiter geöffnet (Zeitsperre gilt) oder „Aktualisieren“ (Zeitsperre aufheben)
func refsAsync(force bool) {
	go func() {
		if force {
			refsMu.Lock()
			refsAt, refsTry = time.Time{}, time.Time{}
			refsMu.Unlock()
		}
		refsEnsure()
		pPostMessageW.Call(hMain, wmPanel, 3, 0)
	}()
}

func refsText(inf refInfo) string {
	if inf.hsr == "" && inf.lsr == "" {
		return "noch nicht eingeteilt"
	}
	return "HSR " + orOffen(inf.hsr) + "  ·  (L)SR " + orOffen(inf.lsr)
}

// Zeilen des Reiters: Tag, Anstoß, Paarung, HSR/(L)SR. g = passendes Spiel aus dem Spielplan (Doppelklick = Steckbrief)
func refRows(all []sgame, mine bool) ([]srow, int) {
	d := refsSnapshot()
	byKey := map[string]*sgame{}
	for i := range all {
		g := &all[i]
		if len(g.key) >= 12 && g.home != "" {
			iso := g.key[:4] + "-" + g.key[4:6] + "-" + g.key[6:8]
			byKey[refKey(iso, g.key[8:10]+":"+g.key[10:12], g.home, g.away)] = g
		}
	}
	list := append([]refEntry(nil), d.list...)
	sort.SliceStable(list, func(a, b int) bool {
		if list[a].date != list[b].date {
			return list[a].date < list[b].date
		}
		return list[a].tm < list[b].tm
	})
	today := dayKey(0)
	var rows []srow
	n, last := 0, ""
	for _, e := range list {
		g := byKey[refKey(e.date, e.tm, e.home, e.away)]
		grp := teamGroupOf(e.league)
		if g != nil {
			grp = teamGroupOf(g.league)
		}
		fav := favGame(grp, e.home, e.away)
		if mine && !fav {
			continue
		}
		n++
		k := strings.ReplaceAll(e.date, "-", "")
		day := dayLabel(k[6:8] + "." + k[4:6] + "." + k[:4])
		switch k {
		case today:
			day = "Heute · " + day
		case dayKey(1):
			day = "Morgen · " + day
		}
		if day != last {
			rows = append(rows, srow{kind: 1, text: day})
			last = day
		}
		paar := e.home
		if e.away != "" {
			paar += " – " + e.away
		}
		rows = append(rows, srow{kind: 4, g: g, text: paar, sub: refsText(e.inf), tm: e.tm, fav: fav})
	}
	return rows, n
}

func refsStatus(n int) string {
	d := refsSnapshot()
	refsMu.Lock()
	at, try := refsAt, refsTry
	refsMu.Unlock()
	switch {
	case d.max == "" && try.IsZero():
		return "Lade esrw.de …"
	case d.max == "":
		return "esrw.de nicht erreichbar · ↻ lädt neu"
	case n == 0 && refsMine:
		return "Keine Einteilungen für deine Teams (Optionen › Hervorhebung)"
	case n == 0:
		return "Keine Einteilungen auf esrw.de"
	}
	return fmt.Sprintf("%d Einteilungen auf esrw.de · Stand %s · Doppelklick = Steckbrief", n, at.Format("15:04"))
}
