package de.raindancer118.shelved.account;

import java.util.List;

public interface Mailer {

    record Attachment(String name, String contentType, byte[] bytes) {
    }

    record Mail(String to, String subject, String text, List<Attachment> attachments) {
        public static Mail of(String to, String subject, String text) {
            return new Mail(to, subject, text, List.of());
        }
    }

    void send(Mail mail);
}
