# shelved

[![CI/CD](https://github.com/Raindancer118/shelved/actions/workflows/ci.yml/badge.svg)](https://github.com/Raindancer118/shelved/actions/workflows/ci.yml)
![Java 25](https://img.shields.io/badge/Java-25-b07219)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-6db33f)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Bücherregal-Inventar: Regalfoto oder Barcode scannen, Bestand mit Covern ansehen, nach Nachname einsortieren, eine Wunschliste führen, Wert der Bibliothek schätzen. Läuft auf [shelf.tstieh.de](https://shelf.tstieh.de).

## Was es kann

- **Erfassen.** Eine Kamera für alles: Barcode, Cover oder ganzes Regal, auch alte Fotos aus der Galerie. Ein Sprachmodell liest Cover und Buchrücken, alles andere kommt aus offenen Katalogen.
- **Einsortieren.** Bücher bekommen einen Platz im Regal nach Nachname, die Brettbreite wird bei Bedarf aus dem Foto geschätzt.
- **Wunschliste.** Bücher merken, per Barcode im Buchladen gleich sehen, ob man es schon hat. Kommt ein gemerktes Buch in die Bibliothek, fragt die App, ob es von der Liste darf.
- **Wert und Versicherung.** Wert der Bibliothek schätzen und eine Inventarliste für die Hausratversicherung als PDF und CSV ausgeben.
- **Gemeinsam nutzen.** Bibliotheken mit Mitgliedern und Rollen (Eigentümer, Leitung, Mitarbeit, Nur lesen), Einladungen per Mail.

## Aufbau

Spring Boot mit JPA und Flyway auf PostgreSQL, ein Paket pro Fachbereich (`scan`, `metadata`, `wishlist`, `placement`, `pricing`, `inventory`, `library`, `account`, `ai`). Das Frontend ist eine schlanke PWA ohne Framework. KI wird nur dort eingesetzt, wo die Kataloge keine Antwort haben.

## Woher die Daten kommen

| Was | Quelle | KI? |
|---|---|---|
| Titel, Verlag, Jahr, Seiten, Format, Ladenpreis | Deutsche Nationalbibliothek (SRU) | nein |
| Klappentext, Cover, Seiten | Google Books (API-Key), DNB/MVB, Open Library | nein |
| Barcode → ISBN | Browser (BarcodeDetector) oder ZXing auf dem Server | nein |
| Buchrücken oder Cover auf einem Foto lesen | Claude Sonnet 5.5 (ein Aufruf pro Foto, ca. 2–6 Cent) | ja |
| Brettbreite, wenn unbekannt | aus Füllgrad im Foto und Buchdicke (0,1 mm pro Seite) | nein, kommt im selben Aufruf mit |

## Lokal starten

```bash
./scripts/dev.sh
```

Startet Postgres in Docker und die App mit Profil `local` (kein Login, alles gehört dem Nutzer `local`) auf http://localhost:8150. Keys liest das Skript aus `.env.anthropic` und `.env.google` (nicht im Repo).

## Tests

```bash
mvn verify
```

Braucht Docker (Testcontainers), ohne Docker geht es mit einer Wegwerf-Datenbank in `SHELVED_TEST_DB_URL`. Rund 115 Tests, die Katalog-Clients laufen gegen echte, als Fixtures gespeicherte Antworten der DNB, von Google Books und Open Library.

## Betrieb

Läuft auf umaroth (VM apps) unter https://shelf.tstieh.de. Push auf `main` → CI testet → selbst gehosteter Runner baut und startet `docker-compose.yml` (App + Postgres). Server-`.env` wird nie überschrieben, Vorlage `.env.example`, anlegen mit `scripts/prepare-server.sh`. Login per OIDC (Authentik), Profil `prod`.

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE).
