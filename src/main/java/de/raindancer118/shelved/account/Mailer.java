package de.raindancer118.shelved.account;

import java.util.List;

public interface Mailer {

    record Attachment(String name, String contentType, byte[] bytes) {
    }

    /** {@code html} may be null for a plain-text mail; with HTML the logo goes along inline. */
    record Mail(String to, String subject, String text, String html, List<Attachment> attachments) {
        public static Mail of(String to, String subject, Letter letter) {
            return of(to, subject, letter, List.of());
        }

        public static Mail of(String to, String subject, Letter letter, List<Attachment> attachments) {
            return new Mail(to, subject, letter.text(), letter.html(), attachments);
        }
    }

    void send(Mail mail);
}
