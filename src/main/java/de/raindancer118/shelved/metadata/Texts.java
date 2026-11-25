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
}
