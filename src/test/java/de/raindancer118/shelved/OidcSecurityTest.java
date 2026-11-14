package de.raindancer118.shelved;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production mode: nothing without a login, and each user only sees their own books. */
@SpringBootTest(properties = {
        "shelved.auth-mode=oidc",
        "shelved.data-dir=target/test-data",
        "spring.security.oauth2.client.registration.authentik.client-id=test",
        "spring.security.oauth2.client.registration.authentik.client-secret=test",
        "spring.security.oauth2.client.registration.authentik.scope=openid",
        "spring.security.oauth2.client.registration.authentik.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.authentik.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.provider.authentik.authorization-uri=https://idp.test/authorize",
        "spring.security.oauth2.client.provider.authentik.token-uri=https://idp.test/token",
        "spring.security.oauth2.client.provider.authentik.jwk-set-uri=https://idp.test/jwks",
        "spring.security.oauth2.client.provider.authentik.user-info-uri=https://idp.test/userinfo",
        "spring.security.oauth2.client.provider.authentik.user-name-attribute=sub"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OidcSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void apiAnswers401WithoutLogin() throws Exception {
        mvc.perform(get("/api/books")).andExpect(status().isUnauthorized());
    }

    /** The app shell loads without login and shows its own login page with the portal as one option. */
    @Test
    void theAppShellIsPublicAndOffersThePortal() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/options")).andExpect(jsonPath("$.sso").value(true));
        mvc.perform(get("/oauth2/authorization/authentik")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("idp.test/authorize")));
    }

    /** Survives browser restarts (camera on Android) and server restarts (sessions live in Postgres). */
    @Test
    void loginSessionCookieIsPersistentAndStoredInTheDatabase() throws Exception {
        var res = mvc.perform(get("/oauth2/authorization/authentik")).andExpect(status().is3xxRedirection()).andReturn().getResponse();
        String cookie = res.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(cookie).contains("Max-Age=2592000").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isPositive();
    }

    @Test
    void healthStaysPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void usersAreSeparated() throws Exception {
        mvc.perform(get("/api/books").with(oidcLogin().idToken(t -> t.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/me").with(oidcLogin().idToken(t -> t.subject("bob").claim("given_name", "Bob"))))
                .andExpect(jsonPath("$.name").value("Bob"));
    }
}
