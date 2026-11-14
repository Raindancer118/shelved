package de.raindancer118.shelved;

import de.raindancer118.shelved.account.Mailer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catches outgoing mail in tests. */
public class FakeMailer implements Mailer {

    public final List<Mail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(Mail mail) {
        sent.add(mail);
    }

    public Mail last(String to) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(to)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("No mail to " + to + " in " + sent));
    }

    /** The token from the link in the last mail to this address. */
    public String token(String to) {
        Matcher m = Pattern.compile("[?&/]token=([A-Za-z0-9_-]+)").matcher(last(to).text());
        if (!m.find()) {
            throw new AssertionError("No token link in " + last(to).text());
        }
        return m.group(1);
    }
}
