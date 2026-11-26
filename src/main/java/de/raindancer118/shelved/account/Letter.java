package de.raindancer118.shelved.account;

import org.springframework.web.util.HtmlUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One mail from shelved, written once and sent twice: as HTML in the app's look (white sheet, hairline, one dark
 * button, the logo) and as plain text for clients that show no HTML. Tables and inline styles, because that is all
 * Outlook and Gmail reliably render; Apple Mail additionally gets a dark variant.
 */
public final class Letter {

    public static final String LOGO_CID = "shelved-logo";

    public record Fact(String value, String label) {
    }

    private final String headline;
    private String preheader;
    private String greeting;
    /** Paragraphs (String) and rows of numbers (List of Fact), in the order they were added. */
    private final List<Object> blocks = new ArrayList<>();
    private String actionLabel;
    private String actionUrl;
    private String note;

    private Letter(String headline) {
        this.headline = headline;
    }

    public static Letter titled(String headline) {
        return new Letter(headline);
    }

    /** The grey line mail apps show next to the subject. */
    public Letter preheader(String s) { preheader = s; return this; }
    public Letter greeting(String s) { greeting = s; return this; }
    public Letter paragraph(String s) { blocks.add(s); return this; }
    public Letter action(String label, String url) { actionLabel = label; actionUrl = url; return this; }
    public Letter facts(List<Fact> f) { blocks.add(List.copyOf(f)); return this; }
    /** Small print under a hairline: how long a link lasts, what to do if it wasn't you. */
    public Letter note(String s) { note = s; return this; }

    public String text() {
        StringBuilder t = new StringBuilder();
        if (greeting != null) {
            t.append(greeting).append("\n\n");
        }
        for (Object block : blocks) {
            if (block instanceof String p) {
                t.append(p).append("\n\n");
            } else {
                facts(block).forEach(f -> t.append(f.value()).append(' ').append(f.label()).append('\n'));
                t.append('\n');
            }
        }
        if (actionUrl != null) {
            t.append(actionLabel).append(":\n").append(actionUrl).append("\n\n");
        }
        if (note != null) {
            t.append(note).append("\n\n");
        }
        return t.append("shelved").toString();
    }

    private static final String SANS = "'Plus Jakarta Sans',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO = "'JetBrains Mono',ui-monospace,Menlo,Consolas,monospace";

