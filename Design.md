# Design – shelved

Richtung **C · Signal**, Variante „Bücher zuerst“ (gewählt 14.11.2026). Prototyp: `prototype/signal-v2.html`, verworfene Richtungen A/B in `prototype/index.html`.

## Haltung
Banking-App-Klarheit, aber die Bücher sind der Inhalt. Weiß, Haarlinien statt Schatten, eine große Zahl, wo eine Zahl wichtig ist. Orange nur für das, was man tun soll. Cover tragen die Farbe, die UI hält sich zurück.

Referenzen: Orbital Digital Banking (Behance 255621121, Reduktion auf eine Akzentfarbe, starke Typo-Skala), Banking-Muster von Monzo/N26/Revolut (Schnellaktion, Liste wie Buchungen, progressive disclosure). Übertragen: Reihen = Sparziele (Fortschritt), Bestand = Buchungsliste, Wert = Kontostand, aber nachrangig im Tab Statistik.

## Farben
| Token | Hell | Dunkel |
|---|---|---|
| `--bg` | `#ffffff` | `#0d0d0c` |
| `--ink` | `#0d0d0d` | `#f3f2ef` |
| `--muted` | `#7a7a7a` | `#8f8e8a` |
| `--line` | `#e6e6e4` | `#262624` |
| `--soft` | `#f2f2f0` | `#1a1a19` |
| `--accent` | `#ff4f1f` | `#ff6a3d` |
| `--accent-ink` (Text) | `#e8400f` | `#ff7b52` |

Warme Neutraltöne (leichter Gelbstich) statt kaltem Grau. Dark Mode über `prefers-color-scheme`.

## Typografie
- **Bricolage Grotesque** 500/700: Überschriften, große Zahlen, Regal-Signaturen (`R2 · B3 · 07`). Display-Größen mit `letter-spacing:-.02…-.04em`.
- **Instrument Sans** 400/500/600: alles andere.
- Zahlen `font-variant-numeric: tabular-nums`.
- Fonts selbst gehostet unter `/fonts` (keine Google-CDN, DSGVO).

## Komponenten
- Cover: Seitenverhältnis 2:3, Radius 4px, 1px-Kontur. Fehlt ein Cover: Typo-Cover (Titel/Autor auf Farbe aus Hash).
- Fehlender Reihenband: gestrichelter Platzhalter, schraffiert, Band-Nr. + Titel + „fehlt“ in Orange.
- Chips: Radius 8px, aktiv = schwarz gefüllt.
- Primäraktion: oranger Button/FAB, Radius 12–14px, kein Pill.
- Listen: Haarlinien `#ededeb`, keine Karten-Schatten.
- Suchleiste oben in der Bibliothek mit **Kamera-Icon rechts im Feld** (Tom, 14.11.): **nur Nachschlagen**. Barcode scannen oder Buch fotografieren → »hab ich das schon?«, Treffer öffnet die Detailseite, kein Treffer bietet »Hinzufügen« an.
- **FAB „Hinzufügen“** (orange, unten rechts): Bücher in die Bibliothek aufnehmen. Modus-Umschalter **Barcode · Buch · Regal · Von Hand**. Regal = ganzes Regalbrett fotografieren, Claude liest die Rücken.
- Tab-Leiste: Bibliothek · Reihen · Regale · Statistik. Aktives Icon orange.
- Touch-Targets ≥ 48px.

## Anmeldung & Konto (14.11.2026, Bibliotheks-Ausbau)
- **Login-Seite** statt Sofort-Umleitung aufs Portal: Wortmarke „shelved" (Bricolage 700, Punkt in Orange), große Überschrift, Felder wie im Bearbeiten-Formular, ein oranger Primärknopf. Portal-Login („Mit tstieh-Portal") als Ghost-Button darunter, nur wenn konfiguriert. Keine Tab-Leiste auf Auth-Seiten (`body.auth`). Spalte max. 420px.
- **Registrieren**: Name, E-Mail, Passwort (≥ 10 Zeichen), Name der Bibliothek, Art als Chips: *Privat/geteilt · Öffentliche Bücherei · Firma/Fachbibliothek*. Über einen Einladungslink entfällt die Bibliothek, die Adresse ist vorbelegt und fest.
- **Konto-Seite** (`#/account`, erreichbar über den Avatar oben rechts in der Bibliothek): Abschnitte als Haarlinien-Listen wie Buchungen: Bibliothek (Name, Art, Wechseln, neue anlegen) · Team (nur Leitung: Mitglieder mit Rolle, Einladen, offene Einladungen) · Mein Konto (Name, E-Mail, Passwort) · Daten (Auskunft per Mail) · Abmelden · Konto löschen (Danger-Button, ganz unten).
- **Rollen** heißen in der UI: Eigentümer · Leitung · Mitarbeit · Nur lesen.

## Motion
View Transitions zwischen Screens (Cover → Detail morpht). `prefers-reduced-motion` → keine Transition.
