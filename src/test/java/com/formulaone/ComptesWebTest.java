package com.formulaone;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@WebMvcTest(ComptesController.class)
@Import(SecurityConfig.class)
class ComptesWebTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("formulaone.users-file", () -> dir.resolve("users.properties").toString());
        r.add("spring.security.user.name", () -> "admin");
        r.add("spring.security.user.password", () -> "mdpServeur1");
    }

    @Autowired
    WebApplicationContext ctx;
    MockMvc mvc;

    @BeforeEach
    void init() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private static final String JSON_ALICE = "{\"login\":\"alice\",\"password\":\"motdepasse-1\",\"role\":\"USER\"}";

    @Test
    void sansAuthentification401() throws Exception {
        mvc.perform(get("/api/admin/comptes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void parcoursAdministrateur() throws Exception {
        var admin = httpBasic("admin", "mdpServeur1");
        mvc.perform(get("/api/me").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));

        // creation sans l'en-tete de controle : refusee
        mvc.perform(post("/api/admin/comptes").with(admin).contentType(MediaType.APPLICATION_JSON).content(JSON_ALICE))
           .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/comptes").with(admin).header("X-Requested-With", "formulaone")
                .contentType(MediaType.APPLICATION_JSON).content(JSON_ALICE)).andExpect(status().isCreated());
        // doublon
        mvc.perform(post("/api/admin/comptes").with(admin).header("X-Requested-With", "formulaone")
                .contentType(MediaType.APPLICATION_JSON).content(JSON_ALICE)).andExpect(status().isConflict());

        // alice se connecte, n'est pas admin, ne peut pas gerer les comptes
        var alice = httpBasic("alice", "motdepasse-1");
        mvc.perform(get("/api/me").with(alice)).andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(false));
        mvc.perform(get("/api/admin/comptes").with(alice)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/comptes/admin").with(alice).header("X-Requested-With", "formulaone"))
           .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/comptes").with(admin)).andExpect(status().isOk())
           .andExpect(jsonPath("$[1].login").value("alice"));
        mvc.perform(delete("/api/admin/comptes/alice").with(admin).header("X-Requested-With", "formulaone"))
           .andExpect(status().isOk());
        mvc.perform(get("/api/me").with(alice)).andExpect(status().isUnauthorized());
    }
}
