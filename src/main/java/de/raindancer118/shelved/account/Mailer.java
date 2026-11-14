package de.raindancer118.shelved.account;

public interface Mailer {

    record Mail(String to, String subject, String text, String attachmentName, byte[] attachment) {
        public static Mail of(String to, String subject, String text) {
            return new Mail(to, subject, text, null, null);
        }
    }

    void send(Mail mail);
}
