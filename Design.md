# Design – shelved

Seit 0.5.0 (25.11.2026, Tom): **Toms Hausstil** statt Richtung C „Signal“ (Orange, Bricolage). Prototypen der alten Richtung: `prototype/`.

## Richtung
- **Produkt:** erfasst die eigenen Bücher per Regalfoto/Barcode, führt eine Wunschliste und zeigt den richtigen Platz im Regal.
- **Charakter:** ruhig, genau, sammelnd. **Welt:** Bibliothekskatalog, Signaturschild.
- **Konzept:** Die Oberfläche ist ein farbloser, linierter Karteikasten. Farbe kommt nur von den Büchern (Cover, Regalstreifen).
- **Moment:** Cover-Wand der Bibliothek, Regalstreifen mit echten Buchdicken, der Rahmen, der sich um einen Barcode legt.
- **Signature:** Signaturen, ISBNs und Positionen in JetBrains Mono (`R1 · B3 · 07`).

## Farben (ungetönte Neutrale)
| Token | Hell | Dunkel |
|---|---|---|
| `--bg` / `--surface` / `--soft` | `#fdfdfd` / `#fff` / `#f0f0f0` | `#161616` / `#202020` / `#2a2a2a` |
| `--line` / `--line-strong` / `--field` | `#e1e1e1` / `#c5c5c5` / `#8c8c8c` | `#393939` / `#545454` / `#6e6e6e` |
| `--t1` / `--t2` / `--t3` | `#161616` / `#4d4d4d` / `#6c6c6c` | `#f2f2f2` / `#b7b7b7` / `#898989` |
| `--ink` (Primärbutton, Auswahl) | `#181b1f` | `#c3c7ce` |
| `--live` (nur Punkt/Linie: „hast du“, Barcode eingerastet) | `#2e9e52` | `#4cb86a` |
| `--ok` / `--warn` / `--err` | `#007834` / `#b07a00` / `#b6322d` | `#4bcb71` / `#f8ae00` / `#fd7468` |

Hell/Dunkel: Schalter oben rechts in jeder Hauptansicht, gespeichert in `localStorage` (`shelved.theme`), ohne Wahl gilt das System. `js/theme.js` setzt das Theme vor dem ersten Paint.

## Typografie
- **Plus Jakarta Sans** 400–600 für alles, **JetBrains Mono** nur für Kennungen (Signatur, ISBN, Position, Exemplare).
- Größen: 11 (Tab-Labels) · 13 (Meta) · 15 (Text, Listen) · 17 (Abschnitte) · 20 (Dialogtitel) · 24 (Kennzahlen) · 33 (Seitentitel) · 52/72 (die eine große Zahl).
- Überschriften 600, −0.015em. Satzschreibung überall, `tabular-nums` global.
- Fonts selbst gehostet unter `/fonts` (DSGVO).

