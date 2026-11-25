# Design – shelved

Seit 0.5.0 (25.11.2026, Tom): **Toms Hausstil** statt Richtung C „Signal“ (Orange, Bricolage). Prototypen der alten Richtung: `prototype/`.

## Richtung
- **Produkt:** erfasst die eigenen Bücher per Regalfoto/Barcode, zeigt fehlende Bände und den richtigen Platz im Regal.
- **Charakter:** ruhig, genau, sammelnd. **Welt:** Bibliothekskatalog, Signaturschild.
- **Konzept:** Die Oberfläche ist ein farbloser, linierter Karteikasten. Farbe kommt nur von den Büchern (Cover, Regalstreifen).
- **Moment:** Cover-Wand der Bibliothek, Regalstreifen mit echten Buchdicken, fehlende Bände als leere Plätze.
- **Signature:** Signaturen, ISBNs, Bandnummern und Positionen in JetBrains Mono (`R1 · B3 · 07`).

## Farben (ungetönte Neutrale)
| Token | Hell | Dunkel |
|---|---|---|
| `--bg` / `--surface` / `--soft` | `#fdfdfd` / `#fff` / `#f0f0f0` | `#161616` / `#202020` / `#2a2a2a` |
| `--line` / `--line-strong` / `--field` | `#e1e1e1` / `#c5c5c5` / `#8c8c8c` | `#393939` / `#545454` / `#6e6e6e` |
| `--t1` / `--t2` / `--t3` | `#161616` / `#4d4d4d` / `#6c6c6c` | `#f2f2f2` / `#b7b7b7` / `#898989` |
| `--ink` (Primärbutton, Auswahl) | `#181b1f` | `#c3c7ce` |
| `--live` (nur Punkt/Linie: „hast du“, Reihe komplett) | `#2e9e52` | `#4cb86a` |
| `--ok` / `--warn` / `--err` | `#007834` / `#b07a00` / `#b6322d` | `#4bcb71` / `#f8ae00` / `#fd7468` |

Hell/Dunkel: Schalter oben rechts in jeder Hauptansicht, gespeichert in `localStorage` (`shelved.theme`), ohne Wahl gilt das System. `js/theme.js` setzt das Theme vor dem ersten Paint.

## Typografie
- **Plus Jakarta Sans** 400–600 für alles, **JetBrains Mono** nur für Kennungen (Signatur, ISBN, Position, Bandnummer, Exemplare).
- Größen: 11 (Tab-Labels) · 13 (Meta) · 15 (Text, Listen) · 17 (Abschnitte) · 20 (Dialogtitel) · 24 (Kennzahlen) · 33 (Seitentitel) · 52/72 (die eine große Zahl).
- Überschriften 600, −0.015em. Satzschreibung überall, `tabular-nums` global.
- Fonts selbst gehostet unter `/fonts` (DSGVO).

## Komponenten
- Cover: 2:3, Radius 4, Haarlinie statt Schatten. Ohne Cover: Typo-Cover in Farbe aus Hash; in 40-px-Listenzeilen nur die Farbe.
- Fehlender Reihenband: leerer Platz mit gestrichelter Kontur (einzige gestrichelte Linie der App), antippen = merken, gemerkt = durchgezogene Kontur.
- Listen wie ein Kassenbuch: volle Haarlinien (0,5 px auf 2x-Schirmen), keine Karten. Kennzahlen als Reihe mit Haarlinien dazwischen.
- Status-Badge: 8-px-Punkt plus Text, voll rund.
- Buttons: Tinte gefüllt (primär), umrandet (sekundär), Radius 6, Höhe 40 (48 auf Touch). Hover hebt 1 px, Druck `scale(.97)`.
- Dialog: 450 breit, Buttons links, Primär zuerst, destruktiv in Fehlerfarbe. Ersetzt alle `confirm()`.
- Suchleiste oben in der Bibliothek mit **Kamera-Icon rechts im Feld** (Tom, 14.11.): **nur Nachschlagen**. Barcode → »hab ich das schon?«, Treffer öffnet die Detailseite, sonst »In die Bibliothek« oder »Merken«.
- **FAB „Hinzufügen“** (Tinte, unten rechts). Modi als Listenzeilen: Regal · Barcode · Buch · Von Hand.
- Navigation: Leiste unten (Bibliothek · Wunschliste · Reihen · Regale · Statistik), ab 1024 px Seitenleiste 252 px mit Wortmarke, aktiver Eintrag mit Balken.
- Übersichten nutzen bis 1300 px Breite, Detail und Formulare bleiben bei 760.
- Touch-Targets ≥ 48px auf Touch-Geräten.

## Wunschliste (0.5.0)
- Tab „Wunschliste“: offene Wünsche mit Preis und Summe, darunter „Schon in der Bibliothek“. Teilen als Einkaufsliste.
- Merken per Barcode (auch im Buchladen: zeigt, ob man es hat), Katalogsuche oder fehlendem Reihenband (einzeln oder alle).
- Kommt ein gewünschtes Buch in die Bibliothek (Barcode, Katalog, von Hand, Regalfoto), fragt ein Dialog, ob es von der Liste soll. „Auf der Liste lassen“ merkt sich die Antwort.

## Anmeldung & Konto (14.11.2026, Bibliotheks-Ausbau)
- **Login-Seite** statt Sofort-Umleitung aufs Portal: Wortmarke „shelved.“, große Überschrift, Felder wie im Bearbeiten-Formular, ein Primärknopf in Tinte. Portal-Login („Mit tstieh-Portal") als Ghost-Button darunter, nur wenn konfiguriert. Keine Tab-Leiste auf Auth-Seiten (`body.auth`). Spalte max. 420px.
- **Registrieren**: Name, E-Mail, Passwort (≥ 10 Zeichen), Name der Bibliothek, Art als Chips: *Privat/geteilt · Öffentliche Bücherei · Firma/Fachbibliothek*. Über einen Einladungslink entfällt die Bibliothek, die Adresse ist vorbelegt und fest.
- **Konto-Seite** (`#/account`, erreichbar über den Avatar oben rechts in der Bibliothek): Abschnitte als Haarlinien-Listen wie Buchungen: Bibliothek (Name, Art, Wechseln, neue anlegen) · Team (nur Leitung: Mitglieder mit Rolle, Einladen, offene Einladungen) · Mein Konto (Name, E-Mail, Passwort) · Daten (Auskunft per Mail) · Abmelden · Konto löschen (Danger-Button, ganz unten).
- **Rollen** heißen in der UI: Eigentümer · Leitung · Mitarbeit · Nur lesen.

## Motion
View Transitions zwischen Screens (180 ms, ease-out `cubic-bezier(.23,1,.32,1)`). Dauer-Tokens `--dur-press` 120 · `--dur-menu` 200 · `--dur-card` 260 (Dialog, Toast). Keine Bewegung beim Hover außer 1 px Anheben. `prefers-reduced-motion` → nur kurzes Einblenden.
