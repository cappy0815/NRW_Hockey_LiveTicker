//go:build windows

// EHV-NRW Live-Ticker 3.0 (Go) – Vorlage: EHV_NRW_LiveTicker_v2.0.au3
// Etappe 1: Hauptliste, API, Server-Ausfall, Lastsenkung
// Etappe 2: Ticker-Fenster, Toene, eigenes flaches Design
package main

import (
	"fmt"
	"os"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"
)

const appVersion = "3.5"

const disclaimer = "Inoffizielles, privates Tool – es besteht keine Verbindung zum Eishockeyverband NRW (EHV NRW) oder zu hockeydata.net.\r\n\r\n" +
	"Angezeigt werden ausschließlich öffentlich zugängliche Daten, wie sie auch auf ehv-nrw.de und esrw.de zu sehen sind.\r\n\r\n" +
	"Die Nutzung ist nur für private Zwecke erlaubt."

const (
	wmData      = WM_APP + 1 // Arbeits-Thread: neue Liste
	wmStatus    = WM_APP + 2 // Arbeits-Thread: Statuszeile
	wmTick      = WM_APP + 3 // Ticker-Thread: neue Spielberichte
	upcomingSec = 1800       // Spielplan aller Ligen nur alle 30 Min.

	mainW, mainH = 820, 470
	titleH       = 48
	listTop      = 84
	rowH         = 34
)

// ---------------------------------------------------------------- Optionen (INI wie v2.0)
var (
	appDir, iniFile                                                 string
	refreshList                                                     = 120
	favOn                                                           = true
	favTerms                                                        = "Neuss"
	favRe                                                           *regexp.Regexp
	optTickSec                                                      = 15
	optOnTop, optEvDefault, optMinMain, optRestore                  bool
	optSndTor, optSndStrafe, optSndDrittel, optSndEnde, optSndBully bool
	optCollapse                                                     int
	appIcon, appIconSm                                              uintptr
)

func optionsLoad() {
	rd := func(k, d string) string { return iniRead(iniFile, "Optionen", k, d) }
	on := func(k, d string) bool { return rd(k, d) == "1" }
	refreshList, _ = strconv.Atoi(rd("ListeSek", "120"))
	switch refreshList { // alte Werte (z. B. 30 s aus v1.x) auf den Standard setzen
	case 60, 120, 300, 0:
	default:
		refreshList = 120
	}
	optTickSec, _ = strconv.Atoi(rd("TickerSek", "15"))
	if optTickSec < 5 {
		optTickSec = 15
	}
	favOn = on("Hervorheben", "1")
	favTerms = rd("HervorhebenBegriffe", "Neuss")
	favRe = favRegex(favTerms)
	optOnTop = on("Vordergrund", "1")
	optEvDefault = on("EreignisseAufgeklappt", "0")
	optSndTor, optSndStrafe, optSndDrittel = on("TonTor", "1"), on("TonStrafe", "1"), on("TonDrittel", "1")
	optSndEnde, optSndBully = on("TonSpielende", "1"), on("TonBully", "1")
	optCollapse, _ = strconv.Atoi(rd("EreignisseZuklappenSek", "0"))
	optMinMain = on("HauptMinimieren", "0")
	optRestore = on("HauptWiederOeffnen", "1")
	optUpdAuto = on("UpdatePruefen", "1")
	optRedPlus = on("RedPlusButton", "1")
}

func isFav(g game) bool { return favOn && favRe != nil && favRe.MatchString(g.home+"|"+g.away) }

// ---------------------------------------------------------------- Zustand
var (
	hMain uintptr

	mu         sync.Mutex
	busy       bool
	pendStatus string
	pendRows   []game
	pendMsg    string

	rows     []game // angezeigt (nur UI-Thread)
	status   string
	sel      = -1
	hover    string
	scrollY  int
	lastList time.Time

	// Spielplan-Stand (nur Arbeits-Thread)
	upGames      []game
	divsToday    []string
	todayKnown   bool
	todayDate    string
	lastUpcoming time.Time
	lastOk       string
	schedAll     []sgame // geschuetzt durch mu
)

func init() { runtime.LockOSThread() } // Fenster gehoeren dem Haupt-Thread

