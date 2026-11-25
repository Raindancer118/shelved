package de.raindancer118.shelved.account;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    /** SMTP when spring.mail.host is set; otherwise mails only go to the log (local development). */
    @Bean
    Mailer mailer(ObjectProvider<JavaMailSender> sender, @Value("${shelved.mail-from:shelved <noreply@tstieh.de>}") String from) {
        JavaMailSender smtp = sender.getIfAvailable();
        if (smtp == null) {
            return mail -> log.info("Mail not sent (no SMTP configured) to {}: {}\n{}", mail.to(), mail.subject(), mail.text());
        }
        return mail -> {
            try {
                MimeMessage m = smtp.createMimeMessage();
                MimeMessageHelper h = new MimeMessageHelper(m, !mail.attachments().isEmpty(), StandardCharsets.UTF_8.name());
                h.setFrom(from);
                h.setTo(mail.to());
                h.setSubject(mail.subject());
                h.setText(mail.text(), false);
                for (Mailer.Attachment a : mail.attachments()) {
                    h.addAttachment(a.name(), new ByteArrayResource(a.bytes()), a.contentType());
                }
                smtp.send(m);
            } catch (Exception e) {
                // the caller decides; a lost confirmation mail must not look like success
                throw new IllegalStateException("E-Mail konnte nicht gesendet werden", e);
            }
        };
    }
}
