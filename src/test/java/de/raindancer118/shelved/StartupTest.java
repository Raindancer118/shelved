package de.raindancer118.shelved;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/** The app boots against a real Postgres, migrations run, health and the frontend are served. */
class StartupTest extends IntegrationTestBase {

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void servesTheAppWithSecurityHeaders() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>shelved</title>")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=(self)")));
    }

    @Test
    void staticFilesAreRevalidatedSoDeploysReachEveryone() throws Exception {
        mvc.perform(get("/js/app.js")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
    }

    /** Same dance the browser does: fetch the token cookie once, echo it back as header. */
    @Test
    void browserCsrfFlowWorks() throws Exception {
        var cookie = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        org.assertj.core.api.Assertions.assertThat(cookie).isNotNull();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/shelves")
                        .cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"boardWidthsMm\":[800]}"))
                .andExpect(status().isCreated());
    }
}