func main() {
	appDir = os.Getenv("APPDATA") + `\EHV-Live-Ticker\`
	os.MkdirAll(appDir, 0755)
	iniFile = appDir + "EHV_LiveTicker.ini"
	sndDir = appDir + `sounds\`
	optionsLoad()
	updLoad()
	initDPI()

	registerClass("EHVLiveTickerMain", mainProc)
	registerClass("EHVLiveTickerTicker", tickerProc)
	registerClass("EHVLiveTickerPanel", panelProc)
	w, h := S(mainW), S(mainH)
	hMain = createPopup("EHVLiveTickerMain", "EHV-NRW Live-Ticker", 0, (screenW()-w)/2, (screenH()-h)/2, w, h)

	// Icon aus der EXE (Ressource 1)
	hInst, _, _ := pGetModuleHandleW.Call(0)
	appIcon, _, _ = pLoadImageW.Call(hInst, 1, 1, 0, 0, 0x40|0x8000) // LR_DEFAULTSIZE|LR_SHARED
	appIconSm, _, _ = pLoadImageW.Call(hInst, 1, 1, uintptr(S(16)), uintptr(S(16)), 0x8000)
	setIcon(hMain)
	pShowWindow.Call(hMain, SW_SHOWNORMAL)

	if iniRead(iniFile, "Info", "HinweisGelesen", "0") != "1" {
		hintOpen()
		iniWrite(iniFile, "Info", "HinweisGelesen", "1")
	}
	soundsEnsure()

	pSetTimer.Call(hMain, 1, 500, 0)
	go tickerLoop()
	go logoLoop()
	startWork(true, true)

	var m msgT
	for {
		r, _, _ := pGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
}

func setIcon(h uintptr) {
	if appIcon != 0 {
		sendMsg(h, WM_SETICON, 0, appIconSm)
		sendMsg(h, WM_SETICON, 1, appIcon)
	}
}

// ---------------------------------------------------------------- Arbeits-Thread Spielliste
func setStatus(s string) {
	mu.Lock()
	pendStatus = s
	mu.Unlock()
	pPostMessageW.Call(hMain, wmStatus, 0, 0)
}

// Abruf im Hintergrund, damit das Fenster nie einfriert. cfg = Ligen neu lesen, full = Spielplan neu laden
func startWork(cfg, full bool) {
	mu.Lock()
	if busy {
		mu.Unlock()
		return
	}
	busy = true
	mu.Unlock()
	lastList = time.Now()
	go func() {
		if cfg {
			setStatus("Lese Ligen von ehv-nrw.de …")
			loadConfig()
		}
		if full || todayDate != time.Now().Format("20060102") || time.Since(lastUpcoming) >= upcomingSec*time.Second {
			upcomingRefresh()
		}
		ask := divs
		if todayKnown {
			ask = divsToday
		}
		live, errs, ok := loadLive(ask, setStatus)
		mu.Lock()
		defer func() { mu.Unlock(); pPostMessageW.Call(hMain, wmData, boolPtr(ok), 0) }()
		busy = false
		if !ok {
			pendMsg = offlineText()
			return
		}
		all := append([]game{}, live...)
		seen := map[string]bool{}
		for _, g := range live {
			seen[g.id] = true
		}
		nUp := 0
		for _, g := range upGames {
			if seen[g.id] {
				continue // inzwischen live
			}
			if nUp == 0 {
				all = append(all, game{league: "HEUTE NOCH", kind: 2})
			}
			nUp++
			all = append(all, g)
		}
		// RED+-Link: Live-Abfrage liefert ihn nicht, der Spielplan schon
		redByID := map[string]string{}
		for i := range schedAll {
			if schedAll[i].redURL != "" {
				redByID[schedAll[i].id] = schedAll[i].redURL
			}
		}
		for i := range all {
			if all[i].kind != 2 && all[i].redURL == "" {
				all[i].redURL = redByID[all[i].id]
			}
		}
		nFav := 0
		for i := range all {
			if all[i].kind != 2 && isFav(all[i]) {
				all[i].fav = true
				nFav++
			}
		}
		lastOk = time.Now().Format("15:04:05")
		msg := "Kein Spiel live"
		if len(live) > 0 {
			msg = fmt.Sprintf("%d live", len(live))
		}
		msg += fmt.Sprintf(" · %d heute noch · Stand %s", nUp, lastOk)
		if nFav > 0 {
			msg += fmt.Sprintf(" · %d hervorgehoben", nFav)
		}
		if todayKnown {
			msg += fmt.Sprintf(" · %d von %d Ligen spielen heute", len(ask), len(divs))
		}
		if errs > 0 {
			msg += fmt.Sprintf(" · %d Abruf(e) fehlgeschlagen", errs)
		}
		pendRows, pendMsg = all, msg
	}()
}

func boolPtr(b bool) uintptr {
	if b {
		return 1
	}
	return 0
}

func upcomingRefresh() {
	res, ok := loadUpcoming(setStatus)
	if !ok {
		return // Server weg -> alten Spielplan behalten
	}
	lastUpcoming = time.Now()
	if res.haveAny {
		todayDate = time.Now().Format("20060102")
	}
	upGames, todayKnown = res.games, res.todayKnown
	mu.Lock()
	schedAll = res.all
	mu.Unlock()
	if todayKnown {
		divsToday = res.divsToday
	}
}

func offlineText() string {
	s := "⚠ Server nicht erreichbar (hockeydata.net)"
	if lastOk != "" {
		s += " · angezeigt: Stand " + lastOk
	}
	return s + " · neuer Versuch automatisch"
}

// ---------------------------------------------------------------- Hauptfenster zeichnen
func listH() int { return mainH - 74 - listTop }

func clampScroll() {
	max := len(rows)*rowH - listH()
	if max < 0 {
		max = 0
	}
	if scrollY > max {
		scrollY = max
	}
	if scrollY < 0 {
		scrollY = 0
	}
}

type col struct {
	x, w  int
	title string
	align uintptr
}

// Spalten: rechts neben SPIELZEIT ist Platz für den RED+-Knopf (x 766 bis 802)
var cols = []col{{34, 222, "LIGA", DT_LEFT}, {262, 166, "HEIM", DT_LEFT}, {434, 166, "GAST", DT_LEFT},
	{606, 52, "STAND", DT_CENTER}, {664, 94, "SPIELZEIT", DT_LEFT}}

const redPillX = 766 // linke Kante des RED+-Knopfs in der Liste

func paintMain(c *canvas, w, h int) {
	c.rect(0, 0, w, h, cBg)
	// Titelleiste
	c.rect(0, 0, w, titleH, cDark)
	c.appIcon(12, 8, 32)
	c.line(54, 0, 300, titleH, "EHV-NRW Live-Ticker", ui(13, true), cWhite, DT_LEFT)
	c.line(266, 2, 300, titleH, "laufende Spiele", ui(9, false), 0xA9B0B8, DT_LEFT)
	c.round(w-262, 14, 72, 20, 10, cDark2)
	c.line(w-262, 14, 72, 20, "v"+appVersion, ui(7.5, true), 0xC9CED4, DT_CENTER)
	if updAvailable() {
		c.pill(w-362, 11, 92, 28, "Update "+updLatestText(), cGreen, hover == "upd")
	}
	for i, k := range []struct{ id, t string }{{"help", "?"}, {"opt", "⚙"}} {
		x := w - 172 + i*42
		if hover == k.id {
			c.round(x, 8, 36, 32, 8, 0x3A4048)
		}
		c.line(x, 8, 36, 30, k.t, font(12, false, "Segoe UI Symbol"), cWhite, DT_CENTER)
	}
	winButtons(c, w, hover)
	c.stripe(0, titleH, w, 3)

	// Spaltenkoepfe
	for _, cl := range cols {
		c.line(cl.x, 60, cl.w, 20, cl.title, ui(7.5, true), 0x9AA1A9, cl.align)
	}
	if len(rows) == 0 {
		c.line(0, listTop+60, w, 30, "Heute läuft kein Spiel mehr.", ui(11, false), cGreyTxt, DT_CENTER)
		c.pill((w-210)/2, listTop+104, 210, 34, "Nächste Spiele anzeigen", cGreen, hover == "plan")
	}
	// Zeilen (nur der sichtbare Bereich)
	lh := listH()
	for i, g := range rows {
		y := listTop + i*rowH - scrollY
		if y+rowH <= listTop || y >= listTop+lh {
			continue
		}
		if y < listTop || y+rowH > listTop+lh { // angeschnittene Zeilen weglassen (kein Clipping noetig)
			continue
		}
		if g.kind == 2 {
			c.line(34, y+8, 200, rowH-8, g.league, ui(7.5, true), 0x9AA1A9, DT_LEFT)
			c.rect(120, y+rowH/2+4, w-140, 1, cLine)
			continue
		}
		bg := uint32(cWhite)
		switch {
		case i == sel:
			bg = cSel
		case g.fav:
			bg = cFav
		case hover == "row"+strconv.Itoa(i):
			bg = cHover
		}
		c.round(12, y+2, w-24, rowH-4, 7, bg)
		if tickerOpen(g.id) { // ● = Ticker offen
			c.circle(19, y+rowH/2-4, 8, cGreen)
		}
		txt := uint32(cDark)
		if g.kind == 1 {
			txt = 0x9A9A9A
		}
		c.line(cols[0].x, y, cols[0].w, rowH, g.league, ui(8.5, false), txt, DT_LEFT)
		c.line(cols[1].x, y, cols[1].w, rowH, g.home, ui(9, g.kind == 0), txt, DT_LEFT)
		c.line(cols[2].x, y, cols[2].w, rowH, g.away, ui(9, g.kind == 0), txt, DT_LEFT)
		c.line(cols[3].x, y, cols[3].w, rowH, g.score, ui(10, true), txt, DT_CENTER)
		x := cols[4].x
		if g.kind == 0 {
			c.circle(x, y+rowH/2-3, 6, cRed)
			x += 12
		}
		c.line(x, y, cols[4].x+cols[4].w-x, rowH, g.timeText, ui(8.5, false), txt, DT_LEFT)
		if g.redURL != "" && optRedPlus {
			col := uint32(cRed)
			if hover == "red"+strconv.Itoa(i) {
				col = lighter(cRed)
			}
			c.round(redPillX, y+rowH/2-10, 36, 20, 10, col)
			c.line(redPillX, y+rowH/2-10, 36, 20, "RED+", ui(7, true), cWhite, DT_CENTER)
		}
	}
	// Bildlaufleiste (nur wenn noetig)
	if total := len(rows) * rowH; total > lh {
		th := lh * lh / total
		ty := listTop + scrollY*(lh-th)/(total-lh)
		c.round(w-8, ty, 4, th, 2, 0xC2C7CE)
	}
	// Fusszeile
	fy := h - 62
	c.rect(0, fy-10, w, 1, cLine)
	c.pill(20, fy, 128, 32, "Aktualisieren", cGreen, hover == "refresh")
	c.pill(158, fy, 138, 32, "Ticker öffnen", cRed, hover == "open")
	c.pill(306, fy, 118, 32, "Spielplan", cDark2, hover == "plan")
	c.line(438, fy, w-458, 32, status, ui(8.5, false), cGreyTxt, DT_LEFT)
	c.line(20, h-26, w-40, 18, "Doppelklick öffnet den Ticker · ● Ticker offen · gelb = hervorgehoben · Quelle: ehv-nrw.de / hockeydata.net",
		ui(7.5, false), 0x9AA1A9, DT_LEFT)
}

// Minimieren und Schliessen, gemeinsam fuer alle Fenster
func winButtons(c *canvas, w int, hv string) {
	if hv == "min" {
		c.round(w-88, 8, 36, 32, 8, 0x3A4048)
	}
	if hv == "close" {
		c.round(w-48, 8, 36, 32, 8, cRed)
	}
	c.line(w-88, 8, 36, 30, "–", ui(12, false), cWhite, DT_CENTER)
	c.line(w-48, 8, 36, 30, "✕", ui(10, false), cWhite, DT_CENTER)
}

func mainHit(x, y int) string {
	w, h := mainW, mainH
	switch {
	case y >= 8 && y < 40 && x >= w-88 && x < w-52:
		return "min"
	case y >= 8 && y < 40 && x >= w-48 && x < w-12:
		return "close"
	case y >= 8 && y < 40 && x >= w-172 && x < w-136:
		return "help"
	case y >= 8 && y < 40 && x >= w-130 && x < w-94:
		return "opt"
	case y >= 11 && y < 39 && x >= w-362 && x < w-270 && updAvailable():
		return "upd"
	case y < titleH:
		return "caption"
	case y >= h-62 && y < h-30 && x >= 20 && x < 148:
		return "refresh"
	case y >= h-62 && y < h-30 && x >= 158 && x < 296:
		return "open"
	case y >= h-62 && y < h-30 && x >= 306 && x < 424:
		return "plan"
	case len(rows) == 0 && y >= listTop+104 && y < listTop+138 && x >= (w-210)/2 && x < (w+210)/2:
		return "plan"
	case y >= listTop && y < listTop+listH() && x >= 12 && x < w-12:
		i := (y - listTop + scrollY) / rowH
		if i < len(rows) && rows[i].kind != 2 {
			if rows[i].redURL != "" && optRedPlus && x >= redPillX-4 && x < redPillX+40 {
				return "red" + strconv.Itoa(i)
			}
			return "row" + strconv.Itoa(i)
		}
	}
	return ""
}

func openSelected() {
	if sel < 0 || sel >= len(rows) || rows[sel].id == "" {
		status = "Bitte zuerst ein Spiel in der Liste auswählen."
		invalidate(hMain)
		return
	}
	g := rows[sel]
	tickerOpenGame(g.id, g.league, g.div)
}

func mainProc(hwnd, msg, wParam, lParam uintptr) uintptr {
	switch uint32(msg) {
	case WM_PAINT:
		paintWindow(hwnd, paintMain)
		return 0
	case WM_ERASEBKGND:
		return 1
	case WM_NCHITTEST:
		r := windowRect(hwnd)
		x, y := mouseXY(lParam)
		if mainHit(L(x-r.left), L(y-r.top)) == "caption" {
			return HTCAPTION
		}
		return HTCLIENT
	case WM_MOUSEMOVE:
		x, y := mouseXY(lParam)
		hv := mainHit(L(x), L(y))
		if hv != hover {
			hover = hv
			invalidate(hwnd)
			trackLeave(hwnd)
		}
		return 0
	case WM_SETCURSOR:
		if hover == "refresh" || hover == "open" || hover == "plan" || hover == "help" || hover == "opt" || hover == "min" || hover == "close" || hover == "upd" {
			setHandCursor(true)
			return 1
		}
	case WM_MOUSELEAVE:
		hover = ""
		invalidate(hwnd)
		return 0
	case WM_LBUTTONDOWN, WM_LBUTTONDBLCLK:
		x, y := mouseXY(lParam)
		hv := mainHit(L(x), L(y))
		switch {
		case hv == "min":
			pShowWindow.Call(hwnd, SW_MINIMIZE)
		case hv == "close":
			pDestroyWindow.Call(hwnd)
		case hv == "refresh":
			startWork(false, true)
		case hv == "open":
			openSelected()
		case hv == "plan":
			schedOpen()
		case hv == "upd":
			updOpenPanel()
		case hv == "help":
			helpOpen()
		case hv == "opt":
			optionsOpen()
		case len(hv) > 3 && hv[:3] == "red":
			if i, err := strconv.Atoi(hv[3:]); err == nil && i < len(rows) && strings.HasPrefix(rows[i].redURL, "https://redirect.red.sport/") {
				shellOpen(rows[i].redURL)
			}
		case len(hv) > 3 && hv[:3] == "row":
			sel, _ = strconv.Atoi(hv[3:])
			if uint32(msg) == WM_LBUTTONDBLCLK {
				openSelected()
			}
		}
		invalidate(hwnd)
		return 0
	case WM_MOUSEWHEEL:
		d := int(int16(wParam >> 16))
		scrollY -= d / 120 * rowH * 2
		clampScroll()
		invalidate(hwnd)
		return 0
	case WM_TIMER:
		iconic, _, _ := pIsIconic.Call(hMain)
		// Liste nur automatisch aktualisieren, wenn das Hauptfenster nicht minimiert ist
		if refreshList > 0 && iconic == 0 && time.Since(lastList) >= time.Duration(refreshList)*time.Second {
			startWork(false, false)
		}
		tickersTimer()
		updTimer()
		return 0
	case wmStatus:
		mu.Lock()
		status = pendStatus
		mu.Unlock()
		invalidate(hwnd)
		return 0
	case wmData:
		mu.Lock()
		r, m := pendRows, pendMsg
		mu.Unlock()
		if wParam == 1 {
			selID := ""
			if sel >= 0 && sel < len(rows) {
				selID = rows[sel].id
			}
			rows, sel = r, -1
			for i, g := range rows {
				if selID != "" && g.id == selID {
					sel = i
				}
			}
			clampScroll()
		}
		status = m
		invalidate(hwnd)
		schedBuild()
		return 0
	case wmPanel:
		panelsApply(wParam)
		return 0
	case wmLogo:
		logosApply()
		return 0
	case wmTick:
		tickersApply()
		return 0
	case wmUpd:
		updDone(wParam == 1)
		return 0
	case 0x0100: // WM_KEYDOWN
		if wParam == 0x70 { // F1
			helpOpen()
		}
		return 0
	case WM_DESTROY:
		pPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, msg, wParam, lParam)
	return r
}
