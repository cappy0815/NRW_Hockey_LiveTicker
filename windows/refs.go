// Schiedsrichter-Einteilung von esrw.de. Zuordnung zu einem Spiel nur bei vollem Treffer:
// Datum, Anstoßzeit, Heimteam und Gastteam (Schreibweise normalisiert). Kein Treffer = nichts Falsches anzeigen.
package main

import (
	"html"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	refsURL   = "https://www.esrw.de/einteilungen.html"
	refsTTL   = 30 * time.Minute // Einteilungen ändern sich kurzfristig; öfter abrufen ist nicht nötig
	refsRetry = 5 * time.Minute  // nach einem Fehlschlag frühestens nach 5 Min. erneut
)

type refInfo struct{ hsr, lsr string } // leer = noch nicht eingetragen

// Stand der Tabelle: alle Einteilungen, plus Zeitraum, den die Seite abdeckt
type refData struct {
	rows     map[string]refInfo
	min, max string // ISO-Datum jjjj-mm-tt
}

var (
	refsMu      sync.Mutex
	refsCur     refData
	refsAt      time.Time
	refsTry     time.Time
	reRefRow    = regexp.MustCompile(`(?s)<tr class="einteilung_spiel.*?</tr>`)
	reRefDate   = regexp.MustCompile(`<time datetime="(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})`)
	reRefBeg    = regexp.MustCompile(`(?s)<td data-title="Begegnung">(.*?)</td>`)
	reRefHSR    = regexp.MustCompile(`(?s)<td data-title="HSR">(.*?)</td>`)
	reRefLSR    = regexp.MustCompile(`(?s)<td data-title="\(L\)SR">(.*?)</td>`)
	reTag       = regexp.MustCompile(`<[^>]*>`)
	reC1        = regexp.MustCompile("[\u0080-\u009f]")
	reZusatz    = regexp.MustCompile(`\s+in\s+[A-ZÄÖÜ].*$`) // "KEC Die Haie 1b in Benrath !!!" -> Ortshinweis weg
	reWS        = regexp.MustCompile(`\s+`)
	teamCleaner = strings.NewReplacer(`"`, "", "„", "", "“", "", "”", "", "-", " ")
	// Schreibweisen, die sich zwischen esrw.de und hockeydata unterscheiden (nur geprüfte Fälle)
	teamAlias = map[string]string{
		"eissportclub rheine young cats": "esc rheine young cats", // hockeydata-Name -> esrw.de-Name
	}
)

// Bereinigter Text einer Tabellenzelle: Tags weg, Entities auflösen, Leerzeichen zusammenfassen
func plainText(s string) string {
	s = reTag.ReplaceAllString(s, " ")
	s = html.UnescapeString(s)
	s = strings.ReplaceAll(s, "\u00a0", " ") // geschütztes Leerzeichen
	s = reC1.ReplaceAllString(s, "")         // Steuerzeichen (Windows-1252 falsch kodiert auf esrw.de)
	return strings.TrimSpace(reWS.ReplaceAllString(s, " "))
}

// "Lippe-Hockey-Hamm" und "Lippe Hockey Hamm" ergeben dasselbe
func normTeam(s string) string {
	s = strings.ToLower(teamCleaner.Replace(s))
	s = strings.Join(strings.Fields(s), " ")
	if a, ok := teamAlias[s]; ok {
		return a
	}
	return s
}

func refKey(date, tm, home, away string) string {
	return date + " " + tm + "|" + normTeam(home) + "|" + normTeam(away)
}

// Tabelle lesen. Jede Zeile: Datum und Zeit (time-Attribut), "Liga: Heim - Gast", HSR, (L)SR
func parseRefs(page string) refData {
	d := refData{rows: map[string]refInfo{}}
	for _, row := range reRefRow.FindAllString(page, -1) {
		dt := reRefDate.FindStringSubmatch(row)
		bg := reRefBeg.FindStringSubmatch(row)
		if dt == nil || bg == nil {
			continue
		}
		paar := plainText(bg[1])
		if i := strings.Index(paar, ": "); i >= 0 {
			paar = paar[i+2:] // Liga-Präfix weg
		}
		home, away, ok := strings.Cut(paar, " - ")
		if !ok {
			continue
		}
		home, away = reZusatz.ReplaceAllString(home, ""), reZusatz.ReplaceAllString(away, "")
		var inf refInfo
		if m := reRefHSR.FindStringSubmatch(row); m != nil {
			inf.hsr = plainText(m[1])
		}
		if m := reRefLSR.FindStringSubmatch(row); m != nil {
			inf.lsr = plainText(m[1])
		}
		d.rows[refKey(dt[1], dt[2], home, away)] = inf
		if d.min == "" || dt[1] < d.min {
			d.min = dt[1]
		}
		if dt[1] > d.max {
			d.max = dt[1]
		}
	}
	return d
}

// Abruf, höchstens alle 30 Min. (Fehlschlag: nach 5 Min. erneut). Alter Stand bleibt bei Fehlern.
func refsEnsure() {
	refsMu.Lock()
	skip := time.Since(refsAt) < refsTTL || time.Since(refsTry) < refsRetry
	refsTry = time.Now()
	refsMu.Unlock()
	if skip {
		return
	}
	req, err := http.NewRequest("GET", refsURL, nil)
	if err != nil {
		return
	}
	req.Header.Set("User-Agent", "EHV-LiveTicker")
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return
	}
	d := parseRefs(string(b))
	if len(d.rows) == 0 { // Seite anders aufgebaut -> alten Stand behalten
		return
	}
	refsMu.Lock()
	refsCur, refsAt = d, time.Now()
	refsMu.Unlock()
}

// Zeile für den Ticker. show=false: nichts anzeigen (Seite noch nicht gelesen oder Spiel außerhalb des Zeitraums).
// dim=true: Spiel liegt im Zeitraum, steht aber nicht auf esrw.de.
func refLine(date, tm, home, away string) (text string, dim, show bool) {
	if date == "" || tm == "" {
		return "", false, false
	}
	refsMu.Lock()
	d := refsCur
	refsMu.Unlock()
	if d.max == "" || date < d.min || date > d.max {
		return "", false, false
	}
	inf, ok := d.rows[refKey(date, tm, home, away)]
	if !ok {
		return "Schiedsrichter: keine Eintragung auf esrw.de", true, true
	}
	return "HSR " + orOffen(inf.hsr) + "    ·    (L)SR " + orOffen(inf.lsr), false, true
}

func orOffen(s string) string {
	if s == "" {
		return "offen"
	}
	return s
}
