package de.raindancer118.shelved.account;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import de.raindancer118.shelved.library.Library;
import de.raindancer118.shelved.library.Membership;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The data export as a document to read and print: laid out in HTML/CSS in the app's house style and rendered to
 * PDF. Covers are scaled down and embedded, so the mail stays a few megabytes even for big libraries.
 */
public final class ExportPdf {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN).withZone(ZONE);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN).withZone(ZONE);
    private static final Map<Membership.Role, String> ROLES = Map.of(Membership.Role.OWNER, "Eigentümer",
            Membership.Role.ADMIN, "Leitung", Membership.Role.STAFF, "Mitarbeit", Membership.Role.VIEWER, "Nur lesen");
    private static final Map<Library.Kind, String> KINDS = Map.of(Library.Kind.PRIVATE, "Privat/geteilt",
            Library.Kind.PUBLIC, "Öffentliche Bücherei", Library.Kind.COMPANY, "Firma/Fachbibliothek");
    // the same colours the app gives books without a cover
    private static final String[] PALETTE = {"#3d5a80", "#8a3b2e", "#2f5d50", "#6b4f7a", "#9a6b1f", "#38495a", "#7a2e4a",
            "#4f6b2f", "#5a3d2b", "#2e5a7a"};
    private static final int COVER_W = 200, COVER_H = 300;

    private ExportPdf() {
    }

    /** @param cover the stored cover file for a cover file name, if there is one */
    public static byte[] render(DataExport.Export e, Function<String, Optional<Path>> cover) {
        String html = html(e, cover);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            font(b, "PlusJakartaSans-Regular.ttf", "Jakarta", 400);
            font(b, "PlusJakartaSans-Medium.ttf", "Jakarta", 500);
            font(b, "PlusJakartaSans-SemiBold.ttf", "Jakarta", 600);
            font(b, "JetBrainsMono-Medium.ttf", "Mono", 500);
            b.withHtmlContent(html, null);
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void font(PdfRendererBuilder b, String file, String family, int weight) {
        b.useFont(() -> ExportPdf.class.getResourceAsStream("/pdf-fonts/" + file), family, weight,
                BaseRendererBuilder.FontStyle.NORMAL, true);
    }

    static String html(DataExport.Export e, Function<String, Optional<Path>> cover) {
        DataExport.Person p = e.person();
        String who = p.name() != null && !p.name().isBlank() ? p.name() : p.email();
        StringBuilder h = new StringBuilder(64_000);
        h.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"de\"><head><title>")
                .append(esc("Deine Daten bei shelved")).append("</title><style>").append(css(who)).append("</style></head><body>");

        // ---- title page: the books carry the colour, the page stays quiet
        h.append("<div class=\"mark\">shelved<span>.</span></div>");
        h.append("<h1>Deine Daten</h1>");
        h.append("<p class=\"lead\">Alles, was shelved über dich speichert. Stand: ").append(esc(DAY.format(e.createdAt())))
                .append(". Auskunft nach Art. 15 DSGVO.</p>");
        h.append("<p class=\"who\"><b>").append(esc(who)).append("</b><br/>").append(esc(nz(p.email()))).append("</p>");
        h.append("<table class=\"kpis\"><tr>")
                .append(kpi(e.books(), e.books() == 1 ? "Buch" : "Bücher"))
                .append(kpi(e.wishes(), e.wishes() == 1 ? "Wunsch" : "Wünsche"))
                .append(kpi(e.shelves(), e.shelves() == 1 ? "Regal" : "Regale"))
                .append(kpi(e.libraries().size(), e.libraries().size() == 1 ? "Bibliothek" : "Bibliotheken"))
                .append("</tr></table>");
        List<String> strip = e.libraries().stream().flatMap(l -> l.books().stream())
                .map(DataExport.BookEntry::coverFile).filter(f -> f != null).limit(7).toList();
        if (!strip.isEmpty()) {
            h.append("<table class=\"strip\"><tr>");
            for (String f : strip) {
                h.append("<td>").append(cover(cover, f, "", "")).append("</td>");
            }
            h.append("</tr></table>");
        }

        // ---- account and memberships
        h.append("<div class=\"section\"><h2>Dein Konto</h2><table class=\"facts\">")
                .append(fact("Name", nz(p.name())))
                .append(fact("E-Mail", nz(p.email())))
                .append(fact("E-Mail bestätigt", p.emailConfirmed() ? "ja" : "nein"))
                .append(fact("Konto angelegt", p.accountCreated() == null ? "–" : DAY.format(p.accountCreated())))
                .append(fact("Anmeldung", Stream.of(p.passwordSet() ? "mit Passwort (gespeichert nur als bcrypt-Hash)" : null,
                        p.portalLogin() ? "über das tstieh-Portal" : null).filter(s -> s != null).collect(Collectors.joining(", "))))
                .append("</table>");
        h.append("<h3>Bibliotheken</h3><table class=\"rows\"><tr class=\"head\"><td>Name</td><td>Art</td><td>Deine Rolle</td><td class=\"r\">Mitglied seit</td></tr>");
        for (DataExport.LibraryPart l : e.libraries()) {
            h.append("<tr><td><b>").append(esc(l.name())).append("</b></td><td>").append(esc(KINDS.getOrDefault(l.kind(), "–")))
                    .append("</td><td>").append(esc(ROLES.getOrDefault(l.role(), "–"))).append("</td><td class=\"r\">")
                    .append(l.memberSince() == null ? "–" : esc(SHORT.format(l.memberSince()))).append("</td></tr>");
        }
        h.append("</table></div>");

        // ---- each library: books as a cover grid, then wishes and shelves
        for (DataExport.LibraryPart l : e.libraries()) {
            int copies = l.books().stream().mapToInt(DataExport.BookEntry::copies).sum();
            h.append("<div class=\"section\"><p class=\"eyebrow\">").append(esc(l.name())).append("</p><h2>Bücher</h2><p class=\"sub\">")
                    .append(copies).append(copies == 1 ? " Buch" : " Bücher");
            if (copies != l.books().size()) {
                h.append(" in ").append(l.books().size()).append(" Titeln");
            }
            h.append(", sortiert wie im Regal: nach Nachname, dann Jahr.</p>");
            h.append(l.books().isEmpty() ? "<p class=\"sub\">Noch keine.</p>" : grid(l.books().stream().map(b -> card(cover, b.coverFile(), b.title(),
                    first(b.authors()), Stream.of(b.location(), b.copies() > 1 ? b.copies() + " Exemplare" : null, b.isbn13())
                            .filter(s -> s != null).toList())).toList()));
            String wishHead = "<h2 class=\"next\">Wunschliste</h2><p class=\"sub\">" + l.wishes().size()
                    + (l.wishes().size() == 1 ? " Buch gemerkt." : " Bücher gemerkt.") + "</p>";
            h.append(keepWithFirstRow(wishHead, l.wishes().stream().map(w -> card(cover, w.coverFile(), w.title(), first(w.authors()),
                    Stream.of(w.added() == null ? null : "gemerkt am " + SHORT.format(w.added()), w.isbn13()).filter(s -> s != null).toList()))
                    .toList()));
            h.append("<div class=\"keep\"><h2 class=\"next\">Regale und Fotos</h2><table class=\"rows\"><tr class=\"head\"><td>Regal</td><td class=\"r\">Böden, Innenbreite von oben</td></tr>");
            if (l.shelves().isEmpty()) {
                h.append("<tr><td colspan=\"2\">Keine Regale angelegt.</td></tr>");
            }
            for (DataExport.ShelfEntry s : l.shelves()) {
                h.append("<tr><td><b>").append(esc(s.name())).append("</b></td><td class=\"r mono\">")
                        .append(esc(s.boardWidthsMm().stream().map(w -> w == null ? "?" : NumberFormat.getInstance(Locale.GERMAN).format(w / 10.0) + " cm")
                                .collect(Collectors.joining("  ·  ")))).append("</td></tr>");
            }
            h.append("</table><p class=\"sub\">").append(l.scans()).append(l.scans() == 1 ? " Foto" : " Fotos")
                    .append(" wurden zum Erfassen ausgewertet, ").append(l.shelfPhotos())
                    .append(l.shelfPhotos() == 1 ? " Regalfoto ist" : " Regalfotos sind")
                    .append(" verkleinert gespeichert (für die Inventarliste der Versicherung).</p></div></div>");
        }

        // ---- what is kept, why, where it goes, for how long
        h.append("<div class=\"section\"><h2>Was wir speichern und warum</h2><table class=\"rows why\">")
                .append(why("Konto", "Name, E-Mail-Adresse, Passwort als bcrypt-Hash, ggf. die Kennung des tstieh-Portals.",
                        "Damit du dich anmelden kannst und Bestätigungs-, Einladungs- und Passwort-Mails bekommst."))
                .append(why("Bibliotheken", "Bücher, Wunschliste, Regale, Mitgliedschaften und Rollen.",
                        "Das ist die App selbst: Bestand, Einsortieren, Wunschliste, Inventarliste."))
                .append(why("Fotos", "Fotos von Regalen und Büchern, Regalfotos verkleinert dauerhaft.",
                        "Beim Erfassen werden Fotos zur Erkennung der Buchrücken an Anthropic (Claude) übermittelt."))
                .append(why("Katalogabfragen", "ISBN, Titel und Autor eines Buchs.",
                        "Gehen an die Deutsche Nationalbibliothek, Google Books und Open Library, ohne Angaben zu dir."))
                .append(why("E-Mails", "Empfängeradresse und Inhalt der Mail.", "Versand über den Mailserver mx.volantic.de."))
                .append(why("Schutz vor Missbrauch", "IP-Adresse bei Anmeldeversuchen, nur im Arbeitsspeicher.",
                        "Begrenzt fehlgeschlagene Anmeldungen und Mails, nach höchstens einer Stunde vergessen."))
                .append("</table>");
        h.append("<h3>Wie lange</h3><p>Solange dein Konto besteht. Anmeldesitzungen gelten 30 Tage, Bestätigungslinks 2 Tage, "
                + "Links zum Zurücksetzen des Passworts 1 Stunde. Löschst du dein Konto, werden Bibliotheken, in denen du allein bist, "
                + "mit allen Büchern, Regalen und Fotos gelöscht.</p>");
        h.append("<h3>Deine Rechte</h3><p>Du kannst deine Daten in der App jederzeit berichtigen und dein Konto unter "
                + "<i>Konto › Konto löschen</i> löschen. Die Datei <span class=\"mono\">shelved-daten.json</span> im Anhang enthält "
                + "alles in maschinenlesbarer Form zum Mitnehmen (Art. 20 DSGVO). Du kannst der Verarbeitung widersprechen und dich "
                + "bei einer Datenschutz-Aufsichtsbehörde beschweren.</p></div>");
        h.append("</body></html>");
        return h.toString();
    }

    private static String css(String who) {
        return """
                @page { size: A4; margin: 20mm 18mm 22mm 18mm;
                  @bottom-left { content: "shelved · Datenauskunft für %s"; font-family: Jakarta; font-size: 7.5pt; color: #6c6c6c; }
                  @bottom-right { content: "Seite " counter(page) " von " counter(pages); font-family: Jakarta; font-size: 7.5pt; color: #6c6c6c; } }
                @page :first { @bottom-left { content: none; } @bottom-right { content: none; } }
                body { font-family: Jakarta; font-size: 9.5pt; line-height: 1.45; color: #161616; }
                .mark { font-size: 15pt; font-weight: 600; letter-spacing: -0.02em; }
                .mark span { color: #6c6c6c; }
                h1 { font-size: 46pt; font-weight: 600; letter-spacing: -0.02em; line-height: 1.02; margin: 52mm 0 5mm 0; }
                .lead { font-size: 11pt; color: #4d4d4d; margin: 0 0 9mm 0; width: 120mm; }
                .who { font-size: 11pt; margin: 0 0 10mm 0; color: #4d4d4d; }
                .who b { color: #161616; font-weight: 600; }
                table { border-collapse: collapse; width: 100%%; }
                .kpis td { border-top: 0.6pt solid #161616; padding: 3mm 4mm 0 0; vertical-align: top; width: 25%%; }
                .kpis b { display: block; font-size: 24pt; font-weight: 600; letter-spacing: -0.02em; }
                .kpis span { color: #6c6c6c; font-size: 9pt; }
                .strip { margin-top: 16mm; }
                .strip td { padding: 0 2.4mm 0 0; width: 14.28%%; }
                .strip img, .strip .typo { width: 22mm; height: 33mm; }
                .section { page-break-before: always; }
                .eyebrow { font-size: 8.5pt; font-weight: 600; color: #4d4d4d; margin: 0 0 1mm 0; }
                h2 { font-size: 20pt; font-weight: 600; letter-spacing: -0.015em; margin: 0 0 1mm 0; }
                h2.next { margin-top: 10mm; page-break-after: avoid; }
                h3 { font-size: 11.5pt; font-weight: 600; margin: 8mm 0 2mm 0; page-break-after: avoid; }
                .sub { color: #6c6c6c; font-size: 8.5pt; margin: 0 0 5mm 0; }
                .facts td { border-bottom: 0.4pt solid #e1e1e1; padding: 2.4mm 0; }
                .facts td.k { color: #6c6c6c; width: 45mm; }
                .rows td { border-bottom: 0.4pt solid #e1e1e1; padding: 2.4mm 3mm 2.4mm 0; vertical-align: top; }
                .rows tr.head td { color: #6c6c6c; font-size: 8pt; border-bottom: 0.6pt solid #161616; }
                .rows b { font-weight: 600; }
                .r { text-align: right; }
                .why td.k { width: 34mm; font-weight: 600; }
                .why td.d { color: #4d4d4d; }
                .mono { font-family: Mono; font-size: 0.86em; }
                .grid td { width: 20%%; vertical-align: top; padding: 0 3mm 6mm 0; }
                .grid tr { page-break-inside: avoid; }
                .keep { page-break-inside: avoid; }
                .grid img, .grid .typo { width: 31mm; height: 46.5mm; }
                img { border: 0.4pt solid #e1e1e1; }
                .typo { background-color: #4a5d6e; color: #ffffff; padding: 3mm; font-size: 8pt; font-weight: 600; line-height: 1.15; overflow: hidden; }
                .typo span { display: block; font-weight: 400; font-size: 7pt; margin-top: 2mm; }
                .t { font-weight: 600; font-size: 8.5pt; line-height: 1.25; margin-top: 2mm; }
                .a { color: #4d4d4d; font-size: 7.5pt; margin-top: 0.6mm; }
                .m { color: #6c6c6c; font-family: Mono; font-size: 6.5pt; margin-top: 0.6mm; }
                p { margin: 0 0 3mm 0; }
                """.formatted(cssString(esc(who)));
    }

    /** A quoted CSS string: no quotes or backslashes from user data may end it. */
    private static String cssString(String s) {
        return s.replace("\\", "").replace("\"", "'");
    }

    private static String kpi(int n, String label) {
        return "<td><b>" + n + "</b><span>" + esc(label) + "</span></td>";
    }

    private static String fact(String k, String v) {
        return "<tr><td class=\"k\">" + esc(k) + "</td><td>" + esc(v == null || v.isBlank() ? "–" : v) + "</td></tr>";
    }

    private static String why(String what, String data, String purpose) {
        return "<tr><td class=\"k\">" + esc(what) + "</td><td>" + esc(data) + "</td><td class=\"d\">" + esc(purpose) + "</td></tr>";
    }

    /** A heading never stays alone at the bottom of a page: it goes with the first row of its covers. */
    private static String keepWithFirstRow(String heading, List<String> cards) {
        if (cards.isEmpty()) {
            return "<div class=\"keep\">" + heading + "</div>";
        }
        return "<div class=\"keep\">" + heading + grid(cards.subList(0, Math.min(5, cards.size()))) + "</div>"
                + (cards.size() > 5 ? grid(cards.subList(5, cards.size())) : "");
    }

    /** Five covers to a row; a row never breaks across pages. */
    private static String grid(List<String> cards) {
        StringBuilder h = new StringBuilder("<table class=\"grid\">");
        for (int i = 0; i < cards.size(); i += 5) {
            h.append("<tr>");
            for (int j = i; j < i + 5; j++) {
                h.append("<td>").append(j < cards.size() ? cards.get(j) : "").append("</td>");
            }
            h.append("</tr>");
        }
        return h.append("</table>").toString();
    }

    private static String card(Function<String, Optional<Path>> cover, String file, String title, String author, List<String> meta) {
        return cover(cover, file, title, author)
                + "<div class=\"t\">" + esc(title) + "</div>"
                + (author.isEmpty() ? "" : "<div class=\"a\">" + esc(author) + "</div>")
                + meta.stream().map(m -> "<div class=\"m\">" + esc(m) + "</div>").collect(Collectors.joining());
    }

    /** The stored cover, scaled to 2:3; without one a typographic cover in the colour the app gives it. */
    private static String cover(Function<String, Optional<Path>> cover, String file, String title, String author) {
        Optional<String> img = file == null ? Optional.empty() : cover.apply(file).flatMap(ExportPdf::thumbnail);
        if (img.isPresent()) {
            return "<img src=\"data:image/jpeg;base64," + img.get() + "\"/>";
        }
        return "<div class=\"typo\" style=\"background-color: " + colorFor(title + author) + "\">" + esc(title)
                + (author.isEmpty() ? "" : "<span>" + esc(author) + "</span>") + "</div>";
    }

    static String colorFor(String text) {
        int h = 0;
        for (int cp : text.codePoints().toArray()) {
            h = h * 31 + cp;
        }
        return PALETTE[(int) (Integer.toUnsignedLong(h) % PALETTE.length)];
    }

    private static Optional<String> thumbnail(Path file) {
        try {
            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) {
                return Optional.empty();
            }
            // fill the 2:3 box, cut what sticks out (covers are rarely exactly 2:3)
            double scale = Math.max((double) COVER_W / src.getWidth(), (double) COVER_H / src.getHeight());
            int w = (int) Math.round(src.getWidth() * scale), hgt = (int) Math.round(src.getHeight() * scale);
            BufferedImage out = new BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, (COVER_W - w) / 2, (COVER_H - hgt) / 2, w, hgt, null);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.82f);
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
            return Optional.of(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String first(List<String> list) {
        return list == null || list.isEmpty() ? "" : list.getFirst();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Everything from users or catalogues goes through here: the document is XHTML. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\n' || c == '\t') {
                        b.appendCodePoint(c); // control characters are not allowed in XML
                    }
                }
            }
        });
        return b.toString();
    }
}
