package de.raindancer118.shelved.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.time.Duration;

/**
 * Spring Session writes its own cookie and ignores server.servlet.session.cookie.*, so it is set here.
 * Persistent (30 days): Android kills the browser while the camera app is open, a session-only cookie
 * would be gone when it comes back.
 */
@Configuration
public class SessionConfig {

    @Bean
    CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer s = new DefaultCookieSerializer();
        s.setCookieName("SESSION");
        s.setUseHttpOnlyCookie(true);
        s.setUseSecureCookie(secure);
        s.setSameSite("Lax");
        s.setCookieMaxAge((int) Duration.ofDays(30).toSeconds());
        return s;
    }
}
