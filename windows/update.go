//go:build windows

// Update-Prüfung wie bei Claude Limits, Soundboard und Player:
//  1. Versionsdatei auf cappy0815.github.io (downloads/ehv-live-win.json)
//  2. Rückfall: neuestes Windows-Release (Tag WIN_v… ) im Repo cappy0815/NRW_Hockey_LiveTicker
//
// Nichts wird automatisch installiert: der Knopf öffnet nur die Download-Seite im Browser.
// Gesendet wird nur eine normale Abfrage mit der Versionsnummer.
package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"
)

const (
	wmUpd          = WM_APP + 6 // Update-Prüfung abgeschlossen
	updVersionURL  = "https://cappy0815.github.io/downloads/ehv-live-win.json"
	updReleasesAPI = "https://api.github.com/repos/cappy0815/NRW_Hockey_LiveTicker/releases?per_page=30"
	updProjectPage = "https://cappy0815.github.io/#liveticker"
	updInterval    = 24 * time.Hour
)

var (
	optUpdAuto = true // in den Optionen: einmal täglich automatisch prüfen

	updMu       sync.Mutex
	updBusy     bool
	updOK       bool
	updLast     time.Time
	updLatest   string // neueste bekannte Version (aus INI oder letzter Prüfung)
	updDownload string // direkter Download der EXE
	updPageLink string // Seite der konkreten Version
)

// Stand aus der INI, damit der Knopf auch ohne Netz gleich stimmt
func updLoad() {
	if n, err := strconv.ParseInt(iniRead(iniFile, "Update", "Last", "0"), 10, 64); err == nil && n > 0 {
		updLast = time.Unix(n, 0)
	}
	updLatest = iniRead(iniFile, "Update", "Latest", "")
	updDownload = iniRead(iniFile, "Update", "Download", "")
	updPageLink = iniRead(iniFile, "Update", "Page", "")
}

func updGet(url string, api bool) ([]byte, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "EHV-LiveTicker/"+appVersion)
	if api {
		req.Header.Set("Accept", "application/vnd.github+json")
	}
	resp, err := (&http.Client{Timeout: 12 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 1<<20))
}

// erst die Versionsdatei, sonst das neueste Windows-Release
func updFetch() (release, error) {
	if b, err := updGet(updVersionURL, false); err == nil {
		if r, err := parseVersionFile(b); err == nil {
			return r, nil
		}
	}
	b, err := updGet(updReleasesAPI, true)
	if err != nil {
		return release{}, err
	}
	return parseReleases(b)
}

// Prüfung im Hintergrund; das Ergebnis kommt als wmUpd zurück
func updCheck(manual bool) {
	updMu.Lock()
	if updBusy {
		updMu.Unlock()
		return
	}
	updBusy = true
	updMu.Unlock()
	if manual {
		setStatus("Prüfe auf Updates …")
		updMu.Lock()
		updMsg = "Prüfe auf Updates …"
		updMu.Unlock()
		if p := panels["opt"]; p != nil {
			invalidate(p.hwnd)
		}
	}
	go func() {
		rel, err := updFetch()
		updMu.Lock()
		updBusy = false
		updOK = err == nil
		updLast = time.Now()
		if updOK {
			updLatest, updDownload, updPageLink = rel.version, rel.download, rel.page
		}
		updMu.Unlock()
		pPostMessageW.Call(hMain, wmUpd, boolPtr(manual), 0)
	}()
}

// Ergebnis anwenden (UI-Thread)
func updDone(manual bool) {
	updMu.Lock()
	ok, latest, last := updOK, updLatest, updLast
	dl, pg := updDownload, updPageLink
	updMu.Unlock()
	iniWrite(iniFile, "Update", "Last", strconv.FormatInt(last.Unix(), 10))
	if ok {
		iniWrite(iniFile, "Update", "Latest", latest)
		iniWrite(iniFile, "Update", "Download", dl)
		iniWrite(iniFile, "Update", "Page", pg)
	}
	if manual {
		var msg string
		switch {
		case !ok:
			msg = "Keine Verbindung zu GitHub – Prüfung nicht möglich"
		case updAvailable():
			msg = "Neue Version " + latest + " verfügbar – Knopf „Update“ oben rechts"
		default:
			msg = "Aktuell: Version " + appVersion
		}
		status = msg
		updMu.Lock()
		updMsg = msg
		updMu.Unlock()
	}
	if p := panels["opt"]; p != nil {
		invalidate(p.hwnd)
	}
	invalidate(hMain)
}

func updAvailable() bool {
	updMu.Lock()
	defer updMu.Unlock()
	return updLatest != "" && versionNewer(updLatest, appVersion)
}

func updLatestText() string {
	updMu.Lock()
	defer updMu.Unlock()
	return updLatest
}

// Ergebnis der letzten manuellen Prüfung, sichtbar im Optionsfenster
var updMsg string

func updLastText() string {
	updMu.Lock()
	last, msg := updLast, updMsg
	updMu.Unlock()
	zl := "Zuletzt geprüft: noch nie"
	if !last.IsZero() {
		zl = "Zuletzt geprüft: " + last.Format("02.01.2006 15:04")
	}
	if msg != "" {
		return msg + " · " + zl
	}
	return zl
}

// Pfad der laufenden EXE (damit man weiß, wo die neue hin soll)
func exePath() string {
	p, err := os.Executable()
	if err != nil {
		return "(unbekannt)"
	}
	return p
}

// Download-Adresse: die EXE, sonst die Seite der Version, sonst die Projektseite
func updDownloadURL() string {
	updMu.Lock()
	defer updMu.Unlock()
	switch {
	case updDownload != "":
		return updDownload
	case updPageLink != "":
		return updPageLink
	}
	return updProjectPage
}

// alle 0,5 s aus dem Hauptfenster-Timer: einmal täglich prüfen, wenn eingeschaltet
func updTimer() {
	if !optUpdAuto {
		return
	}
	updMu.Lock()
	due := time.Since(updLast) > updInterval && !updBusy
	updMu.Unlock()
	if due {
		updCheck(false)
	}
}

// Fenster „Update verfügbar“
func updOpenPanel() {
	docOpen("upd", "Update verfügbar", []docLine{
		{kind: 0, v: "NEUE VERSION " + updLatestText()},
		{kind: 2, v: "Installiert ist Version " + appVersion + ". „Herunterladen“ öffnet die Download-Seite im Browser."},
		{kind: 2, v: "„Herunterladen“ öffnet die Seite im Browser. Danach schließt sich dieses Programm, damit die EXE ersetzt werden kann. Die neue Datei dann starten."},
		{kind: 2, v: "Dieses Programm liegt hier: " + exePath()},
		{kind: 2, v: "Einstellungen, Töne und Position bleiben unter %APPDATA%\\EHV-Live-Ticker erhalten."},
		{kind: 2, v: "Es wird nichts automatisch installiert. Windows kann bei einer neu heruntergeladenen Datei wieder eine Warnung zeigen (unbekannter Herausgeber)."},
	})
}
