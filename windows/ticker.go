//go:build windows

// Ticker-Fenster (Etappe 2): rahmenloses, dunkles Fenster je Spiel mit Stand, Logos, Spielzeit,
// Drittelergebnissen, Toren/Strafen und Toenen. Logik aus v2.0 (_TickerUpdate usw.).
package main

import (
	"strconv"
	"sync"
	"time"
	"unsafe"
)

const (
	maxTicker   = 8
	tickW       = 380
	highlightMs = 15000
	preBully    = 150 // Deckkraft vor dem Bully (0-255)
	evTop       = 244
	evBoxH      = 176
)

type ticker struct {
	hwnd             uintptr
	id, league, div  string
	r                *report
	upd              string
	lastTot, lastPen int
	lastPhase        string
	lastStatus       int
	ended            bool
	goalUntil        time.Time
	showEv           bool
	evOpened         time.Time
	evScroll         int
	hover            string
}

var (
	tickers [maxTicker]*ticker

	tickMu    sync.Mutex
	openIDs   []string // fuer den Abruf-Thread: offene, nicht beendete Spiele
	pendRep   = map[string]tickResult{}
	logoBytes = map[string][]byte{} // nur Abruf-Thread
	logoImg   = map[string]*image{} // nur UI-Thread
	tickReq   = make(chan string, 16)
)

type tickResult struct {
	rep   *report
	fail  bool
	logos map[string][]byte
}

func tickerOpen(id string) bool {
	for _, t := range tickers {
		if t != nil && t.id == id {
			return true
		}
	}
	return false
}

func tickerCount() int {
	n := 0
	for _, t := range tickers {
		if t != nil {
			n++
		}
	}
	return n
}

func syncOpenIDs() {
	var ids []string
	for _, t := range tickers {
		if t != nil && !t.ended {
			ids = append(ids, t.id)
		}
	}
	tickMu.Lock()
	openIDs = ids
	tickMu.Unlock()
}

func tickH(t *ticker) int {
	if t.showEv {
		return evTop + evBoxH + 12
	}
	return evTop
}

func tickerOpenGame(id, league, div string) {
	free := -1
	for s, t := range tickers {
		if t != nil && t.id == id {
			pShowWindow.Call(t.hwnd, SW_RESTORE)
			pSetForegroundWindow.Call(t.hwnd)
			return
		}
		if free < 0 && t == nil {
			free = s
		}
	}
	if free < 0 {
		status = "Maximal " + strconv.Itoa(maxTicker) + " Ticker gleichzeitig – bitte einen schließen."
		invalidate(hMain)
		return
	}
	t := &ticker{id: id, league: league, div: div, lastTot: -1, lastPen: -1, lastStatus: -1, showEv: optEvDefault, upd: "Lade …"}
	if t.showEv {
		t.evOpened = time.Now()
	}
	x, _ := strconv.Atoi(iniRead(iniFile, "Ticker", "X", "-1"))
	y, _ := strconv.Atoi(iniRead(iniFile, "Ticker", "Y", "-1"))
	if x < 0 || int32(x) > screenW()-100 {
		x = int(screenW() - S(tickW) - S(30))
	}
	if y < 0 || int32(y) > screenH()-100 {
		y = int(S(40))
	}
	x += free * int(S(28))
	y += free * int(S(28))
	ex := uint32(WS_EX_LAYERED)
	if optOnTop {
		ex |= WS_EX_TOPMOST
	}
	t.hwnd = createPopup("EHVLiveTickerTicker", "EHV Ticker", ex, int32(x), int32(y), S(tickW), S(tickH(t)))
	pSetLayeredWindowAttr.Call(t.hwnd, 0, 255, 2) // LWA_ALPHA (ohne Aufruf bliebe das Fenster unsichtbar)
	setIcon(t.hwnd)
	tickers[free] = t
	pShowWindow.Call(t.hwnd, SW_SHOWNORMAL)
	syncOpenIDs()
	invalidate(hMain)
	if optMinMain {
		pShowWindow.Call(hMain, SW_MINIMIZE)
	}
	select {
	case tickReq <- id:
	default:
	}
}

func tickerBySlot(hwnd uintptr) (int, *ticker) {
	for s, t := range tickers {
		if t != nil && t.hwnd == hwnd {
			return s, t
		}
	}
	return -1, nil
}

func tickerClose(hwnd uintptr) {
	s, t := tickerBySlot(hwnd)
	if t == nil {
		return
	}
	r := windowRect(hwnd)
	if r.left > -10000 {
		iniWrite(iniFile, "Ticker", "X", strconv.Itoa(int(r.left)-s*int(S(28))))
		iniWrite(iniFile, "Ticker", "Y", strconv.Itoa(int(r.top)-s*int(S(28))))
	}
	tickers[s] = nil
	pDestroyWindow.Call(hwnd)
	syncOpenIDs()
	invalidate(hMain)
	if optRestore && tickerCount() == 0 {
		pShowWindow.Call(hMain, SW_RESTORE)
		pSetForegroundWindow.Call(hMain)
	}
}

