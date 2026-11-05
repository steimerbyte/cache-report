# Cache Report

Zeigt Cache, Daten und App-Größe jedes Pakets auf dem Gerät und kann den Cache global leeren.
Package `com.steimerbyte.cachereport`, eine Activity, Jetpack Compose, kein Backend.

## Zweck

`dumpsys diskstats` liefert drei parallele Zahlenfelder (Cache, Daten, App) zu allen Paketen,
aber nicht lesbar. Die App macht daraus eine Liste mit Suche, Sortierung, Filter für Pakete
über 100 MB und Balken. Dazu laufen zwei Live-Histogramme für CPU-Last und RAM-Verbrauch.
Der Trim-Knopf leert die Caches aller Apps über `pm trim-caches` – das ist eine globale,
nicht paketgenaue Operation.

## Layoutsystem

Die Oberfläche folgt dem verbindlichen Layoutsystem der NothingOS-App-Familie
(`~/.omp/agent/managed-skills/nothingos-app/SKILL.md`): Tokens, Typografie-Tabelle,
Seitenhierarchie und Komponenten-Signaturen sind von dort übernommen, nicht neu erfunden.

| Slot | Inhalt dieser App |
|---|---|
| OSW | Kicker `SCHNAPPSCHUSS hh:mm · NICHT AUTOMATISCH`, Titel `CACHE REPORT`, `MiniButton` AKTUALISIEREN |
| Live-Panel | `LivePanel`: zwei 48-Slot-Histogramme (CPU, RAM), Wert rechts in `Value` |
| Summary | `SummaryPanel` mit GESAMT-CACHE, DATEN und APP (App Size), darunter `438 PAKETE · 259 MIT CACHE · TOP 10 82,0 %` |
| Controls | `SearchField` (Trefferzahl rechts im Feld), `Pill` GRÖSSE/NAME, `Pill` NUR > 100 MB |
| Liste | Label und Paket links, Cache-Wert in `Value` rechts, darunter `Mono` `DATEN x · GESAMT y`, darunter `Bar` mit dem Anteil am Gesamt-Cache |
| Aktion | `WideButton` CACHE LEEREN, darunter `Hint` mit dem Trockenlauf (`pm trim-caches 1 · LÖSCHT NICHTS`) |
| Overlays | About (Tippen auf die Kicker-Zeile), Bestätigungsdialog des echten Trim, Fehler- und Shizuku-Panel |

Der Fortschritt der Warteschlange steht als `LADE n / 438` im Kopf. Kopf und
Aktionsblock stehen fest, alles dazwischen scrollt als eine Liste — deshalb bleibt
im Querformat nichts abgeschnitten.

### About

Tippen auf die Kicker-Zeile im OSW öffnet die About-Sektion (`AboutPanel`), kein
eigener Knopf. Inhalt in dieser Reihenfolge: App-Name in `Title`,
`Version 1.3.0 · Nothing A059P`, **`Function over form.`** allein stehend in `Accent`,
ein Satz Zweck und die tatsächlich genutzten Kommandos als `Mono`-Liste
(`dumpsys diskstats`, `cat /proc/stat`, `cat /proc/meminfo`, `pm trim-caches <zielwert>`).
Kein Impressum, keine Lizenz, keine Links — die stehen hier im README.

### Abweichungen vom Skill

1. **`SummaryPanel` bricht nach zwei Werten um.** Der Skill erlaubt 2–4 Spalten; Werte
   wie `15,21 GiB` passen bei 22 sp nicht in vier Spalten, deshalb zwei Spalten pro Zeile.
2. **`Hint` trägt hier die Trockenlauf-Aktion.** Der Skill sieht Antippen zum Ausblenden
   mit `SharedPreferences`-Merker vor. Ausblenden würde die Aktion unerreichbar machen;
   die Zeile bleibt deshalb antippbar und startet `pm trim-caches 1` (Nullop), aber
   dauerhaft sichtbar.
3. **`LivePanel` hat zwei optionale Textparameter** (`cpuText`, `ramText`). Der RAM-Wert
   ist das GiB-Paar aus `MemTotal`/`MemAvailable` und lässt sich aus dem Prozentverlauf
   allein nicht rekonstruieren.

Der Balken pro Listenzeile zeigt den Anteil am **Gesamt-Cache** (vorher: Anteil am
größten Paket) — reine Darstellung, die Zahlen selbst sind unverändert.

## Genutzte Kommandos

Alle Kommandos laufen als UID 2000 (shell) über Shizuku
(`IShizukuService.newProcess`), nicht über `Runtime.exec` der App.

