# shelved

Bücherregal-Inventar: Regalfoto oder Barcode scannen, Bestand mit Covern ansehen, nach Nachname einsortieren, fehlende Reihenbände finden, Wert der Bibliothek schätzen.

## Woher die Daten kommen

| Was | Quelle | KI? |
|---|---|---|
| Titel, Verlag, Jahr, Seiten, Format, Ladenpreis | Deutsche Nationalbibliothek (SRU) | nein |
| Klappentext, Cover, Seiten | Google Books (API-Key), DNB/MVB, Open Library | nein |
| Reihe, Bandnummer, alle Bände einer Reihe | Wikidata | nein |
| Barcode → ISBN | Browser (BarcodeDetector) oder ZXing auf dem Server | nein |
| Buchrücken auf einem Regalfoto lesen | Claude (ein Aufruf pro Foto, ca. 3–10 Cent) | ja |
| Reihe/Bandliste, wenn Wikidata nichts weiß | Claude, gebündelt bzw. beim Öffnen der Reihe | ja, nur als Ersatz |

## Lokal starten

```bash
./scripts/dev.sh
```

Startet Postgres in Docker und die App mit Profil `local` (kein Login, alles gehört dem Nutzer `local`) auf http://localhost:8150. Keys liest das Skript aus `.env.anthropic` und `.env.google` (nicht im Repo).

## Tests

```bash
mvn verify
```

Braucht Docker (Testcontainers). Die Katalog-Clients werden gegen echte, als Fixtures gespeicherte Antworten getestet.

## Betrieb

`docker-compose.yml` mit App und Postgres, Image aus `ghcr.io/raindancer118/shelved`. Variablen siehe `.env.example`. Login per OIDC (Authentik), Profil `prod`.