func tickerResize(t *ticker) {
	pSetWindowPos.Call(t.hwnd, 0, 0, 0, uintptr(S(tickW)), uintptr(S(tickH(t))), SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE)
	invalidate(t.hwnd)
}

func toggleEvents(t *ticker) {
	t.showEv = !t.showEv
	t.evScroll = 0
	if t.showEv {
		t.evOpened = time.Now()
	}
	tickerResize(t)
}

// ---------------------------------------------------------------- Abruf-Thread
func tickerLoop() {
	last := time.Now()
	offline := false
	fetch := func(id string) {
		refsEnsure() // Schiedsrichter-Einteilung, höchstens alle 30 Min.
		d, err := apiGet("GetGameReport", "gameId="+id, false)
		res := tickResult{fail: true}
		if err == 1 {
			offline = true // keine Antwort vom Server
		} else if err == 0 {
			offline = false
			if r, ok := parseReport(id, d); ok {
				res = tickResult{rep: r, logos: map[string][]byte{}}
				for _, tid := range []string{r.homeID, r.awayID} {
					if tid == "" || tid == "0" {
						continue
					}
					b, ok := logoBytes[tid]
					if !ok {
						b = httpGetBin(logoBase + tid + ".png")
						if len(b) > 0 { // Netzfehler -> spaeter erneut versuchen
							logoBytes[tid] = b
						}
					}
					res.logos[tid] = b
				}
			}
		}
		tickMu.Lock()
		pendRep[id] = res
		tickMu.Unlock()
		pPostMessageW.Call(hMain, wmTick, 0, 0)
	}
	for {
		select {
		case id := <-tickReq:
			fetch(id)
		case <-time.After(time.Second):
			if time.Since(last) < time.Duration(optTickSec)*time.Second {
				continue
			}
			last = time.Now()
			tickMu.Lock()
			ids := append([]string{}, openIDs...)
			tickMu.Unlock()
			if len(ids) == 0 {
				continue
			}
			// war der Server zuletzt weg: erst kurz testen, sonst wartet jeder Ticker aufs Zeitlimit
			if offline && !serverOk() {
				tickMu.Lock()
				for _, id := range ids {
					pendRep[id] = tickResult{fail: true}
				}
				tickMu.Unlock()
				pPostMessageW.Call(hMain, wmTick, 0, 0)
				continue
			}
			for _, id := range ids {
				fetch(id)
				if offline {
					break // Rest beim naechsten Durchlauf (dann mit Kurztest)
				}
			}
		}
	}
}

// ---------------------------------------------------------------- Ergebnisse anwenden (UI-Thread)
func tickersApply() {
	tickMu.Lock()
	got := pendRep
	pendRep = map[string]tickResult{}
	tickMu.Unlock()
	for _, t := range tickers {
		if t == nil {
			continue
		}
		res, ok := got[t.id]
		if !ok {
			continue
		}
		if res.fail || res.rep == nil {
			t.upd = "⚠ Keine Verbindung · Stand bleibt · " + time.Now().Format("15:04:05")
			invalidate(t.hwnd)
			continue
		}
		for tid, b := range res.logos {
			if _, have := logoImg[tid]; !have && len(b) > 0 {
				logoImg[tid] = imageFromPNG(b) // nil = kein echtes Logo -> Kuerzel
			}
		}
		tickerApply(t, res.rep)
	}
	syncOpenIDs()
}

func tickerApply(t *ticker, r *report) {
	first := t.lastTot < 0 // beim allerersten Abruf keine Toene
	tot := r.hs + r.as
	snd := ""
	if t.lastStatus == 0 && r.status == 1 {
		snd = "bully"
	}
	if !first && r.pens > t.lastPen {
		snd = "strafe"
	}
	if !first && len(r.phase) >= 12 && r.phase[:12] == "INTERMISSION" && !(len(t.lastPhase) >= 12 && t.lastPhase[:12] == "INTERMISSION") {
		snd = "drittel"
	}
	if !first && tot > t.lastTot {
		t.goalUntil = time.Now().Add(highlightMs * time.Millisecond)
		snd = "tor"
	}
	if !first && r.status == 4 && !t.ended {
		snd = "spielende"
	}
	if snd != "" {
		soundPlay(snd)
	}
	if r.status != t.lastStatus { // vor dem Bully halb durchsichtig
		a := uintptr(255)
		if r.status == 0 {
			a = preBully
		}
		pSetLayeredWindowAttr.Call(t.hwnd, 0, a, 2)
	}
	t.lastTot, t.lastPen, t.lastPhase, t.lastStatus = tot, r.pens, r.phase, r.status
	t.ended = r.status == 4
	t.r = r
	if t.ended {
		t.upd = "Endstand · " + time.Now().Format("15:04:05")
	} else {
		t.upd = "Aktualisiert " + time.Now().Format("15:04:05")
	}
	tickerTitle(t)
	invalidate(t.hwnd)
}