| Zweck | Kommando |
|---|---|
| Datenquelle | `dumpsys diskstats` |
| CPU | `cat /proc/stat`, Zeile `cpu `, `idle = idle + iowait` |
| RAM | `cat /proc/meminfo`, `MemTotal` und `MemAvailable` |
| Trim (echt) | `pm trim-caches 9223372036854775807` |
| Trockenlauf | `pm trim-caches 1` |

### Parsen

`DiskStats.parse` liest `Package Names`, `Cache Sizes`, `App Data Sizes` und `App Sizes`
als zusammenhängenden Text zwischen `[` und `]`, nicht zeilenweise – der Dump bricht die
Felder beliebig um. Alle vier Felder müssen **gleich lang** sein, sonst gilt der Dump als
unbrauchbar: ein Filter oder ein `mapNotNull` würde die Indizes verschieben und jede Größe
am falschen Paket landen. Der Fehler wird mit Ursache angezeigt, z. B.
`Arrays unterschiedlich lang: Cache Sizes 437 vs Pakete 438`.

`App Cache Size` und `App Data Size` werden zusätzlich als Kopfzeilen-Gegenprobe gelesen;
weicht eine Summe ab, steht das als Warnung in der Kopfzeile. Fehlt eine Kopfzeile im
Dump, bleibt sie `-1` und die Warnung entfällt (es wird nichts geraten).

**„GESAMT" pro Zeile ist `App Sizes`, also die App-/Code-Größe, nicht Cache+Daten.**
Das Feld kann je Paket kleiner als der Cache sein (Play Store: Cache 5465,7 MB,
GESAMT 126,4 MB). Die Summe über alle Pakete stimmt trotzdem exakt mit der
Kopfzeile `App Size` überein (siehe Messungen).

### Ladevorgang als Warteschlange

Der Dump kommt in einem Aufruf, die Liste wird aber nicht in einem Schub materialisiert:
Nach dem Parsen schiebt eine Schleife die Einträge **einzeln** in die Liste
(`yield()` + 4 ms Pause pro Eintrag). Im Kopf steht dann `LADE 137 / 438`, die
Trefferzahl wächst mit, Bedienelemente bleiben ansprechbar; die Knöpfe, die einen
Shell-Lauf starten, sind bis zum letzten Eintrag gesperrt (`LÄDT …` / `LÄUFT …`),
damit kein zweiter Lauf in die Schleife greift. Suche und Sortierung lassen sich
während des Ladens bedienen. Die Sortierung greift in jedem Schritt auf die volle
Liste, die Reihenfolge ist deshalb schon während des Ladens korrekt und nach dem
letzten Eintrag unverändert.

Es gibt **keinen** Einzel-App-Trim und keinen Fake dafür: `pm trim-caches` ist global.
Eine künftige Stapelaktion müsste durch dieselbe Schlange laufen.

## Bedienung

- **Suche** unter dem Summary-Panel, live gegen Label **oder** Package, `×` leert sie.
  Die Trefferzahl steht rechts im Suchfeld (`2 TREFFER`); die Gesamtzahl der Pakete
  steht in der Zeile unter dem Summary-Panel (`438 PAKETE · 259 MIT CACHE · TOP 10 82,0 %`).