## Komponenten
- Cover: 2:3, Radius 4, Haarlinie statt Schatten. Ohne Cover: Typo-Cover in Farbe aus Hash; in 40-px-Listenzeilen nur die Farbe.
- Listen wie ein Kassenbuch: volle Haarlinien (0,5 px auf 2x-Schirmen), keine Karten. Kennzahlen als Reihe mit Haarlinien dazwischen.
- Status-Badge: 8-px-Punkt plus Text, voll rund.
- Buttons: Tinte gefüllt (primär), umrandet (sekundär), Radius 6, Höhe 40 (48 auf Touch). Hover hebt 1 px, Druck `scale(.97)`.
- Dialog: 450 breit, Buttons links, Primär zuerst, destruktiv in Fehlerfarbe. Ersetzt alle `confirm()`.
- Suchleiste oben in der Bibliothek mit **Kamera-Icon rechts im Feld**. Seit 0.5.2 (Tom, 25.11.) der einzige Einstieg zum Scannen und Hinzufügen, **kein FAB mehr**.
- **Eine Kamera, eine Option** (`#/add`, Tom 25.11.): Vollbild-Kamera ohne Auswahl. Barcodes werden live und fortlaufend gelesen. Der Auslöser macht ein Foto: mit Barcode ist es die ISBN, ohne Barcode entscheidet der Server (Scan-Modus `AUTO`) zwischen Einzelbuch und Regal (ab 3 Büchern). Ergebnis als Sheet über der Kamera. „Eintippen“ schaltet auf ein Feld für ISBN, Titel oder Autor, die App erkennt, was es ist. Regal mit Platzzuordnung: Kamera-Knopf im Tab Regale.
- Wunschliste: Suchleiste mit Kamera-Icon wie in der Bibliothek, dieselbe Kamera ohne Cover-Lesen.
- Laden: ab 150 ms ein Skelett in der Form der Ansicht, Cover-Boxen pulsieren leicht, bis das Bild da ist, und blenden dann ein. Ein Service Worker (`sw.js`) hält alle Cover von Bibliothek und Wunschliste auf dem Gerät und lädt neue im Hintergrund vor. Beim Abmelden wird der Cache geleert.
- Handys (≤ 480 px): Skala eine Stufe kleiner (Titel 28 px), Touch-Ziele 44 px.
- Navigation: Leiste unten (Bibliothek · Wunschliste · Regale · Statistik), ab 1024 px Seitenleiste 252 px mit Wortmarke, aktiver Eintrag mit Balken.
- Übersichten nutzen bis 1300 px Breite, Detail und Formulare bleiben bei 760.
- Touch-Targets ≥ 48px auf Touch-Geräten.

## Wunschliste (0.5.0)
- Tab „Wunschliste“: offene Wünsche mit Preis und Summe, darunter „Schon in der Bibliothek“. Teilen als Einkaufsliste.
- Merken per Barcode (auch im Buchladen: zeigt, ob man es hat), Cover-Foto, Galeriefoto oder Katalogsuche.
- Kommt ein gewünschtes Buch in die Bibliothek (Barcode, Katalog, von Hand, Regalfoto), fragt ein Dialog, ob es von der Liste soll. „Auf der Liste lassen“ merkt sich die Antwort.

## Anmeldung & Konto (14.11.2026, Bibliotheks-Ausbau)
- **Login-Seite** statt Sofort-Umleitung aufs Portal: Wortmarke „shelved.“, große Überschrift, Felder wie im Bearbeiten-Formular, ein Primärknopf in Tinte. Portal-Login („Mit tstieh-Portal") als Ghost-Button darunter, nur wenn konfiguriert. Keine Tab-Leiste auf Auth-Seiten (`body.auth`). Spalte max. 420px.
- **Registrieren**: Name, E-Mail, Passwort (≥ 10 Zeichen), Name der Bibliothek, Art als Chips: *Privat/geteilt · Öffentliche Bücherei · Firma/Fachbibliothek*. Über einen Einladungslink entfällt die Bibliothek, die Adresse ist vorbelegt und fest.
- **Konto-Seite** (`#/account`, erreichbar über den Avatar oben rechts in der Bibliothek): Abschnitte als Haarlinien-Listen wie Buchungen: Bibliothek (Name, Art, Wechseln, neue anlegen) · Team (nur Leitung: Mitglieder mit Rolle, Einladen, offene Einladungen) · Mein Konto (Name, E-Mail, Passwort) · Daten (Auskunft per Mail) · Abmelden · Konto löschen (Danger-Button, ganz unten).
- **Rollen** heißen in der UI: Eigentümer · Leitung · Mitarbeit · Nur lesen.

## Motion
View Transitions zwischen Screens (180 ms, ease-out `cubic-bezier(.23,1,.32,1)`). Dauer-Tokens `--dur-press` 120 · `--dur-menu` 200 · `--dur-card` 260 (Dialog, Toast). Keine Bewegung beim Hover außer 1 px Anheben. `prefers-reduced-motion` → nur kurzes Einblenden.