func tickerTitle(t *ticker) {
	if t.r == nil {
		return
	}
	s := t.r.hShort + " " + strconv.Itoa(t.r.hs) + ":" + strconv.Itoa(t.r.as) + " " + t.r.aShort
	if time.Now().Before(t.goalUntil) {
		s = "TOR! " + s
	}
	setText(t.hwnd, s)
}

// alle 0,5 s: Tor-Markierung und automatisches Zuklappen
func tickersTimer() {
	for _, t := range tickers {
		if t == nil {
			continue
		}
		if !t.goalUntil.IsZero() && time.Now().After(t.goalUntil) {
			t.goalUntil = time.Time{}
			tickerTitle(t)
			invalidate(t.hwnd)
		}
		if optCollapse > 0 && t.showEv && time.Since(t.evOpened) >= time.Duration(optCollapse)*time.Second {
			toggleEvents(t)
		}
	}
}

// ---------------------------------------------------------------- Zeichnen
func paintTicker(t *ticker) func(c *canvas, w, h int) {
	return func(c *canvas, w, h int) {
		c.rect(0, 0, w, h, cTickBg)
		c.stripe(0, 0, w, 3)
		lg := t.league
		if lg == "" && t.r != nil {
			lg = t.r.division
		}
		c.line(14, 6, w-110, 28, lg, ui(8, false), 0x9AA1A9, DT_LEFT)
		if t.hover == "min" {
			c.round(w-70, 6, 30, 26, 7, 0x3A4048)
		}
		if t.hover == "close" {
			c.round(w-36, 6, 30, 26, 7, cRed)
		}
		c.line(w-70, 6, 30, 24, "–", ui(11, false), 0xC9CED4, DT_CENTER)
		c.line(w-36, 6, 30, 26, "✕", ui(9, false), 0xC9CED4, DT_CENTER)

		r := t.r
		if r == nil {
			c.line(0, 60, w, 40, t.upd, ui(10, false), 0x9AA1A9, DT_CENTER)
			return
		}
		// Logo-Plaketten (hell, rund) bzw. Kuerzel
		for i, side := range []struct{ id, short string }{{r.homeID, r.hShort}, {r.awayID, r.aShort}} {
			x := 44
			if i == 1 {
				x = w - 44 - 52
			}
			if im := logoImg[side.id]; im != nil {
				c.circle(x, 40, 52, 0xF5F5F5)
				c.image(im, x+6, 46, 40)
			} else {
				c.round(x, 40, 52, 52, 26, cTickBox)
				c.line(x-10, 40, 72, 52, side.short, ui(10, true), cWhite, DT_CENTER)
			}
		}
		// Spielstand (Klick: Ereignisse ein/aus)
		scol := uint32(cWhite)
		if !t.goalUntil.IsZero() {
			scol = cGoal
			c.round(118, 44, w-236, 44, 12, 0x3B3420)
		}
		score := strconv.Itoa(r.hs) + " : " + strconv.Itoa(r.as)
		if r.status == 0 {
			score = "– : –"
		}
		c.line(110, 38, w-220, 56, score, ui(26, true), scol, DT_CENTER)
		// Vereinsnamen bis 2 Zeilen, sonst kleinere Schrift
		for i, name := range []string{r.hLong, r.aLong} {
			x := 8
			if i == 1 {
				x = w/2 + 2
			}
			f := ui(8.5, true)
			if c.textHeight(w/2-10, name, f) > 2*c.textHeight(1000, "Ag", f) {
				f = ui(7.5, true)
			}
			c.text(x, 98, w/2-10, 36, name, f, cWhite, DT_CENTER|DT_WORDBREAK)
		}
		c.line(10, 136, w-20, 22, r.timeText, ui(10, true), cBlue, DT_CENTER)
		c.line(10, 158, w-20, 18, r.periods, ui(8, false), 0xB0B0B0, DT_CENTER)

		lbl := "▶  Ereignisse einblenden"
		if t.showEv {
			lbl = "▼  Ereignisse ausblenden"
		}
		ec := uint32(cBlue)
		if t.hover == "toggle" {
			ec = cWhite
		}
		c.line(14, 182, 180, 20, lbl, ui(8, true), ec, DT_LEFT)
		for _, k := range []struct {
			id, txt string
			x, w    int
		}{{"table", "Tabelle  ▸", 196, 76}, {"lineup", "Aufstellung  ▸", 276, 94}} {
			lc := uint32(cBlue)
			if t.hover == k.id {
				lc = cWhite
			}
			c.line(k.x, 182, k.w, 20, k.txt, ui(8, true), lc, DT_RIGHT)
		}
		c.line(14, 204, w-28, 18, t.upd, ui(7.5, false), 0x8A8A8A, DT_RIGHT)
		// Schiedsrichter (esrw.de): nur wenn die Seite gelesen ist und das Spiel im Zeitraum liegt
		if txt, dim, show := refLine(r.schedDate, r.schedTime, r.hLong, r.aLong); show {
			col := uint32(0xB8BEC6)
			if dim {
				col = 0x7D838B
			}
			c.line(14, 222, w-28, 16, txt, ui(7.5, false), col, DT_CENTER)
		}

		if !t.showEv {
			return
		}
		c.round(10, evTop, w-20, evBoxH, 10, cTickBox)
		lines := r.events
		if len(lines) == 0 {
			lines = []string{"Noch keine Tore oder Strafen."}
		}
		f := font(8.5, false, "Consolas")
		y := evTop + 8 - t.evScroll
		for _, l := range lines {
			hh := c.textHeight(w-40, l, f)
			if y >= evTop+4 && y+hh <= evTop+evBoxH-4 {
				col := uint32(0xE6E6E6)
				if len(l) >= 9 && l[6:9] == "TOR" {
					col = cGoal
				}
				c.text(20, y, w-40, hh, l, f, col, DT_WORDBREAK)
			}
			y += hh + 3
		}
	}
}

