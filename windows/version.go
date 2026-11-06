// Versionsvergleich und Auswertung der Update-Quellen (ohne Windows-Abhängigkeit, damit testbar)
package main

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
)

type release struct{ version, page, download string }

var errFormat = errors.New("unbekanntes Format")

// "1.10" > "1.9", "3.1" > "3.0.1"
func verParts(v string) []int {
	v = strings.TrimPrefix(strings.TrimSpace(v), "v")
	var out []int
	for _, p := range strings.Split(v, ".") {
		n, _ := strconv.Atoi(strings.TrimSpace(p))
		out = append(out, n)
	}
	return out
}

func versionNewer(a, b string) bool {
	pa, pb := verParts(a), verParts(b)
	for i := 0; i < len(pa) || i < len(pb); i++ {
		x, y := 0, 0
		if i < len(pa) {
			x = pa[i]
		}
		if i < len(pb) {
			y = pb[i]
		}
		if x != y {
			return x > y
		}
	}
	return false
}

// {"version":"3.1","page":"…","download":"…exe","sha256":"…"}
func parseVersionFile(b []byte) (release, error) {
	var v struct {
		Version  string `json:"version"`
		Page     string `json:"page"`
		Download string `json:"download"`
	}
	if json.Unmarshal(b, &v) != nil || len(verParts(v.Version)) == 0 || strings.TrimSpace(v.Version) == "" {
		return release{}, errFormat
	}
	return release{version: strings.TrimPrefix(strings.TrimSpace(v.Version), "v"), page: v.Page, download: v.Download}, nil
}

// Rückfall: GitHub-Releases des gemeinsamen Repos. Tags wie WIN_v3.1_APK_v2.6: nur der WIN-Teil zählt.
func parseReleases(b []byte) (release, error) {
	var list []struct {
		Tag        string `json:"tag_name"`
		HTMLURL    string `json:"html_url"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
		Assets     []struct {
			Name string `json:"name"`
			URL  string `json:"browser_download_url"`
		} `json:"assets"`
	}
	if json.Unmarshal(b, &list) != nil {
		return release{}, errFormat
	}
	var best release
	for _, r := range list {
		if r.Draft || r.Prerelease || !strings.HasPrefix(r.Tag, "WIN_v") {
			continue
		}
		ver := strings.TrimPrefix(r.Tag, "WIN_v")
		if i := strings.Index(ver, "_"); i >= 0 {
			ver = ver[:i] // alles nach dem Windows-Teil gehört zu Android
		}
		if best.version != "" && !versionNewer(ver, best.version) {
			continue // höchste Version gewinnt, unabhängig von der Reihenfolge
		}
		dl := ""
		for _, a := range r.Assets {
			if strings.HasSuffix(strings.ToLower(a.Name), ".exe") {
				dl = a.URL
				break
			}
		}
		best = release{version: ver, page: r.HTMLURL, download: dl}
	}
	if best.version == "" {
		return release{}, errFormat
	}
	return best, nil
}