- **Sortierung** per Pill: `GRÖSSE` (absteigend, Standard) oder `NAME` (alphabetisch,
  `java.text.Collator` für Deutsch; Satzzeichen zählen mit, deshalb steht
  „Google Play Store" vor „Google Play-Dienste", und Labels mit führender Ziffer wie
  „3 Button Navigation Bar" stehen vor Buchstaben).
- **Filter** `NUR > 100 MB` wirkt zusätzlich zur Suche.
- **AKTUALISIEREN** ist der `MiniButton` im OSW; während des Ladens zeigt er `LÄDT …`.
- **Zustände**: `MESSE …` (erster Dump), `LADE n / 438` im Kopf, `KEIN TREFFER FÜR "…"`,
  `KEIN PAKET ÜBER 100 MB`, Parse-Fehler mit Ursache.
- Suchtext, Sortierung und Filter liegen in `rememberSaveable` und überleben Rotation.

## CPU- und RAM-Histogramm

`LivePanel`, die zweite Stufe der Seitenhierarchie: zwei Zeilen mit je einem
46-dp-Histogramm (48 Slots) und dem Wert rechts in `Value`. Sampler wie im Profile
Optimizer (dort verifiziert): alle 700 ms ein
`/proc/stat`-Sample, `load = 1 − Δidle/Δgesamt`, dazu `used = (MemTotal − MemAvailable)`.
Sampling läuft im IO-Dispatcher, das UI-Update auf Main; der `LaunchedEffect` liegt im
Screen, mit dem Verlassen der Composition endet der Sampler. Fehlende Werte lassen das
Histogramm leer und zeigen `KEINE DATEN`, statt die App zu stören.

## Gemessene Zahlen (Nothing Phone 3a Pro, Android 17, 05.11.2026)

Dump vom Testgerät, 438 Pakete, alle vier Arrays 438 lang.

| Größe | Shell/Dump | UI |
|---|---|---|
| Summe Cache | `App Cache Size: 16335937024` B | 15,21 GiB – Abweichung **0 B** |
| Summe Daten | `App Data Size: 39442312941` B | 36,73 GiB – Abweichung **0 B** |
| Summe App (`App Sizes`) | `App Size: 22115613184` B | `APP (App Size)` 20,60 GiB – Abweichung **0 B** |
| Pakete / mit Cache / Top-10-Anteil | 438 / 259 / 81,96 % | 438 / 259 / 82,0 % |
| Pakete > 100 MB | 19 | Filter `NUR > 100 MB` → `19 TREFFER` |

Alle drei Summen stehen im `SummaryPanel` (GESAMT-CACHE, DATEN, APP (App Size)) und
stammen aus demselben Dump, der unmittelbar vorher per `adb shell dumpsys diskstats`
gezogen wurde.

Gegenprobe der Spitze, Wert für Wert (Bytes → MB wie in der UI gerechnet):

| Paket | Cache | Daten | App |
|---|---|---|---|
| com.android.vending | 5.731.176.448 → 5465,7 MB | 5.767.409.664 → 5500,2 MB | 132.568.576 → 126,4 MB |
| com.brave.browser | 1.869.365.248 → 1782,8 MB | 2.290.716.672 → 2184,6 MB | 272.156.160 → 259,5 MB |
| com.nothing.gallery | 1.785.167.872 → 1702,5 MB | 1.832.222.720 → 1747,3 MB | 89.892.352 → 85,7 MB |

Suche am Gerät: `play` → 10 Treffer (u. a. Google Play Store, Google Play-Dienste,
One Player); `play` + Filter `> 100 MB` → 2 Treffer (Play Store 5465,7 MB,
Play-Dienste 101,4 MB); `zzzz` → 0 Treffer und `KEIN TREFFER FÜR zzzz`.

CPU und RAM im Paar gegen die Shell gemessen (Screenshot und Kommando im selben Zug):

| Runde | UI (CPU) | `top -n1 -b`, Last = (800 − idle)/800 |
|---|---|---|
| 1 | 51 % | 56,2 % |
| 2 | 36 % | 29,0 % |
| 3 | 35 % | 33,0 % |
| 4 | 31 % | 31,2 % |

Die Fenster der beiden Messungen sind nicht deckungsgleich (700-ms-Sample bzw.
`top`-Intervall), die Abweichung liegt bei 0,2 bis 7 Punkten. RAM gegen
`/proc/meminfo`:

| Zeitpunkt | `MemTotal` / `MemAvailable` | UI |
|---|---|---|
| 17:07 | 11.680.596 kB / 6.136.416 kB → 5,29 / 11,14 GiB | „5,3/11,1 GiB" |
| 17:21 | 11.680.596 kB / 7.012.196 kB → 4,45 / 11,14 GiB | „4,4/11,1 GiB" |

Der Unterschied zwischen den Zeilen ist echte Speicherfreigabe, kein Messfehler.

## Trim: Argument ist der Zielwert, nicht die Löschmenge

`FreeStorageHelper.freeStorage` steigt sofort aus, wenn
`file.getUsableSpace() >= bytes`. Das Argument von `pm trim-caches` ist also der
**angestrebte freie Platz**, nicht die zu löschende Datenmenge:

- `pm trim-caches 1` → freier Platz (111 GB) ≥ 1 → **Nullop**. Am Gerät gemessen:
  `App Cache Size` vor und nach dem Aufruf identisch 16.335.937.024 B.
- `pm trim-caches 9223372036854775807` (`Long.MAX_VALUE`) → unerreichbar, die
  Cache-Räumung läuft durch Quota- und Unter-Quota-Stufe, also alles.

Deshalb steht `Long.MAX_VALUE` hinter „CACHE LEEREN" und `1` hinter dem
Trockenlauf. Ein Test mit `999999999999` wäre **nicht** harmlos gewesen, sondern
hätte wie der echte Knopf alle Caches gelöscht.

**Echter Trim nicht ausgeführt.** Auf dem Gerät gelaufen ist nur der Nullop
(`pm trim-caches 1`) über die Trockenlauf-Zeile (`Hint`) und zusätzlich direkt über
`adb shell`: Ausgabe leer, Statuszeile „pm trim-caches 1 · keine Ausgabe",
„FREIGEGEBEN 0", Gesamt-Cache unverändert 15,21 GiB. Der Live-Knopf wurde in der App
nie bestätigt; der Bestätigungsdialog stand einmal offen (Fehltap eines anderen
Agenten) und wurde per `am force-stop` geschlossen, ohne dass ein Trim lief —
`App Cache Size` danach unverändert 16.335.937.024 B.

## Screenshots (Belege, `screenshots/`)

| Datei | Belegt |
|---|---|
| `01-kopf-summary.png` | OSW (`SCHNAPPSCHUSS 18:13 · NICHT AUTOMATISCH`, AKTUALISIEREN), Live-Panel, Summary mit 15,21 GiB / 36,73 GiB / 20,60 GiB, Kennzahlenzeile, Steuerzeile mit `438 TREFFER` |
| `02-liste.png` | Liste nach zwei Wischbewegungen: Cache-Wert rechts in `Value`, `DATEN … · GESAMT …`, Anteilsbalken |
| `03-suche-filter.png` | Suche `play` + Filter `NUR > 100 MB` → 2 Treffer, beide Pills aktiv, `×` im Feld |
| `04-laden.png` | Warteschlange: `LADE n / 438` im Kopf, `LÄDT …` im OSW, `LÄUFT …` unten |
| `05-quer.png` | Querformat nach zwei Wischen im Listenbereich: OSW, Sortier-Pills, Listenzeilen (Play Store 5465,7 MB, Brave 1782,8 MB) und der fixierte Aktionsblock |
| `06-about.png` | About-Sektion aus der Kicker-Zeile: Name, `Version 1.3.0 · Nothing A059P`, `Function over form.` in Rot, Zweck „Zeigt alle drei Größen je Paket und leert den Cache global.", Kernkommandos |

Ältere Aufnahmen derselben App aus der Zeit vor dem Layoutsystem liegen unverändert
als `01-liste.png` … `08-quer-liste.png` daneben; sie zeigen die frühere Anordnung
(Kopfzeile im Scrollbereich, Mikro-Knopf für den Trockenlauf, Balken relativ zum
größten Paket).

## Was ungetestet blieb

- **Der echte Trim** (`Long.MAX_VALUE`) auf dem Gerät – bewusst nicht ausgeführt,
  er würde ~15 GiB Caches aller 438 Apps löschen. Geprüft ist damit der Aufrufweg
  (Shizuku → UID 2000 → `pm`), nicht die Wirkung.
- **Differenzanzeige > 0**: ohne echten Trim steht „FREIGEGEBEN 0"; die Rechnung
  `vorher − nachher` ist nur mit diesem Wert belegt.
- **Parse-Fehler am Gerät**: der Zustand ist nur über die Unit-Tests
  (`DiskStatsTest`, u. a. „Arrays unterschiedlich lang") belegt; einen defekten Dump
  kann man auf dem Gerät nicht erzeugen. Die Anzeige des Fehlers ist derselbe Pfad wie
  `MESSE …`.
- **Geräte mit anderem `diskstats`-Format** (Samsung/OEM): der Parser wurde nur
  gegen den Dump dieses Geräts geprüft; fehlende Arrays führen zu einer benannten
  Fehlermeldung statt zu falschen Zahlen.
- **Querformat** zeigt OSW und Aktionsblock fest, alles dazwischen scrollt als eine
  Liste (~410 dp Höhe reichen nicht für Panels und Liste gleichzeitig). Suchtext,
  Sortierung und Filter überleben die Drehung; während des Neuladens nach der Drehung
  steht `MESSE …`, weil der Schnappschuss am Activity-Objekt hängt.
- Die Paketanzahl ist geräte- und zeitabhängig (während der Tests 438 im
  `diskstats`-Dump); sie wird bei jedem Start neu gemessen, nicht festgeschrieben.

## Unit-Tests

`app/src/test/java/com/steimerbyte/cachereport/DiskStatsTest.kt` prüft die drei Größen
pro Paket, die Summen gegen die Kopfzeilen, die Ursache bei ungleichen Array-Längen und
das fehlende Feld. Lauf: `./gradlew testDebugUnitTest`.