func tickHit(t *ticker, x, y int) string {
	w := tickW
	switch {
	case y >= 6 && y < 32 && x >= w-70 && x < w-40:
		return "min"
	case y >= 6 && y < 32 && x >= w-36 && x < w-6:
		return "close"
	case y >= 38 && y < 94 && x >= 110 && x < w-110:
		return "score"
	case y >= 180 && y < 204 && x >= 10 && x < 190:
		return "toggle"
	case y >= 180 && y < 204 && x >= 196 && x < 272:
		return "table"
	case y >= 180 && y < 204 && x >= 276 && x < 370:
		return "lineup"
	case t.showEv && y >= evTop && y < evTop+evBoxH:
		return "events"
	}
	return "" // alles andere: Fenster verschieben
}

func tickerProc(hwnd, msg, wParam, lParam uintptr) uintptr {
	_, t := tickerBySlot(hwnd)
	if t == nil {
		r, _, _ := pDefWindowProcW.Call(hwnd, msg, wParam, lParam)
		return r
	}
	switch uint32(msg) {
	case WM_PAINT:
		paintWindow(hwnd, paintTicker(t))
		return 0
	case WM_ERASEBKGND:
		return 1
	case WM_NCHITTEST:
		r := windowRect(hwnd)
		x, y := mouseXY(lParam)
		if tickHit(t, L(x-r.left), L(y-r.top)) == "" {
			return HTCAPTION
		}
		return HTCLIENT
	case WM_MOUSEMOVE:
		x, y := mouseXY(lParam)
		hv := tickHit(t, L(x), L(y))
		if hv != t.hover {
			t.hover = hv
			invalidate(hwnd)
			trackLeave(hwnd)
		}
		return 0
	case WM_SETCURSOR:
		if t.hover == "min" || t.hover == "close" || t.hover == "score" || t.hover == "toggle" || t.hover == "table" || t.hover == "lineup" {
			setHandCursor(true)
			return 1
		}
	case WM_MOUSELEAVE:
		t.hover = ""
		invalidate(hwnd)
		return 0
	case WM_LBUTTONDOWN:
		x, y := mouseXY(lParam)
		switch tickHit(t, L(x), L(y)) {
		case "min":
			pShowWindow.Call(hwnd, SW_MINIMIZE)
		case "close":
			tickerClose(hwnd)
		case "score", "toggle":
			toggleEvents(t)
		case "table":
			if t.div != "" {
				tableOpen(t.div, t.league)
			}
		case "lineup":
			lineupOpen(t.id, t.league)
		}
		return 0
	case WM_MOUSEWHEEL:
		if t.showEv {
			t.evScroll -= int(int16(wParam>>16)) / 120 * 38
			if t.evScroll < 0 {
				t.evScroll = 0
			}
			if t.r != nil && t.evScroll > len(t.r.events)*19 {
				t.evScroll = len(t.r.events) * 19
			}
			t.evOpened = time.Now()
			invalidate(hwnd)
		}
		return 0
	case WM_CLOSE:
		tickerClose(hwnd)
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, msg, wParam, lParam)
	return r
}

var _ = unsafe.Pointer(nil)
