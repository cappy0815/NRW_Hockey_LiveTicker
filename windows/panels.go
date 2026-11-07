//go:build windows

// Etappe 3: Spielplan, Steckbrief, Tabelle, Aufstellung – helle Fenster im gleichen Design.
// Alle vier nutzen ein gemeinsames Geruest (Titelleiste, Schliessen, Verschieben, Mausrad).
package main

import (
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const (
	wmPanel = WM_APP + 4 // Tabelle/Aufstellung geladen
	wmLogo  = WM_APP + 5 // Wappen geladen
	pTitleH = 44
)

var (
	shell32       = syscall.NewLazyDLL("shell32.dll")
	pShellExecute = shell32.NewProc("ShellExecuteW")
	pCreateDCmem  = pCreateCompatibleDC
)

type panel struct {
	hwnd           uintptr
	kind           string
	title          string
	w, h           int
	hover          string
	scroll         int
	viewTop, viewH int
	contentH       int
}

var panels = map[string]*panel{}

// ---------------------------------------------------------------- Wappen im Hintergrund laden
var (
	logoMu   sync.Mutex
	logoPend = map[string]bool{}
	logoDone = map[string][]byte{}
	logoCh   = make(chan string, 512)
)

func requestLogo(id string) *image {
	if id == "" || id == "0" {
		return nil
	}
	if im, ok := logoImg[id]; ok {
		return im
	}
	logoMu.Lock()
	if !logoPend[id] {
		select {
		case logoCh <- id:
			logoPend[id] = true
		default:
		}
	}
	logoMu.Unlock()
	return nil
}

func logoLoop() {
	for id := range logoCh {
		b := httpGetBin(logoBase + id + ".png")
		logoMu.Lock()
		logoDone[id] = b
		logoMu.Unlock()
		pPostMessageW.Call(hMain, wmLogo, 0, 0)
	}
}

func logosApply() {
	logoMu.Lock()
	for id, b := range logoDone {
		if len(b) > 0 {
			if _, have := logoImg[id]; !have {
				logoImg[id] = imageFromPNG(b) // nil = kein echtes Logo
			}
		} else {
			logoPend[id] = false // Netzfehler -> spaeter erneut versuchen
		}
	}
	logoDone = map[string][]byte{}
	logoMu.Unlock()
	for _, p := range panels {
		invalidate(p.hwnd)
	}
}

// ---------------------------------------------------------------- Geruest
func panelOpen(kind, title string, w, h int, topmost bool) *panel {
	if p := panels[kind]; p != nil {
		pDestroyWindow.Call(p.hwnd)
	}
	p := &panel{kind: kind, title: title, w: w, h: h}
	ex := uint32(0)
	if topmost {
		ex = WS_EX_TOPMOST
	}
	panels[kind] = p
	p.hwnd = createPopup("EHVLiveTickerPanel", title+" – EHV-NRW Live-Ticker", ex, (screenW()-S(w))/2, (screenH()-S(h))/2, S(w), S(h))
	setIcon(p.hwnd)
	pShowWindow.Call(p.hwnd, SW_SHOWNORMAL)
	return p
}

func panelByHwnd(h uintptr) *panel {
	for _, p := range panels {
		if p.hwnd == h {
			return p
		}
	}
	return nil
}

func panelClose(p *panel) {
	delete(panels, p.kind)
	pDestroyWindow.Call(p.hwnd)
}

func paintPanel(p *panel) func(c *canvas, w, h int) {
	return func(c *canvas, w, h int) {
		c.rect(0, 0, w, h, cWhite)
		c.rect(0, 0, w, pTitleH, cDark)
		c.appIcon(12, 9, 26)
		c.line(48, 0, w-110, pTitleH, p.title, ui(12, true), cWhite, DT_LEFT)
		if p.hover == "close" {
			c.round(w-48, 6, 36, 32, 8, cRed)
		}
		c.line(w-48, 6, 36, 30, "✕", ui(10, false), cWhite, DT_CENTER)
		c.stripe(0, pTitleH, w, 3)
		switch p.kind {
		case "sched":
			paintSched(c, p, w, h)
		case "sheet":
			paintSheet(c, p, w, h)
		case "table":
			paintTable(c, p, w, h)
		case "lineup":
			paintLineup(c, p, w, h)
		case "opt":
			paintOptions(c, p, w, h)
		case "help", "log", "hint", "upd":
			paintDoc(c, p, w, h)
		}
		// Bildlaufleiste
		if p.viewH > 0 && p.contentH > p.viewH {
			th := p.viewH * p.viewH / p.contentH
			ty := p.viewTop + p.scroll*(p.viewH-th)/(p.contentH-p.viewH)
			c.round(w-8, ty, 4, th, 2, 0xC2C7CE)
		}
	}
}

func panelHit(p *panel, x, y int) string {
	if y >= 6 && y < 38 && x >= p.w-48 && x < p.w-12 {
		return "close"
	}
	if y < pTitleH {
		return "caption"
	}
	switch p.kind {
	case "sched":
		return schedHit(p, x, y)
	case "sheet":
		return sheetHit(p, x, y)
	case "opt":
		return optHit(p, x, y)
	case "table", "lineup", "help", "log", "hint", "upd":
		return btnHit(p, x, y)
	}
	return ""
}

func panelProc(hwnd, msg, wParam, lParam uintptr) uintptr {
	p := panelByHwnd(hwnd)
	if p == nil {
		r, _, _ := pDefWindowProcW.Call(hwnd, msg, wParam, lParam)
		return r
	}
	switch uint32(msg) {
	case WM_PAINT:
		paintWindow(hwnd, paintPanel(p))
		return 0
	case WM_ERASEBKGND:
		return 1
	case WM_NCHITTEST:
		r := windowRect(hwnd)
		x, y := mouseXY(lParam)
		if panelHit(p, L(x-r.left), L(y-r.top)) == "caption" {
			return HTCAPTION
		}
		return HTCLIENT
	case WM_MOUSEMOVE:
		x, y := mouseXY(lParam)
		if hv := panelHit(p, L(x), L(y)); hv != p.hover {
			p.hover = hv
			invalidate(hwnd)
			trackLeave(hwnd)
		}
		return 0
	case WM_SETCURSOR:
		if p.hover != "" && p.hover != "caption" && !strings.HasPrefix(p.hover, "row") {
			setHandCursor(true)
			return 1
		}
	case WM_MOUSELEAVE:
		p.hover = ""
		invalidate(hwnd)
		return 0
	case WM_LBUTTONDOWN, WM_LBUTTONDBLCLK:
		x, y := mouseXY(lParam)
		hv := panelHit(p, L(x), L(y))
		if hv == "close" {
			panelClose(p)
			return 0
		}
		panelClick(p, hv, uint32(msg) == WM_LBUTTONDBLCLK)
		invalidate(hwnd)
		return 0
	case WM_MOUSEWHEEL:
		p.scroll -= int(int16(wParam>>16)) / 120 * 64
		if max := p.contentH - p.viewH; p.scroll > max {
			p.scroll = max
		}
		if p.scroll < 0 {
			p.scroll = 0
		}
		invalidate(hwnd)
		return 0
	case WM_CLOSE:
		panelClose(p)
		return 0
	case 0x0102: // WM_CHAR
		if p.kind == "opt" {
			optChar(p, uint16(wParam))
		}
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, msg, wParam, lParam)
	return r
}

func panelClick(p *panel, hv string, dbl bool) {
	switch p.kind {
	case "sched":
		schedClick(p, hv, dbl)
	case "sheet":
		sheetClick(hv)
	case "table":
		if hv == "btn0" {
			panelClose(p)
		}
	case "lineup":
		if hv == "btn0" {
			lineupLoad()
		} else if hv == "btn1" {
			panelClose(p)
		}
	case "opt":
		optClick(p, hv)
	case "help":
		if hv == "btn0" {
			changelogOpen()
		} else if hv == "btn1" {
			panelClose(p)
		}
	case "upd":
		switch hv {
		case "btn0":
			shellOpen(updDownloadURL())
			panelClose(p)
		case "btn1":
			shellOpen(updProjectPage)
		case "btn2":
			panelClose(p)
		}
	case "log", "hint":
		if hv == "btn0" {
			panelClose(p)
		}
	}
}

// Knoepfe unten (rechtsbuendig) fuer Tabelle/Aufstellung
type btn struct {
	label string
	col   uint32
}

func panelBtns(p *panel) []btn {
	switch p.kind {
	case "lineup":
		return []btn{{"Aktualisieren", cDark2}, {"Schließen", cGreen}}
	case "help":
		return []btn{{"Changelog", cDark2}, {"Alles klar", cGreen}}
	case "hint":
		return []btn{{"Verstanden", cGreen}}
	case "upd":
		return []btn{{"Herunterladen", cGreen}, {"Projektseite", cDark2}, {"Später", cDark2}}
	}
	return []btn{{"Schließen", cGreen}}
}

func btnHit(p *panel, x, y int) string {
	bs := panelBtns(p)
	for i := range bs {
		bx := p.w - 20 - (len(bs)-i)*120 + 10
		if y >= p.h-46 && y < p.h-16 && x >= bx && x < bx+110 {
			return "btn" + strconv.Itoa(i)
		}
	}
	return ""
}

func drawBtns(c *canvas, p *panel) {
	bs := panelBtns(p)
	for i, b := range bs {
		bx := p.w - 20 - (len(bs)-i)*120 + 10
		c.pill(bx, p.h-46, 110, 30, b.label, b.col, p.hover == "btn"+strconv.Itoa(i))
	}
}

func shellOpen(target string) {
	pShellExecute.Call(0, uintptr(unsafe.Pointer(u16("open"))), uintptr(unsafe.Pointer(u16(target))), 0, 0, SW_SHOWNORMAL)
}

// ================================================================= Spielplan
var (
	schedTab    int
	schedList   []srow
	schedSel    = -1
	schedStatus string
	schedLoaded bool
)

const sRowH = 32

func schedOpen() {
	p := panelOpen("sched", "Spielplan", 860, 600, false)
	p.viewTop, p.viewH = 116, 600-116-14
	schedBuild()
	if !schedLoaded {
		schedStatus = "Lade Spielplan aller Ligen …"
		startWork(false, true)
	}
}

func schedBuild() {
	p := panels["sched"]
	if p == nil {
		return
	}
	mu.Lock()
	all := schedAll
	mu.Unlock()
	schedLoaded = len(all) > 0
	schedSel = -1
	if schedTab == 2 && favRe == nil {
		schedList = nil
		schedStatus = "Keine Teams hervorgehoben – Begriffe in der INI unter „HervorhebenBegriffe“ (Optionen folgen in Etappe 4)."
		invalidate(p.hwnd)
		return
	}
	var n int
	schedList, n = schedRows(all, schedTab, func(s string) bool { return favOn && favRe != nil && favRe.MatchString(s) })
	switch schedTab {
	case 0:
		schedStatus = fmt.Sprintf("%d Spiele in den nächsten 7 Tagen", n)
	case 1:
		schedStatus = fmt.Sprintf("%d Ergebnisse der letzten 7 Tage", n)
	case 2:
		schedStatus = "„" + favTerms + "“: kommende Spiele und letzte Ergebnisse"
	case 3:
		schedStatus = fmt.Sprintf("%d Ligen · Klick zeigt die Tabelle", n)
	}
	if !schedLoaded {
		schedStatus = "Lade Spielplan aller Ligen …"
	}
	if schedTab != 3 {
		schedStatus += " · Doppelklick = Steckbrief"
	}
	p.contentH = len(schedList) * sRowH
	if p.scroll > p.contentH-p.viewH {
		p.scroll = 0
	}
	invalidate(p.hwnd)
}

var schedTabs = []string{"Nächste", "Ergebnisse", "Meine Spiele", "Tabellen"}

func paintSched(c *canvas, p *panel, w, h int) {
	for i, t := range schedTabs {
		col := uint32(cDark2)
		if i == schedTab {
			col = cGreen
		}
		c.pill(20+i*140, 58, 130, 30, t, col, p.hover == "tab"+strconv.Itoa(i))
	}
	c.pill(w-150, 58, 130, 30, "Aktualisieren", cDark2, p.hover == "reload")
	c.line(22, 92, w-44, 20, schedStatus, ui(8.5, false), cGreyTxt, DT_LEFT)
	for i, r := range schedList {
		y := p.viewTop + i*sRowH - p.scroll
		if y < p.viewTop || y+sRowH > p.viewTop+p.viewH {
			continue
		}
		switch r.kind {
		case 1:
			c.round(12, y+2, w-24, sRowH-4, 6, 0xEEF0F3)
			c.line(24, y, w-48, sRowH, r.text, ui(8.5, true), 0x7C746E, DT_LEFT)
			continue
		case 2:
			c.round(12, y+2, w-24, sRowH-4, 6, cRed)
			c.line(24, y, w-48, sRowH, r.text, ui(8.5, true), cWhite, DT_LEFT)
			continue
		case 3:
			bg := uint32(cWhite)
			switch {
			case r.fav:
				bg = cFav
			case p.hover == "lig"+strconv.Itoa(i):
				bg = cHover
			}
			c.round(12, y+2, w-24, sRowH-4, 6, bg)
			name := r.text
			if r.fav {
				name = "★ " + name
			}
			c.line(24, y, w-330, sRowH, name, ui(9, r.fav), cDark, DT_LEFT)
			c.line(w-320, y, 290, sRowH, r.sub, ui(8, false), cGreyTxt, DT_RIGHT)
			continue
		}
		g := r.g
		bg := uint32(cWhite)
		switch {
		case i == schedSel:
			bg = cSel
		case favOn && favRe != nil && favRe.MatchString(g.home+"|"+g.away):
			bg = cFav
		case p.hover == "row"+strconv.Itoa(i):
			bg = cHover
		}
		c.round(12, y+2, w-24, sRowH-4, 6, bg)
		left, lc := g.time, uint32(cGreen)
		if left == "" {
			left = "–"
		}
		if g.status == 1 {
			left, lc = "LIVE", cRed
		} else if g.status == 4 {
			lc = cDark
		}
		c.line(22, y, 56, sRowH, left, ui(8.5, true), lc, DT_LEFT)
		for k, t := range []struct{ id, name string }{{g.hID, g.home}, {g.aID, g.away}} {
			x := 80 + k*216
			if im := requestLogo(t.id); im != nil {
				c.image(im, x, y+6, 20)
			}
			c.line(x+26, y, 186, sRowH, t.name, ui(9, false), cDark, DT_LEFT)
		}
		score := "–"
		if g.status != 0 {
			score = fmt.Sprintf("%d:%d", g.hs, g.as)
			if g.extra != "" {
				score += " " + g.extra
			}
		}
		c.line(512, y, 70, sRowH, score, ui(9, true), cDark, DT_CENTER)
		c.line(588, y, 150, sRowH, leagueShort(g.league, g.divName), ui(8, false), cGreyTxt, DT_LEFT)
		c.line(742, y, w-756, sRowH, hallShort(g.hall), ui(8, false), cGreyTxt, DT_LEFT)
	}
}

func schedHit(p *panel, x, y int) string {
	if y >= 58 && y < 88 {
		for i := range schedTabs {
			if x >= 20+i*140 && x < 150+i*140 {
				return "tab" + strconv.Itoa(i)
			}
		}
		if x >= p.w-150 && x < p.w-20 {
			return "reload"
		}
	}
	if y >= p.viewTop && y < p.viewTop+p.viewH {
		i := (y - p.viewTop + p.scroll) / sRowH
		if i < len(schedList) && schedList[i].kind == 0 {
			return "row" + strconv.Itoa(i)
		}
		if i < len(schedList) && schedList[i].kind == 3 {
			return "lig" + strconv.Itoa(i)
		}
	}
	return ""
}

func schedClick(p *panel, hv string, dbl bool) {
	switch {
	case strings.HasPrefix(hv, "tab"):
		schedTab, _ = strconv.Atoi(hv[3:])
		p.scroll = 0
		schedBuild()
	case hv == "reload":
		schedStatus = "Lade Spielplan aller Ligen …"
		startWork(false, true)
	case strings.HasPrefix(hv, "lig"):
		if i, err := strconv.Atoi(hv[3:]); err == nil && i < len(schedList) {
			tableOpen(schedList[i].div, schedList[i].text)
		}
	case strings.HasPrefix(hv, "row"):
		schedSel, _ = strconv.Atoi(hv[3:])
		if dbl {
			g := *schedList[schedSel].g
			sheetOpen(&g)
		}
	}
}

// ================================================================= Steckbrief
var sheetG *sgame

type kv struct{ k, v string }

func sheetLines(g *sgame) []kv {
	when := g.date
	if len(g.date) == 10 {
		when = dayLabel(g.date) + g.date[6:]
	}
	switch {
	case g.status == 4:
		e := "Endstand"
		if g.extra != "" {
			e += " " + g.extra
		}
		when = e + " · " + when
	case g.status == 1:
		when = "LIVE · " + when
	case g.time != "":
		when += " · " + g.time + " Uhr"
	}
	l := []kv{{"Datum", when}}
	if g.status != 0 && g.periods != "" {
		l = append(l, kv{"Drittel", g.periods})
	}
	if g.scorersH != "" {
		l = append(l, kv{"Tore " + g.hShort, g.scorersH})
	}
	if g.scorersA != "" {
		l = append(l, kv{"Tore " + g.aShort, g.scorersA})
	}
	return l
}

// Hoehe der Zeilen messen (Speicher-DC mit derselben Schrift)
func measureLines(lines []kv, extra string) []int {
	dc, _, _ := pCreateDCmem.Call(0)
	defer pDeleteDC.Call(dc)
	c := &canvas{dc}
	var hs []int
	for _, l := range lines {
		hs = append(hs, c.textHeight(328, l.v, ui(8.5, false)))
	}
	hs = append(hs, c.textHeight(328, extra, ui(8.5, false)))
	return hs
}

func sheetLayout(g *sgame) (lines []kv, hs []int, hallTxt string, btnY int) {
	lines = sheetLines(g)
	if g.hall != "" {
		hallTxt = g.hall
		if g.address != "" {
			hallTxt += "\n" + g.address
		}
		hallTxt += "  ›"
	}
	hs = measureLines(lines, hallTxt)
	y := 206
	for _, h := range hs[:len(lines)] {
		y += h + 6
	}
	if hallTxt != "" {
		y += hs[len(lines)] + 10
	}
	return lines, hs, hallTxt, y + 10
}

func sheetBtns(g *sgame) []btn {
	var b []btn
	if g.status == 0 && g.utcMs > 0 {
		b = append(b, btn{"Kalender", cGreen})
	}
	if g.div != "" {
		b = append(b, btn{"Tabelle", cRed})
	}
	return append(b, btn{"Schließen", cGreen})
}

func sheetOpen(g *sgame) {
	sheetG = g
	_, _, _, by := sheetLayout(g)
	panelOpen("sheet", "Spiel", 460, by+48, false)
}

func paintSheet(c *canvas, p *panel, w, h int) {
	g := sheetG
	c.line(20, 54, w-40, 18, leagueShort(g.league, g.divName), ui(8.5, false), cGreyTxt, DT_CENTER)
	for k, t := range []struct {
		id, short string
		x         int
	}{{g.hID, g.hShort, 60}, {g.aID, g.aShort, w - 172}} {
		if im := requestLogo(t.id); im != nil {
			c.circle(t.x+30, 78, 52, 0xECEFF3)
			c.image(im, t.x+36, 84, 40)
			c.line(t.x, 134, 112, 20, t.short, ui(9, true), cDark, DT_CENTER)
		} else {
			c.line(t.x, 90, 112, 30, t.short, ui(13, true), cDark, DT_CENTER)
		}
		_ = k
	}
	score := "–"
	if g.status != 0 {
		score = fmt.Sprintf("%d : %d", g.hs, g.as)
	}
	c.line(170, 82, w-340, 50, score, ui(22, true), cDark, DT_CENTER)
	c.text(20, 160, w-40, 40, g.home+"\n– "+g.away, ui(9.5, false), cDark, DT_CENTER|DT_WORDBREAK)
	lines, hs, hallTxt, by := sheetLayout(g)
	y := 206
	for i, l := range lines {
		c.text(20, y, 90, hs[i], l.k, ui(8.5, true), cDark, DT_LEFT)
		c.text(112, y, 328, hs[i], l.v, ui(8.5, false), cDark, DT_WORDBREAK)
		y += hs[i] + 6
	}
	if hallTxt != "" {
		c.text(20, y, 90, 18, "Halle", ui(8.5, true), cDark, DT_LEFT)
		col := uint32(0x0064C8)
		if p.hover == "route" {
			col = 0x004A96
		}
		c.text(112, y, 328, hs[len(lines)], hallTxt, ui(8.5, false), col, DT_WORDBREAK)
	}
	bs := sheetBtns(g)
	x := (w - (len(bs)*110 + (len(bs)-1)*10)) / 2
	for i, b := range bs {
		c.pill(x+i*120, by, 110, 30, b.label, b.col, p.hover == "btn"+strconv.Itoa(i))
	}
}

func sheetHit(p *panel, x, y int) string {
	g := sheetG
	lines, hs, hallTxt, by := sheetLayout(g)
	hy := 206
	for i := range lines {
		hy += hs[i] + 6
	}
	if hallTxt != "" && y >= hy && y < hy+hs[len(lines)] && x >= 112 && x < 440 {
		return "route"
	}
	bs := sheetBtns(g)
	bx := (p.w - (len(bs)*110 + (len(bs)-1)*10)) / 2
	for i := range bs {
		if y >= by && y < by+30 && x >= bx+i*120 && x < bx+i*120+110 {
			return "btn" + strconv.Itoa(i)
		}
	}
	return ""
}

func sheetClick(hv string) {
	g := sheetG
	if hv == "route" { // offizielles URL-Schema von Google Maps
		q := g.hall
		if g.address != "" {
			q += ", " + g.address
		}
		shellOpen("https://www.google.com/maps/search/?api=1&query=" + url.QueryEscape(q))
		return
	}
	if !strings.HasPrefix(hv, "btn") {
		return
	}
	i, _ := strconv.Atoi(hv[3:])
	switch sheetBtns(g)[i].label {
	case "Kalender":
		f := filepath.Join(os.TempDir(), "EHV_Spiel_"+strings.ReplaceAll(g.id, "/", "")+".ics")
		if os.WriteFile(f, []byte(icsText(g)), 0644) == nil {
			shellOpen(f)
		}
	case "Tabelle":
		tableOpen(g.div, leagueShort(g.league, g.divName))
	case "Schließen":
		if p := panels["sheet"]; p != nil {
			panelClose(p)
		}
	}
}

// ================================================================= Tabelle
var (
	tableData []standing
	tableNote string
	tableSub  string
	pendTable []standing
	pendTOk   int // 0 ok, 1 keine Verbindung, 2 keine Tabelle
)

const tRowH = 28

func tableOpen(div, sub string) {
	p := panelOpen("table", "Tabelle", 600, 560, optOnTop)
	p.viewTop, p.viewH = 104, 560-104-80
	tableData, tableNote, tableSub = nil, "Lade Tabelle …", sub
	go func() {
		d, err := apiGet("Standings", "divisionId="+div, false)
		var st []standing
		code := 1
		if err == 0 {
			st = parseStandings(d)
			code = 0
			if len(st) == 0 {
				code = 2
			}
		}
		mu.Lock()
		pendTable, pendTOk = st, code
		mu.Unlock()
		pPostMessageW.Call(hMain, wmPanel, 1, 0)
	}()
}

func paintTable(c *canvas, p *panel, w, h int) {
	c.line(20, 52, w-40, 22, tableSub, ui(9, false), cGreyTxt, DT_LEFT)
	type tc struct {
		t     string
		x, w  int
		align uintptr
	}
	cs := []tc{{"#", 20, 30, DT_CENTER}, {"TEAM", 56, 280, DT_LEFT}, {"SP", 340, 40, DT_CENTER}, {"S", 380, 40, DT_CENTER},
		{"N", 420, 40, DT_CENTER}, {"TORE", 460, 64, DT_CENTER}, {"PKT", 526, 54, DT_CENTER}}
	for _, k := range cs {
		c.line(k.x, 80, k.w, 20, k.t, ui(7.5, true), 0x9AA1A9, k.align)
	}
	for i, s := range tableData {
		y := p.viewTop + i*tRowH - p.scroll
		if y < p.viewTop || y+tRowH > p.viewTop+p.viewH {
			continue
		}
		bg := uint32(cWhite)
		if i%2 == 1 {
			bg = 0xF7F8FA
		}
		if favOn && favRe != nil && favRe.MatchString(s.team) {
			bg = cFav
		}
		c.round(12, y+1, w-24, tRowH-2, 6, bg)
		v := []string{s.rank, s.team, strconv.Itoa(s.gp), strconv.Itoa(s.won), strconv.Itoa(s.lost),
			fmt.Sprintf("%d:%d", s.gf, s.ga), strconv.Itoa(s.pts)}
		for k, col := range cs {
			x, cw := col.x, col.w
			if k == 1 {
				if im := requestLogo(s.teamID); im != nil {
					c.image(im, x, y+4, 20)
				}
				x, cw = x+26, cw-26
			}
			c.line(x, y, cw, tRowH, v[k], ui(9, k == 6), cDark, col.align)
		}
	}
	c.line(20, h-46, 300, 30, tableNote, ui(8, false), cGreyTxt, DT_LEFT)
	drawBtns(c, p)
}

// ================================================================= Aufstellung
var (
	luGame, luLeague, luStatus string
	luData                     *lineup
	pendLu                     *lineup
	pendLuErr                  bool
)

const luRowH = 22

func lineupOpen(id, league string) {
	luGame, luLeague, luData = id, league, nil
	p := panelOpen("lineup", "Aufstellung", 780, 580, optOnTop)
	p.viewTop, p.viewH = 152, 580-152-60
	lineupLoad()
}

func lineupLoad() {
	luStatus = "Lade Aufstellung …"
	id := luGame
	go func() {
		d, err := apiGet("GetGameReport", "gameId="+id, false)
		var lu *lineup
		if err == 0 {
			lu = parseLineup(d)
		}
		mu.Lock()
		pendLu, pendLuErr = lu, lu == nil
		mu.Unlock()
		pPostMessageW.Call(hMain, wmPanel, 2, 0)
	}()
}

func paintLineup(c *canvas, p *panel, w, h int) {
	c.line(20, 50, w-40, 18, luLeague, ui(8.5, false), cGreyTxt, DT_CENTER)
	if luData == nil {
		c.line(20, 120, w-40, 30, luStatus, ui(10, false), cGreyTxt, DT_CENTER)
		drawBtns(c, p)
		return
	}
	c.line(20, 68, w-40, 32, luData.title, ui(15, true), cDark, DT_CENTER)
	cw := (w - 50) / 2
	type lc struct {
		t     string
		w     int
		align uintptr
	}
	cs := []lc{{"#", 30, DT_CENTER}, {"NAME", cw - 30 - 36 - 26*3 - 34, DT_LEFT}, {"POS", 36, DT_CENTER}, {"R", 26, DT_CENTER},
		{"T", 26, DT_CENTER}, {"A", 26, DT_CENTER}, {"SM", 34, DT_CENTER}}
	for s, side := range []struct {
		name string
		rows [][7]string
	}{{luData.homeName, luData.home}, {luData.awayName, luData.away}} {
		x0 := 20 + s*(cw+10)
		c.line(x0, 104, cw, 20, side.name, ui(9, true), cRed, DT_LEFT)
		x := x0
		for _, k := range cs {
			c.line(x, 128, k.w, 20, k.t, ui(7.5, true), 0x9AA1A9, k.align)
			x += k.w
		}
		for i, r := range side.rows {
			y := p.viewTop + i*luRowH - p.scroll
			if y < p.viewTop || y+luRowH > p.viewTop+p.viewH {
				continue
			}
			if i%2 == 1 {
				c.round(x0, y, cw, luRowH, 5, 0xF4F5F7)
			}
			x := x0
			for k, col := range cs {
				c.line(x, y, col.w, luRowH, r[k], ui(8.5, k == 0), cDark, col.align)
				x += col.w
			}
		}
	}
	c.line(20, h-46, w-280, 30, luStatus, ui(8, false), cGreyTxt, DT_LEFT)
	drawBtns(c, p)
}

// ---------------------------------------------------------------- Ergebnisse aus den Arbeits-Threads
func panelsApply(kind uintptr) {
	mu.Lock()
	st, code, lu, luErr := pendTable, pendTOk, pendLu, pendLuErr
	mu.Unlock()
	if kind == 1 {
		p := panels["table"]
		if p == nil {
			return
		}
		tableData = st
		tableNote = []string{"S / N inklusive Verlängerung und Penaltyschießen", "Keine Verbindung zu hockeydata.net",
			"Für diese Liga gibt es (noch) keine Tabelle."}[code]
		p.contentH = len(st) * tRowH
		invalidate(p.hwnd)
		return
	}
	p := panels["lineup"]
	if p == nil {
		return
	}
	if luErr {
		luStatus = "Keine Verbindung · " + nowHMS()
	} else {
		luData = lu
		n := len(lu.home)
		if len(lu.away) > n {
			n = len(lu.away)
		}
		p.contentH = n * luRowH
		if n == 0 {
			luStatus = "Aufstellung noch nicht verfügbar – erscheint, sobald der Anschreiber sie erfasst · " + nowHMS()
		} else {
			luStatus = "R = Reihe · T = Tore · A = Assists · SM = Strafminuten · Stand " + nowHMS()
		}
	}
	invalidate(p.hwnd)
}

func nowHMS() string { return time.Now().Format("15:04:05") }
