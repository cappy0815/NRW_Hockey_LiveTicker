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
		switch {
		case !ok:
			status = "Update-Prüfung nicht möglich – keine Verbindung zu GitHub"
		case updAvailable():
			status = "Neue Version " + latest + " verfügbar – Knopf „Update“ oben rechts"
		default:
			status = "Aktuell: Version " + appVersion
		}
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

func updLastText() string {
	updMu.Lock()
	last := updLast
	updMu.Unlock()
	if last.IsZero() {
		return "Zuletzt geprüft: noch nie"
	}
	return "Zuletzt geprüft: " + last.Format("02.01.2006 15:04")
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
		{kind: 2, v: "Die neue EXE herunterladen und die alte ersetzen oder daneben legen und starten. Einstellungen, Töne und Position bleiben unter %APPDATA%\\EHV-Live-Ticker erhalten."},
		{kind: 2, v: "Es wird nichts automatisch installiert. Windows kann bei einer neu heruntergeladenen Datei wieder eine Warnung zeigen (unbekannter Herausgeber)."},
	})
}
