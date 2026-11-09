package com.formulaone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Connexion, deconnexion, expiration, frein aux essais, fermeture des sessions d'un compte modifie. */
@WebMvcTest(controllers = {ComptesController.class, AuthController.class})
@Import({SecurityConfig.class, LoginThrottle.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)   // compteurs d'essais remis a zero
class AuthWebTest {

    @TempDir
    static Path dossier;
    static int compteur;

    /** Les tests modifient les comptes (donc le fichier) : chaque contexte repart d'un fichier neuf. */
    static String nouveauFichier() {
        Path f = dossier.resolve("users-" + (compteur++) + ".properties");
        try {
            Files.writeString(f, "alice=" + new BCryptPasswordEncoder().encode("motdepasse-1") + ";USER;*\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return f.toString();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("formulaone.users-file", () -> nouveauFichier());
        r.add("spring.security.user.name", () -> "admin");
        r.add("spring.security.user.password", () -> "mdpServeur1");
    }

    @Autowired
    WebApplicationContext ctx;
    @Autowired
    ComptesService comptes;
    MockMvc mvc;

    @BeforeEach
    void init() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private org.springframework.test.web.servlet.ResultActions login(String login, String mdp) throws Exception {
        return mvc.perform(post("/api/login").header(TestAuth.ENTETE, TestAuth.VALEUR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"login\":\"" + login + "\",\"password\":\"" + mdp + "\"}"));
    }

    @Test
    void nonConnecte_api401_pageRedirigeVersLaConnexion() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/formules")).andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login.html"));
    }

    @Test
    void authentificationBasicNEstPlusAcceptee() throws Exception {
        mvc.perform(get("/api/me").with(httpBasic("admin", "mdpServeur1"))).andExpect(status().isUnauthorized());
    }

    @Test
    void connexionOuvreUneSessionDe30Minutes() throws Exception {
        MockHttpSession s = (MockHttpSession) login("alice", "motdepasse-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("alice")).andExpect(jsonPath("$.admin").value(false))
                .andReturn().getRequest().getSession();
        assertEquals(30 * 60, s.getMaxInactiveInterval());
        mvc.perform(get("/api/me").session(s)).andExpect(status().isOk());
    }

    @Test
    void mauvaisMotDePasseEtLoginInconnuDonnentLeMemeMessage() throws Exception {
        String a = login("alice", "mauvais-mdp-1").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String b = login("inconnu", "mauvais-mdp-1").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(a, b);
        login("alice", "").andExpect(status().isUnauthorized());
    }

    @Test
    void connexionSansEnteteDeControleRefusee() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"alice\",\"password\":\"motdepasse-1\"}")).andExpect(status().isForbidden());
    }

    @Test
    void deconnexionFermeLaSession() throws Exception {
        MockHttpSession s = TestAuth.connecter(mvc, "alice", "motdepasse-1");
        mvc.perform(post("/api/logout").session(s).header(TestAuth.ENTETE, TestAuth.VALEUR)).andExpect(status().isOk())
           .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertTrue(s.isInvalid());
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCoupeeQuandLeMotDePasseChange() throws Exception {
        MockHttpSession s = TestAuth.connecter(mvc, "alice", "motdepasse-1");
        mvc.perform(get("/api/me").session(s)).andExpect(status().isOk());
        comptes.changerMotDePasse("alice", "nouveau-mdp-123");
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized());
        TestAuth.connecter(mvc, "alice", "nouveau-mdp-123");
    }

    @Test
    void sessionCoupeeQuandLeRoleChangeOuQueLeCompteEstSupprime() throws Exception {
        MockHttpSession s = TestAuth.connecter(mvc, "alice", "motdepasse-1");
        comptes.changerRole("alice", "ADMIN");
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized());

        MockHttpSession s2 = TestAuth.connecter(mvc, "alice", "motdepasse-1");
        mvc.perform(get("/api/admin/comptes").session(s2)).andExpect(status().isOk());
        comptes.supprimer("alice", "admin");
        mvc.perform(get("/api/me").session(s2)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionDePlusDe12HeuresCoupee() throws Exception {
        MockHttpSession s = TestAuth.connecter(mvc, "alice", "motdepasse-1");
        s.setAttribute(AuthController.SESSION_DEBUT, System.currentTimeMillis() - 13L * 3600 * 1000);
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized());
    }

    @Test
    void identifiantSessionChangeALaConnexion() throws Exception {
        MockHttpSession avant = new MockHttpSession();
        String idAvant = avant.getId();
        MockHttpSession apres = (MockHttpSession) mvc.perform(post("/api/login").session(avant)
                .header(TestAuth.ENTETE, TestAuth.VALEUR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"alice\",\"password\":\"motdepasse-1\"}")).andExpect(status().isOk())
                .andReturn().getRequest().getSession();
        assertTrue(!idAvant.equals(apres.getId()), "contre la fixation de session");
    }

    @Test
    void tropDEchecsBloquentMemeAvecLeBonMotDePasse() throws Exception {
        for (int i = 0; i < LoginThrottle.ECHECS_MAX_IP; i++) {
            login("alice", "mauvais-mdp-" + i).andExpect(status().isUnauthorized());
        }
        login("alice", "motdepasse-1").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}
