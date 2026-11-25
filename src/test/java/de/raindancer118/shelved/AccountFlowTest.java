package de.raindancer118.shelved;

import de.raindancer118.shelved.account.Mailer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Own accounts: register, confirm, log in, reset, invite, roles, delete, data export. */
@SpringBootTest(properties = {"shelved.auth-mode=accounts", "shelved.data-dir=target/test-data",
        "shelved.public-url=https://shelf.test", "shelved.mails-per-hour=1000"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, AccountFlowTest.MailConfig.class})
@DirtiesContext
class AccountFlowTest {

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        FakeMailer fakeMailer() {
            return new FakeMailer();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeMailer mail;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        mail.sent.clear();
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body, Cookie session) throws Exception {
        if (session != null) {
            req.cookie(session);
        }
        return mvc.perform(req.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void register(String email, String password, String library) throws Exception {
        json(post("/api/auth/register"), """
                {"email":"%s","name":"Test","password":"%s","libraryName":"%s"}""".formatted(email, password, library), null)
                .andExpect(status().isCreated());
        json(post("/api/auth/verify"), "{\"token\":\"" + mail.token(email) + "\"}", null).andExpect(status().isOk());
    }

    private Cookie login(String email, String password) throws Exception {
        return json(post("/api/auth/login"), """
                {"email":"%s","password":"%s"}""".formatted(email, password), null)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }

    @Test
    void registerConfirmAndLogIn() throws Exception {
        json(post("/api/auth/register"), """
                {"email":"Ada@Example.org","name":"Ada","password":"lang-genug-123","libraryName":"Schulbücherei Nord"}""", null)
                .andExpect(status().isCreated());
        assertThat(mail.last("ada@example.org").text()).contains("https://shelf.test/#/verify?token=");

        // not confirmed yet
        json(post("/api/auth/login"), "{\"email\":\"ada@example.org\",\"password\":\"lang-genug-123\"}", null)
                .andExpect(status().isForbidden());
        json(post("/api/auth/verify"), "{\"token\":\"" + mail.token("ada@example.org") + "\"}", null).andExpect(status().isOk());
        // links work once
        json(post("/api/auth/verify"), "{\"token\":\"" + mail.token("ada@example.org") + "\"}", null).andExpect(status().isBadRequest());

        Cookie s = login("ADA@example.org", "lang-genug-123");
        mvc.perform(get("/api/me").cookie(s))
                .andExpect(jsonPath("$.name").value("Ada"))
                .andExpect(jsonPath("$.email").value("ada@example.org"))
                .andExpect(jsonPath("$.library.name").value("Schulbücherei Nord"))
                .andExpect(jsonPath("$.library.role").value("OWNER"));
        mvc.perform(get("/api/books").cookie(s)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/books")).andExpect(status().isUnauthorized());
        // stored as bcrypt, never in plain text
        assertThat(jdbc.queryForObject("select password_hash from account where email='ada@example.org'", String.class)).startsWith("$2");
    }

    @Test
    void shortPasswordsAndDoubleAddressesAreRefused() throws Exception {
        json(post("/api/auth/register"), "{\"email\":\"kurz@example.org\",\"name\":\"K\",\"password\":\"kurz\",\"libraryName\":\"X\"}", null)
                .andExpect(status().isBadRequest());
        register("doppelt@example.org", "lang-genug-123", "A");
        // no hint whether the address exists: same answer, but a mail to the owner
        json(post("/api/auth/register"), "{\"email\":\"doppelt@example.org\",\"name\":\"D\",\"password\":\"anders-lang-123\",\"libraryName\":\"B\"}", null)
                .andExpect(status().isCreated());
        assertThat(mail.last("doppelt@example.org").subject()).contains("bereits");
    }

    @Test
    void wrongPasswordsAreLimited() throws Exception {
        register("brute@example.org", "lang-genug-123", "Brute");
        for (int i = 0; i < 5; i++) {
            json(post("/api/auth/login"), "{\"email\":\"brute@example.org\",\"password\":\"falsch-falsch\"}", null)
                    .andExpect(status().isUnauthorized());
        }
        json(post("/api/auth/login"), "{\"email\":\"brute@example.org\",\"password\":\"lang-genug-123\"}", null)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void forgottenPasswordIsResetOnceByMail() throws Exception {
        register("vergesslich@example.org", "altes-passwort-1", "V");
        json(post("/api/auth/forgot"), "{\"email\":\"vergesslich@example.org\"}", null).andExpect(status().isNoContent());
        json(post("/api/auth/forgot"), "{\"email\":\"unbekannt@example.org\"}", null).andExpect(status().isNoContent());
        String token = mail.token("vergesslich@example.org");
        json(post("/api/auth/reset"), "{\"token\":\"" + token + "\",\"password\":\"neues-passwort-2\"}", null).andExpect(status().isOk());
        json(post("/api/auth/reset"), "{\"token\":\"" + token + "\",\"password\":\"noch-eins-3333\"}", null).andExpect(status().isBadRequest());
        json(post("/api/auth/login"), "{\"email\":\"vergesslich@example.org\",\"password\":\"altes-passwort-1\"}", null)
                .andExpect(status().isUnauthorized());
        login("vergesslich@example.org", "neues-passwort-2");
    }

    @Test
    void librariesAreSeparated() throws Exception {
        register("eins@example.org", "lang-genug-123", "Eins");
        register("zwei@example.org", "lang-genug-123", "Zwei");
        Cookie eins = login("eins@example.org", "lang-genug-123");
        json(post("/api/books"), "{\"title\":\"Nur bei Eins\",\"authors\":[\"A\"]}", eins).andExpect(status().isCreated());
        mvc.perform(get("/api/books").cookie(login("zwei@example.org", "lang-genug-123"))).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void invitedStaffWorksInTheSameLibraryAndViewersOnlyRead() throws Exception {
        register("leitung@example.org", "lang-genug-123", "Gemeindebücherei");
        Cookie leitung = login("leitung@example.org", "lang-genug-123");
        json(post("/api/books"), "{\"title\":\"Bestandsbuch\",\"authors\":[\"A\"]}", leitung).andExpect(status().isCreated());

        json(post("/api/library/invitations"), "{\"email\":\"helfer@example.org\",\"role\":\"STAFF\"}", leitung).andExpect(status().isCreated());
        String invite = mail.token("helfer@example.org");
        mvc.perform(get("/api/invitations/" + invite)).andExpect(jsonPath("$.library").value("Gemeindebücherei"));
        // a new person registers through the invitation: no own library, address proven by the link
        json(post("/api/auth/register"), """
                {"email":"helfer@example.org","name":"Helfer","password":"lang-genug-123","inviteToken":"%s"}""".formatted(invite), null)
                .andExpect(status().isCreated());
        Cookie helfer = login("helfer@example.org", "lang-genug-123");
        mvc.perform(get("/api/me").cookie(helfer)).andExpect(jsonPath("$.library.role").value("STAFF"));
        mvc.perform(get("/api/books").cookie(helfer)).andExpect(jsonPath("$[0].title").value("Bestandsbuch"));
        json(post("/api/books"), "{\"title\":\"Von Helfer\",\"authors\":[\"B\"]}", helfer).andExpect(status().isCreated());
        // staff can't manage members
        json(post("/api/library/invitations"), "{\"email\":\"x@example.org\",\"role\":\"OWNER\"}", helfer).andExpect(status().isForbidden());

        json(post("/api/library/invitations"), "{\"email\":\"gast@example.org\",\"role\":\"VIEWER\"}", leitung).andExpect(status().isCreated());
        register("gast@example.org", "lang-genug-123", "Gasts eigene");
        Cookie gast = login("gast@example.org", "lang-genug-123");
        json(post("/api/invitations/" + mail.sent.stream().filter(m -> m.to().equals("gast@example.org") && m.text().contains("invite"))
                .reduce((a, b) -> b).map(m -> { var mt = java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(m.text()); mt.find(); return mt.group(1); }).orElseThrow()
                + "/accept"), "{}", gast).andExpect(status().isOk());
        String libs = mvc.perform(get("/api/libraries").cookie(gast)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String gemeinde = JsonPath.read(libs, "$[?(@.name=='Gemeindebücherei')].id").toString().replaceAll("[\\[\\]\"]", "");
        json(post("/api/libraries/" + gemeinde + "/select"), "{}", gast).andExpect(status().isOk());
        mvc.perform(get("/api/books").cookie(gast)).andExpect(status().isOk());
        json(post("/api/books"), "{\"title\":\"Darf nicht\",\"authors\":[\"C\"]}", gast).andExpect(status().isForbidden());

        // the owner sees the team and can change roles
        String members = mvc.perform(get("/api/library/members").cookie(leitung)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(members).contains("helfer@example.org", "gast@example.org");
        String helferId = JsonPath.read(members, "$[?(@.email=='helfer@example.org')].accountId").toString().replaceAll("[\\[\\]\"]", "");
        json(patch("/api/library/members/" + helferId), "{\"role\":\"ADMIN\"}", leitung).andExpect(status().isOk());
    }

    @Test
    void deletingTheAccountDeletesItsOwnLibraryButNotASharedOne() throws Exception {
        register("weg@example.org", "lang-genug-123", "Weg");
        Cookie weg = login("weg@example.org", "lang-genug-123");
        json(post("/api/books"), "{\"title\":\"Verschwindet\",\"authors\":[\"A\"]}", weg).andExpect(status().isCreated());
        json(post("/api/library/invitations"), "{\"email\":\"bleibt@example.org\",\"role\":\"STAFF\"}", weg).andExpect(status().isCreated());
        json(post("/api/auth/register"), """
                {"email":"bleibt@example.org","name":"Bleibt","password":"lang-genug-123","inviteToken":"%s"}""".formatted(mail.token("bleibt@example.org")), null)
                .andExpect(status().isCreated());
        // the only owner of a library others use has to hand it over first
        json(delete("/api/account"), "{\"password\":\"lang-genug-123\"}", weg).andExpect(status().isConflict());

        register("allein@example.org", "lang-genug-123", "Allein");
        Cookie allein = login("allein@example.org", "lang-genug-123");
        json(post("/api/books"), "{\"title\":\"Allein\",\"authors\":[\"A\"]}", allein).andExpect(status().isCreated());
        json(post("/api/wishes"), "{\"title\":\"Gewünscht\",\"authors\":[\"B\"]}", allein).andExpect(status().isCreated());
        json(delete("/api/account"), "{\"password\":\"falsch-falsch\"}", allein).andExpect(status().isForbidden());
        json(delete("/api/account"), "{\"password\":\"lang-genug-123\"}", allein).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from account where email='allein@example.org'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from book where title='Allein'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wish where title='Gewünscht'", Integer.class)).isZero();
        json(post("/api/auth/login"), "{\"email\":\"allein@example.org\",\"password\":\"lang-genug-123\"}", null)
                .andExpect(status().isUnauthorized());
    }

    /** Art. 15 GDPR: everything about the person, as a readable PDF and as machine-readable JSON (Art. 20). */
    @Test
    void dataExportArrivesByMailAsPdfAndJsonWithAllBooks() throws Exception {
        register("auskunft@example.org", "lang-genug-123", "Auskunft");
        Cookie s = login("auskunft@example.org", "lang-genug-123");
        json(post("/api/books"), "{\"title\":\"Momo\",\"authors\":[\"Michael Ende\"]}", s).andExpect(status().isCreated());
        json(post("/api/wishes"), "{\"title\":\"Die unendliche Geschichte\",\"authors\":[\"Michael Ende\"]}", s).andExpect(status().isCreated());

        json(post("/api/account/export"), "{}", s).andExpect(status().isAccepted());

        // the PDF is rendered in the background; the registration mail came before
        var m = await().until(() -> mail.lastOrNull("auskunft@example.org"), x -> x != null && x.subject().contains("Daten"));
        assertThat(m.subject()).contains("Daten");
        assertThat(m.attachments()).extracting(Mailer.Attachment::name).containsExactly("shelved-daten.pdf", "shelved-daten.json");

        byte[] pdf = m.attachments().get(0).bytes();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " "); // titles wrap in the cards
            assertThat(text).contains("Deine Daten", "auskunft@example.org", "Momo", "Die unendliche Geschichte", "Eigentümer");
        }
        assertThat(new String(m.attachments().get(1).bytes(), StandardCharsets.UTF_8))
                .contains("auskunft@example.org", "Auskunft", "OWNER", "Momo", "Die unendliche Geschichte");
    }
}
