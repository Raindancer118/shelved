package de.raindancer118.shelved.metadata;

import org.springframework.web.util.HtmlUtils;

/** Blurbs arrive as HTML (DNB Inhaltstext, Google): line breaks kept, tags dropped, every entity decoded ("&#160;"). */
public final class Texts {

    private Texts() {
    }

    public static String plain(String html) {
        if (html == null) {
            return null;
        }
        String text = html.replaceAll("(?s)<head>.*?</head>", "")
                .replaceAll("(?i)<br\\s*/?>|</p>", "\n")
                .replaceAll("<[^>]+>", "");
        return HtmlUtils.htmlUnescape(text).trim();
    }

    /**
     * A blurb fit to show, or null. A whole web page (menus, cart, categories) sometimes arrives instead of a blurb:
     * it is long and made of many short lines, which a blurb never is.
     */
    public static String blurb(String html) {
        String text = plain(html);
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        long lines = text.lines().filter(l -> !l.isBlank()).count();
        long shortLines = text.lines().filter(l -> !l.isBlank() && l.trim().length() < 40).count();
        boolean page = text.length() > 6000 || lines > 20 && shortLines > lines * 0.6;
        return page ? null : text;
    }
}
