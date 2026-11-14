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

    @Test
    void apiAnswers401WithoutLogin() throws Exception {
        mvc.perform(get("/api/books")).andExpect(status().isUnauthorized());
    }

    @Test
    void pagesRedirectToTheLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/authentik")));
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
