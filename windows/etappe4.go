//go:build windows

// Etappe 4: Optionen (Schalter statt Windows-Haekchen), Hilfe, Changelog, Hinweis beim ersten Start.
package main

import (
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

// ================================================================= Optionen
type optRow struct {
	kind  int // 0 Abschnitt, 1 Schalter, 2 Auswahl, 3 Textfeld, 4 Hinweis
	label string
	key   string
	vals  []int
	names []string
	snd   string // Probehoeren
}

var optCols = [2][]optRow{
	{
		{kind: 0, label: "AKTUALISIERUNG"},
		{kind: 2, label: "Spielliste", key: "list", vals: []int{60, 120, 300, 0}, names: []string{"1 Min", "2 Min", "5 Min", "aus"}},
		{kind: 2, label: "Ticker", key: "tick", vals: []int{10, 15, 30}, names: []string{"10 s", "15 s", "30 s"}},
		{kind: 1, label: "Updates automatisch prüfen (1× täglich)", key: "upd"},
		{kind: 5, label: "Jetzt auf Updates prüfen", key: "updnow"},
		{kind: 4, key: "last"},
		{kind: 0, label: "TICKER-FENSTER"},
		{kind: 1, label: "Immer im Vordergrund", key: "top"},
		{kind: 1, label: "Ereignisse aufgeklappt öffnen", key: "ev"},
		{kind: 2, label: "Zuklappen nach", key: "collapse", vals: []int{0, 10, 20, 30, 60}, names: []string{"aus", "10 s", "20 s", "30 s", "1 Min"}},
		{kind: 0, label: "HAUPTFENSTER"},
		{kind: 1, label: "Beim Öffnen eines Tickers minimieren", key: "min"},
		{kind: 1, label: "Nach dem letzten Ticker wieder öffnen", key: "restore"},
	},
	{
		{kind: 0, label: "TÖNE"},
		{kind: 1, label: "Torhorn bei Tor", key: "tor", snd: "tor"},
		{kind: 1, label: "Pfiff bei Strafe", key: "strafe", snd: "strafe"},
		{kind: 1, label: "Sirene bei Drittelende", key: "drittel", snd: "drittel"},
		{kind: 1, label: "Abpfiff bei Spielende", key: "spielende", snd: "spielende"},
		{kind: 1, label: "Gong bei Spielbeginn", key: "bully", snd: "bully"},
		{kind: 4, label: "Eigene Klänge: WAV-Dateien im Ordner „sounds“ ersetzen"},
		{kind: 0, label: "HERVORHEBUNG"},
		{kind: 1, label: "Spiele hervorheben, wenn ein Team enthält:", key: "fav"},
		{kind: 3, key: "terms"},
		{kind: 4, label: "mehrere Begriffe mit Komma trennen, z. B. Neuss, Moers"},
	},
}

var (
	oTog   map[string]bool
	oSeg   map[string]int
	oTerms string
	oFocus bool
)

const optW, optColW = 780, 350

func optRowH(k int) int { return []int{34, 32, 34, 36, 24, 36}[k] }

func optionsOpen() {
	oTog = map[string]bool{"top": optOnTop, "ev": optEvDefault, "min": optMinMain, "restore": optRestore, "fav": favOn,
		"tor": optSndTor, "strafe": optSndStrafe, "drittel": optSndDrittel, "spielende": optSndEnde, "bully": optSndBully, "upd": optUpdAuto}
	oSeg = map[string]int{"list": refreshList, "tick": optTickSec, "collapse": optCollapse}
	oTerms, oFocus = favTerms, false
	h := 0
	for _, col := range optCols {
		y := 0
		for _, r := range col {
			y += optRowH(r.kind)
		}
		if y > h {
			h = y
		}
	}
	panelOpen("opt", "Optionen", optW, 56+h+70, false)
}

// Position jeder Zeile (logisch)
func optLayout(fn func(c, x, y int, r optRow)) {
	for ci, col := range optCols {
		x, y := 24+ci*(optColW+30), 58
		for _, r := range col {
			fn(ci, x, y, r)
			y += optRowH(r.kind)
		}
	}
}

func paintOptions(c *canvas, p *panel, w, h int) {
	optLayout(func(_, x, y int, r optRow) {
		switch r.kind {
		case 0:
			c.line(x, y+10, optColW, 20, r.label, ui(8, true), cRed, DT_LEFT)
		case 1:
			c.line(x, y, optColW-60, 32, r.label, ui(9, false), cDark, DT_LEFT)
			if r.snd != "" {
				hc := uint32(0xE6E9ED)
				if p.hover == "play:"+r.snd {
					hc = 0xD3D8DE
				}
				c.circle(x+optColW-94, y+4, 24, hc)
				c.line(x+optColW-94, y+4, 25, 24, "▶", ui(7, false), cDark2, DT_CENTER)
			}
			on := oTog[r.key]
			tc := uint32(0xC9CED4)
			if on {
				tc = cGreen
			}
			sx := x + optColW - 46
			c.round(sx, y+6, 44, 22, 11, tc)
			kx := sx + 3
			if on {
				kx = sx + 25
			}
			c.circle(kx, y+9, 16, cWhite)
		case 2:
			c.line(x, y, 120, 34, r.label, ui(9, false), cDark, DT_LEFT)
			bw := (optColW - 124) / len(r.vals)
			for i, v := range r.vals {
				bx := x + 124 + i*bw
				col, tc := uint32(0xEEF0F3), uint32(cDark)
				if oSeg[r.key] == v {
					col, tc = cGreen, cWhite
				} else if p.hover == "seg:"+r.key+":"+strconv.Itoa(i) {
					col = 0xE1E4E8
				}
				c.round(bx+2, y+5, bw-4, 24, 12, col)
				c.line(bx+2, y+5, bw-4, 24, r.names[i], ui(8, oSeg[r.key] == v), tc, DT_CENTER)
			}
		case 3:
			bc := uint32(cLine)
			if oFocus {
				bc = cGreen
			}
			c.round(x, y+3, optColW, 30, 8, bc)
			c.round(x+2, y+5, optColW-4, 26, 7, cWhite)
			t := oTerms
			if oFocus {
				t += "|"
			}
			c.line(x+12, y+3, optColW-24, 30, t, ui(9, false), cDark, DT_LEFT)
		case 4:
			lbl := r.label
			if r.key == "last" {
				lbl = updLastText()
			}
			c.line(x, y, optColW, 22, lbl, ui(7.5, false), cGreyTxt, DT_LEFT)
		case 5:
			c.pill(x, y+3, 230, 30, r.label, cDark2, p.hover == "updnow")
		}
	})
	c.rect(0, h-66, w, 1, cLine)
	c.pill(w-260, h-50, 110, 32, "Speichern", cGreen, p.hover == "save")
	c.pill(w-140, h-50, 110, 32, "Abbrechen", cDark2, p.hover == "cancel")
	c.line(24, h-50, 380, 32, "Gespeichert in %APPDATA%\\EHV-Live-Ticker", ui(7.5, false), 0x9AA1A9, DT_LEFT)
}

func optHit(p *panel, mx, my int) string {
	if my >= p.h-50 && my < p.h-18 {
		if mx >= p.w-260 && mx < p.w-150 {
			return "save"
		}
		if mx >= p.w-140 && mx < p.w-30 {
			return "cancel"
		}
	}
	hit := ""
	optLayout(func(_, x, y int, r optRow) {
		if my < y || my >= y+optRowH(r.kind) || mx < x || mx >= x+optColW {
			return
		}
		switch r.kind {
		case 1:
			if r.snd != "" && mx >= x+optColW-96 && mx < x+optColW-68 {
				hit = "play:" + r.snd
			} else {
				hit = "tog:" + r.key
			}
		case 2:
			bw := (optColW - 124) / len(r.vals)
			if mx >= x+124 {
				if i := (mx - x - 124) / bw; i < len(r.vals) {
					hit = "seg:" + r.key + ":" + strconv.Itoa(i)
				}
			}
		case 3:
			hit = "text"
		case 5:
			hit = r.key
		}
	})
	return hit
}

func optClick(p *panel, hv string) {
	oFocus = hv == "text"
	switch {
	case strings.HasPrefix(hv, "tog:"):
		oTog[hv[4:]] = !oTog[hv[4:]]
	case strings.HasPrefix(hv, "play:"):
		soundPlayFile(hv[5:])
	case strings.HasPrefix(hv, "seg:"):
		parts := strings.Split(hv, ":")
		i, _ := strconv.Atoi(parts[2])
		for _, col := range optCols {
			for _, r := range col {
				if r.key == parts[1] {
					oSeg[r.key] = r.vals[i]
				}
			}
		}
	case hv == "updnow":
		updCheck(true)
	case hv == "save":
		optionsSave()
		panelClose(p)
	case hv == "cancel":
		panelClose(p)
	}
}

// Tastatur fuer das Textfeld (Strg+V fuegt ein)
func optChar(p *panel, ch uint16) {
	if !oFocus {
		return
	}
	switch {
	case ch == 8: // Ruecktaste
		if r := []rune(oTerms); len(r) > 0 {
			oTerms = string(r[:len(r)-1])
		}
	case ch == 22: // Strg+V
		oTerms += clipboardText()
	case ch == 13 || ch == 27:
		oFocus = false
	case ch >= 32:
		oTerms += string(rune(ch))
	}
	invalidate(p.hwnd)
}

func b01(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

func optionsSave() {
	w := func(k, v string) { iniWrite(iniFile, "Optionen", k, v) }
	w("ListeSek", strconv.Itoa(oSeg["list"]))
	w("TickerSek", strconv.Itoa(oSeg["tick"]))
	w("EreignisseZuklappenSek", strconv.Itoa(oSeg["collapse"]))
	w("Vordergrund", b01(oTog["top"]))
	w("EreignisseAufgeklappt", b01(oTog["ev"]))
	w("HauptMinimieren", b01(oTog["min"]))
	w("HauptWiederOeffnen", b01(oTog["restore"]))
	w("Hervorheben", b01(oTog["fav"]))
	w("HervorhebenBegriffe", strings.TrimSpace(oTerms))
	w("TonTor", b01(oTog["tor"]))
	w("TonStrafe", b01(oTog["strafe"]))
	w("TonDrittel", b01(oTog["drittel"]))
	w("TonSpielende", b01(oTog["spielende"]))
	w("TonBully", b01(oTog["bully"]))
	w("UpdatePruefen", b01(oTog["upd"]))
	optionsLoad()
	// sofort anwenden: Vordergrund, Hervorhebung, Spielplan
	z := ^uintptr(1) // HWND_NOTOPMOST (-2)
	if optOnTop {
		z = ^uintptr(0) // HWND_TOPMOST (-1)
	}
	for _, t := range tickers {
		if t != nil {
			pSetWindowPos.Call(t.hwnd, z, 0, 0, 0, 0, SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE)
		}
	}
	for i := range rows {
		if rows[i].kind != 2 {
			rows[i].fav = isFav(rows[i])
		}
	}
	invalidate(hMain)
	schedBuild()
}

var (
	pOpenClipboard  = user32.NewProc("OpenClipboard")
	pCloseClipboard = user32.NewProc("CloseClipboard")
	pGetClipboard   = user32.NewProc("GetClipboardData")
	pGlobalLock     = kernel32.NewProc("GlobalLock")
	pGlobalUnlock   = kernel32.NewProc("GlobalUnlock")
)

func clipboardText() string {
	if r, _, _ := pOpenClipboard.Call(0); r == 0 {
		return ""
	}
	defer pCloseClipboard.Call()
	h, _, _ := pGetClipboard.Call(13) // CF_UNICODETEXT
	if h == 0 {
		return ""
	}
	p, _, _ := pGlobalLock.Call(h)
	if p == 0 {
		return ""
	}
	defer pGlobalUnlock.Call(h)
	var s []uint16
	for i := 0; i < 500; i++ {
		ch := *(*uint16)(unsafe.Pointer(p + uintptr(i*2)))
		if ch == 0 {
			break
		}
		s = append(s, ch)
	}
	return strings.TrimSpace(strings.ReplaceAll(syscall.UTF16ToString(s), "\r\n", " "))
}

// ================================================================= Textseiten: Hilfe, Changelog, Hinweis
type docLine struct {
	kind int // 0 Abschnitt, 1 Stichwort + Text, 2 Absatz, 3 klein/grau, 4 Abstand
	k, v string
}

var (
	docs  = map[string][]docLine{}
	docHs = map[string][]int{}
)

const docW = 540

func helpDoc() []docLine {
	sec := func(s string) docLine { return docLine{kind: 0, v: s} }
	it := func(k, v string) docLine { return docLine{kind: 1, k: k, v: v} }
	return []docLine{
		{kind: 5},
		sec("SPIELLISTE"),
		it("Doppelklick", "auf ein Spiel öffnet den Ticker (oder „Ticker öffnen“)"),
		it("Gelb", "hervorgehobene Spiele (Standard: Neuss, in den Optionen änderbar)"),
		it("Heute noch", "blass unter der Trennzeile – der Ticker lässt sich schon vor dem Bully öffnen"),
		it("Grüner Punkt", "für dieses Spiel ist ein Ticker offen; roter Punkt = Spiel läuft"),
		it("Aktualisieren", "lädt neu – automatisch im eingestellten Abstand, solange das Fenster nicht minimiert ist"),
		it("Sparsam", "abgefragt werden nur Ligen, die heute spielen; der Spielplan wird alle 30 Min. neu geladen"),
		it("Server weg", "der letzte Stand bleibt stehen, die Statuszeile meldet es – neuer Versuch automatisch"),
		it("⚙ / ?", "oben rechts: Optionen und diese Hilfe (auch mit F1)"),
		it("Einstellungen", "liegen unter %APPDATA%\\EHV-Live-Ticker (Optionen, Töne)"),
		sec("SPIELPLAN"),
		it("Reiter", "„Nächste“ (7 Tage), „Ergebnisse“ (letzte 7 Tage), „Meine Spiele“ (hervorgehobene Teams)"),
		it("Doppelklick", "Steckbrief: Halle, Drittel, Torschützen – Halle anklicken = Route in Google Maps"),
		it("Kalender", "speichert das Spiel als Termin-Datei und öffnet sie mit deinem Kalender"),
		it("Tabelle", "aus dem Steckbrief oder im Ticker über „Tabelle ▸“ – eigenes Team gelb"),
		sec("TICKER-FENSTER"),
		it("Verschieben", "an jeder freien Stelle des Tickers ziehen"),
		it("Klick auf Stand", "Ereignisse auf-/zuklappen; Mausrad blättert in den Ereignissen"),
		it("Vor dem Bully", "Ticker halb durchsichtig – bei Spielbeginn voll sichtbar mit Gong"),
		it("Tor", "Stand 15 s gelb, „TOR!“ im Fenstertitel"),
		it("Töne", "Torhorn, Pfiff (Strafe), Sirene (Drittelende), Abpfiff (Spielende), Gong (Spielbeginn)"),
		it("Aufstellung ▸", "Line-up beider Teams mit Toren, Assists und Strafminuten (sobald eingetragen)"),
		it("Position", "wird gespeichert; bis zu 8 Ticker gleichzeitig"),
		it("Schiedsrichter", "unter dem Stand: HSR und (L)SR, sobald auf esrw.de eingeteilt – Zuordnung nach Datum, Zeit und Teams"),
		sec("KÜRZEL IN DEN EREIGNISSEN"),
		it("(…)", "Vorlagen hinter dem Torschützen, sofern eingetragen"),
		it("[PP…]", "Tor in Überzahl (Powerplay)"),
		it("[SH…]", "Tor in Unterzahl"),
		it("[EN]", "Tor ins leere Tor"),
		it("[PS]", "Penaltyschuss"),
		sec("UPDATES"),
		it("Knopf „Update“", "oben in der Titelleiste, sobald eine neue Version bereitsteht"),
		it("Prüfung", "einmal täglich automatisch, oder „Jetzt auf Updates prüfen“ in den Optionen"),
		it("Installation", "neue EXE herunterladen und starten – nichts wird automatisch installiert"),
		sec("HINWEIS"),
		{kind: 2, v: strings.ReplaceAll(disclaimer, "\r\n\r\n", " ")},
		{kind: 3, v: "Datenquellen: ehv-nrw.de und hockeydata.net (Spiele), esrw.de (Schiedsrichter)"},
		{kind: 4},
		{kind: 3, v: "Gebaut von Justus Jonas & Michael Nowak – mit Claude an der Bande"},
	}
}

var changelog = [][2]string{
	{"3.1", "Schiedsrichter (HSR und (L)SR) aus esrw.de unter dem Spielstand im Ticker – nur bei eindeutigem Treffer (Datum, Zeit, Teams)|Update-Prüfung: einmal täglich und per Knopf in den Optionen|Knopf „Update“ oben in der Titelleiste öffnet die Download-Seite – nichts wird automatisch installiert|Optionen: Update-Prüfung ein-/ausschalten"},
	{"3.0.1", "Neues Icon: schwarzer Puck auf Eis mit EHV-Farbstreifen|Icon auch in den Titelleisten und im Kopf der Hilfe"},
	{"3.0", "Neu gebaut als eigenständige EXE (ohne AutoIt) – weniger Fehlalarme von Virenscannern|Eigenes, flaches Design: rahmenlose Fenster, runde Knöpfe, Schalter statt Häkchen|Scharfe Schrift bei Windows-Skalierung 125 %/150 %|Abrufe im Hintergrund – Fenster frieren nicht mehr ein|Ticker an jeder freien Stelle verschiebbar|Eigenes Programm-Icon und Versionsinfo"},
	{"2.0", "Erste Version als EXE (Bau-Paket mit Bauen.bat)|Deutlich weniger Serverlast: nur Ligen mit heutigen Spielen, Liste standardmäßig alle 2 Min., Spielplan alle 30 Min.|Einstellungen, Töne und Icon unter %APPDATA%\\EHV-Live-Ticker"},
	{"1.9.1", "Server-Ausfall: kurzer Verbindungstest, letzter Stand bleibt sichtbar|Hilfe scrollbar|Hinweis: inoffizielles Tool, nur öffentliche Daten, nur private Nutzung|Vorlagen hinter dem Torschützen"},
	{"1.9", "Spielplan-Fenster: Nächste Spiele, Ergebnisse, Meine Spiele – mit Wappen|Steckbrief: Halle, Drittel, Torschützen|Route zur Halle, Termin für den Kalender|Ligatabelle"},
	{"1.8", "Vor dem Bully: Ticker halb durchsichtig, Gong bei Spielbeginn|Markierung offener Ticker in der Liste|Ereignisse klappen auf Wunsch wieder zu|Design in EHV-Farben, Changelog"},
	{"1.7", "Vereinslogos im Ticker|Ereignisse standardmäßig eingeklappt|Lange Vereinsnamen passen"},
	{"1.6", "Aufstellung beider Teams"},
	{"1.5", "„Heute noch“: anstehende Spiele des Tages"},
	{"1.4", "Töne: Torhorn, Pfiff, Sirene, Abpfiff|Volle Liga/Altersklasse im Ticker"},
	{"1.3", "Optionen: Intervalle, Vordergrund, Hauptfenster-Verhalten, Hervorhebung"},
	{"1.2", "Ticker wird beim Zuklappen nicht mehr gestaucht"},
	{"1.1", "Hervorhebung Neusser Spiele|Ereignisse ein-/ausblenden|Hilfe (F1) und Versionsnummer"},
	{"1.0", "Laufende EHV-NRW-Spiele erkennen|Ticker-Fenster mit Stand, Drittel, Toren und Strafen"},
}

func logDoc() []docLine {
	var d []docLine
	for _, v := range changelog {
		d = append(d, docLine{kind: 0, v: "VERSION " + v[0]})
		for _, l := range strings.Split(v[1], "|") {
			d = append(d, docLine{kind: 1, k: "•", v: l})
		}
	}
	return d
}

func docFont(k int) uintptr {
	switch k {
	case 0:
		return ui(8, true)
	case 3:
		return ui(8.5, false)
	}
	return ui(9, false)
}

func docMeasure(d []docLine) ([]int, int) {
	dc, _, _ := pCreateCompatibleDC.Call(0)
	defer pDeleteDC.Call(dc)
	c := &canvas{dc}
	var hs []int
	total := 0
	for _, l := range d {
		var h int
		switch l.kind {
		case 0:
			h = 36
		case 1:
			h = c.textHeight(docW-176, l.v, docFont(1)) + 6
		case 4:
			h = 14
		case 5:
			h = 84
		default:
			h = c.textHeight(docW-48, l.v, docFont(l.kind)) + 8
		}
		hs = append(hs, h)
		total += h
	}
	return hs, total
}

func docOpen(kind, title string, d []docLine) {
	hs, total := docMeasure(d)
	docs[kind], docHs[kind] = d, hs
	h := 56 + total + 20 + 64
	if max := L(screenH()) * 85 / 100; h > max {
		h = max
	}
	p := panelOpen(kind, title, docW, h, kind == "hint")
	p.viewTop, p.viewH, p.contentH = 56, h-56-70, total+12
}

func paintDoc(c *canvas, p *panel, w, h int) {
	d, hs := docs[p.kind], docHs[p.kind]
	y := p.viewTop + 6 - p.scroll
	for i, l := range d {
		if y >= p.viewTop && y+hs[i] <= p.viewTop+p.viewH {
			switch l.kind {
			case 0:
				c.line(24, y+12, w-48, 20, l.v, docFont(0), cRed, DT_LEFT)
			case 1:
				kw := 140
				if l.k == "•" {
					kw = 20
				}
				c.text(24, y, kw, hs[i], l.k, ui(9, true), cDark, DT_LEFT)
				c.text(24+kw+8, y, w-48-kw-8, hs[i], l.v, docFont(1), cDark, DT_WORDBREAK)
			case 2:
				c.text(24, y, w-48, hs[i], l.v, docFont(2), cDark, DT_WORDBREAK)
			case 3:
				c.text(24, y, w-48, hs[i], l.v, docFont(3), cGreyTxt, DT_WORDBREAK)
			case 5: // Kopf der Hilfe: grosses Icon, Name, Version
				c.appIcon(24, y+6, 64)
				c.line(102, y+10, w-200, 28, "EHV-NRW Live-Ticker", ui(14, true), cDark, DT_LEFT)
				c.line(102, y+38, w-200, 22, "Live-Spiele des Eishockeyverbands NRW", ui(9, false), cGreyTxt, DT_LEFT)
				c.round(w-96, y+14, 72, 22, 11, cDark)
				c.line(w-96, y+14, 72, 22, "v"+appVersion, ui(8, true), cWhite, DT_CENTER)
			}
		}
		y += hs[i]
	}
	c.rect(0, h-66, w, 1, cLine)
	drawBtns(c, p)
}

func helpOpen()      { docOpen("help", "Hilfe – EHV-NRW Live-Ticker", helpDoc()) }
func changelogOpen() { docOpen("log", "Changelog – was ist neu?", logDoc()) }
func hintOpen() {
	docOpen("hint", "Hinweis", []docLine{{kind: 2, v: strings.ReplaceAll(disclaimer, "\r\n\r\n", "\n\n")}})
}
