package de.raindancer118.shelved.inventory;

import de.raindancer118.shelved.book.Book;
import de.raindancer118.shelved.book.BookFormat;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A4 list for the insurer: summary, one row per title with cover, then the shelf photos.
 * Built-in Helvetica (Cp1252) covers German, accents and the euro sign; other scripts would need an embedded font.
 */
public final class InventoryPdf {

    private static final Color INK = new Color(13, 13, 13);
    private static final Color MUTED = new Color(122, 122, 122);
    private static final Color LINE = new Color(230, 230, 228);
    private static final Color ACCENT = new Color(232, 64, 15);
    private static final Font H1 = new Font(Font.HELVETICA, 20, Font.BOLD, INK);
    private static final Font H2 = new Font(Font.HELVETICA, 13, Font.BOLD, INK);
    private static final Font TEXT = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font HEAD = new Font(Font.HELVETICA, 7.5f, Font.BOLD, MUTED);
    private static final Font BIG = new Font(Font.HELVETICA, 16, Font.BOLD, INK);

    private InventoryPdf() {
    }

    static String euros(long cents) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(cents / 100.0) + " €";
    }

    public static byte[] render(InventoryService.Inventory inv, Function<Book, Optional<Path>> cover,
                                Function<UUID, Optional<Path>> photo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 44, 50);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        String date = DateTimeFormatter.ofPattern("dd.MM.yyyy").format(inv.date());
        writer.setPageEvent(new Footer("Inventarliste Bücher · Stand " + date));
        doc.addTitle("Inventarliste Bücher " + date);
        doc.addCreator("shelved");
        doc.open();

        doc.add(new Paragraph("Inventarliste Bücher", H1));
        Paragraph sub = new Paragraph("für die Hausratversicherung · Stand " + date
                + (inv.ownerName() == null ? "" : " · Eigentümer: " + inv.ownerName()), SMALL);
        sub.setSpacingAfter(14);
        doc.add(sub);
        doc.add(summary(inv));

        Paragraph basis = new Paragraph(
                "Wert = Wiederbeschaffungswert, also der heutige Neupreis (Preisangabe der Deutschen Nationalbibliothek "
                        + "bzw. des Verlags). Bei Büchern ohne Preisangabe ist ein Wert geschätzt (Median vergleichbarer Bücher "
                        + "dieser Sammlung) und als „geschätzt\" markiert. Standort: R = Regal, B = Boden von oben, dann Position "
                        + "von links. Die Regalfotos am Ende zeigen die Bücher am Standort.", SMALL);
        basis.setSpacingBefore(8);
        basis.setSpacingAfter(12);
        doc.add(basis);

        doc.add(table(inv, cover));

        if (!inv.photos().isEmpty()) {
            doc.newPage();
            doc.add(new Paragraph("Regalfotos", H2));
            Paragraph note = new Paragraph("Aufgenommen beim Erfassen der Bücher mit shelved, neueste zuerst.", SMALL);
            note.setSpacingAfter(10);
            doc.add(note);
            for (InventoryService.Photo p : inv.photos()) {
                Optional<Image> img = photo.apply(p.scanId()).flatMap(InventoryPdf::image);
                if (img.isEmpty()) {
                    continue;
                }
                Image i = img.get();
                float maxW = doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin();
                float maxH = (doc.getPageSize().getHeight() - doc.topMargin() - doc.bottomMargin()) * 0.42f;
                i.scaleToFit(maxW, maxH);
                PdfPTable box = new PdfPTable(1);
                box.setWidthPercentage(100);
                box.setKeepTogether(true);
                PdfPCell c = new PdfPCell(i, false);
                c.setBorder(Rectangle.NO_BORDER);
                c.setPaddingBottom(4);
                box.addCell(c);
                PdfPCell caption = new PdfPCell(new Phrase(p.label(), TEXT));
                caption.setBorder(Rectangle.NO_BORDER);
                caption.setPaddingBottom(14);
                box.addCell(caption);
                doc.add(box);
            }
        }
        doc.close();
        return out.toByteArray();
    }

    private static PdfPTable summary(InventoryService.Inventory inv) {
        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        t.addCell(kpi(String.valueOf(inv.copies()), inv.copies() == inv.rows().size()
                ? "Bücher" : "Bücher (" + inv.rows().size() + " Titel)"));
        t.addCell(kpi(euros(inv.totalCents()), "Wiederbeschaffungswert gesamt"));
        t.addCell(kpi(euros(inv.estimatedCents()), inv.estimatedBooks() == 0 ? "davon geschätzt"
                : "davon geschätzt (" + inv.estimatedBooks() + " ohne Preisangabe)"));
        return t;
    }

    private static PdfPCell kpi(String value, String label) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(INK);
        c.setBorderWidth(1.2f);
        c.setPaddingTop(6);
        c.addElement(new Paragraph(value, BIG));
        c.addElement(new Paragraph(label, SMALL));
        return c;
    }

    private static PdfPTable table(InventoryService.Inventory inv, Function<Book, Optional<Path>> cover) {
        PdfPTable t = new PdfPTable(new float[]{4, 5, 30, 10, 4, 8, 9});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        Stream.of("Nr", "", "Titel · Autor · Verlag · ISBN", "Standort", "Anz.", "Neupreis", "Wert")
                .forEach(h -> {
                    PdfPCell c = cell(new Phrase(h, HEAD));
                    c.setBorderWidthBottom(1);
                    c.setBorderColorBottom(INK);
                    if (h.equals("Anz.") || h.equals("Neupreis") || h.equals("Wert")) {
                        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    }
                    t.addCell(c);
                });
        for (InventoryService.Row r : inv.rows()) {
            Book b = r.book();
            t.addCell(cell(new Phrase(String.valueOf(r.nr()), SMALL)));
            PdfPCell img = cover.apply(b).flatMap(InventoryPdf::image).map(i -> {
                i.scaleToFit(24, 36);
                PdfPCell c = new PdfPCell(i, false);
                return c;
            }).orElseGet(() -> new PdfPCell(new Phrase("")));
            style(img);
            t.addCell(img);

            Phrase what = new Phrase();
            what.add(new Chunk(b.getTitle(), BOLD));
            if (b.getSubtitle() != null) {
                what.add(new Chunk(" – " + b.getSubtitle(), TEXT));
            }
            what.add(Chunk.NEWLINE);
            what.add(new Chunk(String.join(", ", b.getAuthors()), TEXT));
            String meta = Stream.of(b.getPublisher(), b.getPublishedYear() == null ? null : b.getPublishedYear().toString(),
                            format(b.getFormat()), b.getIsbn13() == null ? null : "ISBN " + b.getIsbn13())
                    .filter(Objects::nonNull).collect(Collectors.joining(" · "));
            if (!meta.isEmpty()) {
                what.add(Chunk.NEWLINE);
                what.add(new Chunk(meta, SMALL));
            }
            t.addCell(cell(what));
            t.addCell(cell(new Phrase(r.location() == null ? "–" : r.location(), TEXT)));
            t.addCell(right(new Phrase(String.valueOf(r.copies()), TEXT)));
            t.addCell(right(r.estimated()
                    ? new Phrase("geschätzt\n" + euros(r.unitCents()), SMALL)
                    : new Phrase(euros(r.unitCents()), TEXT)));
            t.addCell(right(new Phrase(euros(r.valueCents()), r.estimated() ? SMALL : BOLD)));
        }
        PdfPCell label = cell(new Phrase("Summe Wiederbeschaffungswert", BOLD));
        label.setColspan(6);
        label.setBorderWidthTop(1);
        label.setBorderColorTop(INK);
        t.addCell(label);
        PdfPCell sum = right(new Phrase(euros(inv.totalCents()), BOLD));
        sum.setBorderWidthTop(1);
        sum.setBorderColorTop(INK);
        t.addCell(sum);
        return t;
    }

    private static String format(BookFormat f) {
        return f == null ? null : switch (f) {
            case HARDCOVER -> "Hardcover";
            case PAPERBACK -> "Taschenbuch";
            case OTHER -> null;
        };
    }

    private static PdfPCell cell(Phrase p) {
        PdfPCell c = new PdfPCell(p);
        style(c);
        return c;
    }

    private static PdfPCell right(Phrase p) {
        PdfPCell c = cell(p);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static void style(PdfPCell c) {
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setPaddingTop(5);
        c.setPaddingBottom(6);
        c.setVerticalAlignment(Element.ALIGN_TOP);
    }

    private static Optional<Image> image(Path p) {
        try {
            return Optional.of(Image.getInstance(p.toAbsolutePath().toString()));
        } catch (Exception e) {
            return Optional.empty(); // e.g. WebP covers, which PDF can't embed
        }
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String text;

        Footer(String text) {
            this.text = text;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Font f = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(text, f), document.left(), 28, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Seite " + writer.getPageNumber(), f),
                    document.right(), 28, 0);
            cb.setColorStroke(ACCENT);
            cb.setLineWidth(1.5f);
            cb.moveTo(document.left(), document.top() + 18);
            cb.lineTo(document.left() + 24, document.top() + 18);
            cb.stroke();
        }
    }
}