    public String html() {
        StringBuilder b = new StringBuilder();
        b.append("""
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light dark"><meta name="supported-color-schemes" content="light dark">
                <title>%s</title>
                <style>
                  @media (prefers-color-scheme: dark) {
                    .bg { background:#161616 !important; } .sheet { background:#202020 !important; border-color:#393939 !important; }
                    .t1 { color:#f2f2f2 !important; } .t2 { color:#b7b7b7 !important; } .t3 { color:#898989 !important; }
                    .hair { border-color:#393939 !important; } .btn { background:#c3c7ce !important; } .btn a { color:#101214 !important; }
                  }
                  @media (max-width:560px) { .sheet-pad { padding:24px 20px !important; } }
                </style></head>
                """.formatted(esc(headline)));
        b.append("<body class=\"bg\" style=\"margin:0;padding:0;background:#f0f0f0;\">");
        if (preheader != null) {
            b.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">").append(esc(preheader))
                    .append("&#8203;&nbsp;".repeat(40)).append("</div>");
        }
        b.append("<table role=\"presentation\" class=\"bg\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f0f0f0;\"><tr><td align=\"center\" style=\"padding:32px 12px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:520px;\">");
        // the mark and the name, outside the sheet like a letterhead
        b.append("<tr><td style=\"padding:0 4px 16px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                .append("<td style=\"vertical-align:middle;\"><img src=\"cid:").append(LOGO_CID)
                .append("\" width=\"28\" height=\"28\" alt=\"\" style=\"display:block;border:0;border-radius:6px;\"></td>")
                .append("<td class=\"t1\" style=\"vertical-align:middle;padding-left:10px;font:600 17px/1 ").append(SANS)
                .append(";color:#161616;letter-spacing:-0.01em;\">shelved</td></tr></table></td></tr>");
        b.append("<tr><td class=\"sheet sheet-pad\" style=\"background:#ffffff;border:1px solid #e1e1e1;border-radius:6px;padding:32px;\">");
        b.append("<h1 class=\"t1\" style=\"margin:0 0 16px;font:600 22px/1.25 ").append(SANS).append(";color:#161616;letter-spacing:-0.01em;\">")
                .append(esc(headline)).append("</h1>");
        if (greeting != null) {
            b.append(p(greeting));
        }
        for (Object block : blocks) {
            if (block instanceof String par) {
                b.append(p(par));
                continue;
            }
            List<Fact> facts = facts(block);
            b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 20px;table-layout:fixed;\"><tr>");
            for (int i = 0; i < facts.size(); i++) {
                Fact f = facts.get(i);
                b.append("<td class=\"hair\" width=\"").append(100 / facts.size()).append("%\" style=\"padding:12px ").append(i == 0 ? "16px 12px 0" : "16px")
                        .append(";border-top:1px solid #e1e1e1;").append(i > 0 ? "border-left:1px solid #e1e1e1;" : "").append("\">")
                        .append("<div class=\"t1\" style=\"font:600 28px/1.1 ").append(SANS)
                        .append(";color:#161616;letter-spacing:-0.02em;font-variant-numeric:tabular-nums;\">").append(esc(f.value())).append("</div>")
                        .append("<div class=\"t3\" style=\"font:400 13px/1.4 ").append(SANS).append(";color:#6c6c6c;margin-top:4px;\">")
                        .append(esc(f.label())).append("</div></td>");
            }
            b.append("</tr></table>");
        }
        if (actionUrl != null) {
            String href = esc(actionUrl);
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 16px;\"><tr>")
                    .append("<td class=\"btn\" style=\"background:#181b1f;border-radius:6px;\">")
                    .append("<a href=\"").append(href).append("\" style=\"display:inline-block;padding:12px 22px;font:600 15px/1.2 ")
                    .append(SANS).append(";color:#ffffff;text-decoration:none;border-radius:6px;\">").append(esc(actionLabel))
                    .append("</a></td></tr></table>")
                    .append("<p class=\"t3\" style=\"margin:0 0 4px;font:400 12px/1.5 ").append(SANS)
                    .append(";color:#6c6c6c;\">Falls der Knopf nicht geht, öffne diesen Link:</p>")
                    .append("<p style=\"margin:0 0 16px;font:500 12px/1.5 ").append(MONO)
                    .append(";word-break:break-all;\"><a class=\"t2\" href=\"").append(href).append("\" style=\"color:#4d4d4d;\">")
                    .append(href).append("</a></p>");
        }
        if (note != null) {
            b.append("<p class=\"t3 hair\" style=\"margin:16px 0 0;padding-top:16px;border-top:1px solid #e1e1e1;font:400 13px/1.5 ")
                    .append(SANS).append(";color:#6c6c6c;\">").append(esc(note)).append("</p>");
        }
        b.append("</td></tr>");
        b.append("<tr><td class=\"t3\" style=\"padding:16px 4px 0;font:400 12px/1.5 ").append(SANS)
                .append(";color:#6c6c6c;\">Diese Mail kommt von shelved, weil diese Adresse dort ein Konto hat oder eingeladen wurde.</td></tr>");
        b.append("</table></td></tr></table></body></html>");
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Fact> facts(Object block) {
        return (List<Fact>) block;
    }

    private static String p(String s) {
        return "<p class=\"t2\" style=\"margin:0 0 16px;font:400 15px/1.55 " + SANS + ";color:#4d4d4d;\">" + esc(s) + "</p>";
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static byte[] logo;

    /** favicon.svg drawn again: three spines on a board, the third one orange and leaning. SVG doesn't show in mail. */
    public static synchronized byte[] logoPng() {
        if (logo == null) {
            int s = 112;
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(s / 64.0, s / 64.0);
            g.setColor(new Color(0x0d0d0d));
            g.fill(new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28));
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(14, 16, 9, 32, 3, 3));
            g.fill(new RoundRectangle2D.Double(25, 16, 9, 32, 3, 3));
            g.fill(new RoundRectangle2D.Double(10, 50, 44, 3, 3, 3));
            AffineTransform before = g.getTransform();
            g.rotate(Math.toRadians(-14), 41.5, 48);
            g.setColor(new Color(0xff4f1f));
            g.fill(new RoundRectangle2D.Double(37, 18, 9, 31, 3, 3));
            g.setTransform(before);
            g.dispose();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                logo = out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return logo;
    }
}
