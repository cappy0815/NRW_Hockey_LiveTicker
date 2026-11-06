# NRW_Hockey_LiveTicker

Live-Ticker für laufende Spiele im Eishockeyverband NRW – als Windows-Programm und als Android-App.

| | Windows | Android |
|---|---|---|
| Name | EHV-NRW Live-Ticker | EHV Live |
| Version | 3.0.1 | 2.6 |
| Download | [EXE v3.0.1](https://github.com/cappy0815/NRW_Hockey_LiveTicker/releases/download/WIN_v3.0.1_APK_v2.51/EHV_NRW_LiveTicker_v3.0.1.exe) | [APK v2.6](https://cappy0815.github.io/downloads/EHV-Live-v2.6.apk) |

![Ticker-Fenster der Windows-Version](screenshot-windows.png)

## Funktionen

- Laufende und heute noch anstehende Spiele aller NRW-Ligen, eigene Teams farbig hervorgehoben
- Ticker je Spiel: Stand, Spielzeit, Drittelergebnisse, Tore und Strafen mit Vorlagen
- Spielplan (nächste 7 Tage), Ergebnisse (letzte 7 Tage), „Meine Spiele“
- Steckbrief mit Halle, Route und Kalendereintrag, Ligatabelle, Aufstellung beider Teams
- Sparsam: abgefragt werden nur Ligen, die heute spielen; der Spielplan wird höchstens alle 30 Minuten geladen

**Windows:** rahmenlose, verschiebbare Ticker-Fenster (bis zu 8), Töne für Tor, Strafe, Drittelende, Spielende und Bully.  
**Android:** schwebendes Floating-Display über anderen Apps, Benachrichtigung mit Stand, Vibration, Display-Wecker über dem Sperrbildschirm, Schiedsrichter-Einteilung im Ticker (aus esrw.de), Update-Prüfung (einmal am Tag, abschaltbar; installiert wird nichts automatisch).

## Installation

**Windows 10/11:** EXE herunterladen und starten, keine Installation nötig. Das Programm ist nicht signiert, daher zeigt Windows beim ersten Start „Der Computer wurde durch Windows geschützt“ → *Weitere Informationen* → *Trotzdem ausführen*. Einstellungen liegen unter `%APPDATA%\EHV-Live-Ticker`.

**Android 8.0 oder neuer:** APK herunterladen und öffnen; Installationen aus dieser Quelle einmal erlauben. Für das Floating-Display braucht die App die Berechtigung „Über anderen Apps einblenden“.

Prüfsummen (SHA-256) stehen auf [cappy0815.github.io](https://cappy0815.github.io).

## Quellcode

- `android/` – Android-App (Java, Build ohne Gradle über `build.sh`)

## Updates (Android)

Die App liest die Versionsdatei [`downloads/ehv-live.json`](https://cappy0815.github.io/downloads/ehv-live.json) auf cappy0815.github.io, als Rückfall das neueste Release dieses Repos. Gesendet wird nur eine normale Abfrage, keine Daten aus der App.

## Hinweis

Inoffizielles, privates Projekt ohne Verbindung zum Eishockeyverband NRW (EHV NRW) oder zu hockeydata.net. Angezeigt werden ausschließlich öffentlich zugängliche Spieldaten, wie sie auch auf ehv-nrw.de zu sehen sind, sowie die öffentlichen Schiedsrichter-Einteilungen von esrw.de (kein Bezug zu den Eishockey-Schiedsrichtern im Westen e.V.).

**Nur für die private Nutzung.** App und angezeigte Daten dürfen nicht für öffentliche Vorführungen, Übertragungen, Websites oder kommerzielle Zwecke verwendet werden.

Ohne Gewähr, kein Support.

Gebaut von Justus Jonas & MCHLNWK · [cappy0815.github.io](https://cappy0815.github.io)
