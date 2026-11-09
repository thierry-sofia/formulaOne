package com.formulaone;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Les restrictions de sources sont appliquees cote serveur : liste, recherche, detail, CSV. */
@WebMvcTest(controllers = {FormuleController.class, AuthController.class})
@Import({SecurityConfig.class, LoginThrottle.class})
class FormuleSourcesWebTest {

    static Path fichier;

    @BeforeAll
    static void creerFichier(@TempDir Path dir) throws Exception {
        // @TempDir statique : le dossier existe pour toute la classe
        fichier = dir.resolve("users.properties");
        String h = new BCryptPasswordEncoder().encode("motdepasse-1");
        Files.writeString(fichier, "alice=" + h + ";USER;BIS\nbob=" + h + ";USER;*\n");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("formulaone.users-file", () -> fichier.toString());
        r.add("spring.security.user.name", () -> "admin");
        r.add("spring.security.user.password", () -> "mdpServeur1");
    }

    @MockBean
    FormuleRepository repo;
    @MockBean
    CasService casService;
    @Autowired
    WebApplicationContext ctx;
    MockMvc mvc;

    @BeforeEach
    void init() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        when(repo.findByKey("K1")).thenReturn(List.of(Map.of("SOURCE", "BIS", "FORMULE_CD", "A", "FORMULE_LIB", "x")));
        when(repo.findByKey("K2")).thenReturn(List.of(Map.of("SOURCE", "CONCEPT", "FORMULE_CD", "B", "FORMULE_LIB", "y")));
        when(repo.lines(anyString())).thenReturn(List.of());
        when(repo.sumPoids(anyString())).thenReturn(java.math.BigDecimal.ZERO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rechercheRestreinteAuxSourcesDuCompte() throws Exception {
        mvc.perform(get("/api/formules?q=a&source=CONCEPT").session(TestAuth.connecter(mvc, "alice", "motdepasse-1"))).andExpect(status().isOk());

        ArgumentCaptor<java.util.Collection<String>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repo).search(eq("a"), eq("CONCEPT"), anyInt(), captor.capture(), anyString(), anyBoolean(), anyInt(), anyInt());
        org.junit.jupiter.api.Assertions.assertEquals(Set.of("BIS"), Set.copyOf(captor.getValue()),
                "meme si le client demande une autre source, le filtre du compte reste applique");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compteSansRestrictionEtAdministrateurVoientTout() throws Exception {
        mvc.perform(get("/api/formules").session(TestAuth.connecter(mvc, "bob", "motdepasse-1"))).andExpect(status().isOk());
        mvc.perform(get("/api/formules").session(TestAuth.connecter(mvc, "admin", "mdpServeur1"))).andExpect(status().isOk());
        ArgumentCaptor<java.util.Collection<String>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repo, org.mockito.Mockito.times(2)).search(anyString(), anyString(), anyInt(), captor.capture(), anyString(), anyBoolean(), anyInt(), anyInt());
        captor.getAllValues().forEach(v -> org.junit.jupiter.api.Assertions.assertNull(v));
    }

    @Test
    void detailEtCsvDUneSourceInterditeSont404() throws Exception {
        var alice = TestAuth.connecter(mvc, "alice", "motdepasse-1");
        mvc.perform(get("/api/formules/K1").session(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/formules/K2").session(alice)).andExpect(status().isNotFound());
        mvc.perform(get("/api/formules/K1/csv").session(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/formules/K2/csv").session(alice)).andExpect(status().isNotFound());
        // un compte sans restriction voit K2
        mvc.perform(get("/api/formules/K2").session(TestAuth.connecter(mvc, "bob", "motdepasse-1"))).andExpect(status().isOk());
    }

    @Test
    void listeDesSourcesRestreinte() throws Exception {
        when(repo.sources(any())).thenReturn(List.of("BIS"));
        mvc.perform(get("/api/formules/sources").session(TestAuth.connecter(mvc, "alice", "motdepasse-1"))).andExpect(status().isOk());
        verify(repo).sources(Set.of("BIS"));
    }

    @Test
    void csvContientLeCasQuandIlExisteEtResteVideSinon() throws Exception {
        when(repo.lines("K1")).thenReturn(List.of(
                Map.of("MATIERE_CD", "100750", "MATIERE_LIB", "CIS 3 HEXENOL", "FORMULE_POIDS", new java.math.BigDecimal("5")),
                Map.of("MATIERE_CD", "ZZ", "MATIERE_LIB", "INCONNUE", "FORMULE_POIDS", new java.math.BigDecimal("2"))));
        when(casService.cas("100750")).thenReturn("928-96-1");
        when(casService.cas("ZZ")).thenReturn(null);

        String csv = mvc.perform(get("/api/formules/K1/csv").session(TestAuth.connecter(mvc, "alice", "motdepasse-1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("Code matière;Matière;CAS;Poids"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("\"100750\";\"CIS 3 HEXENOL\";\"928-96-1\";5"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("\"ZZ\";\"INCONNUE\";\"\";2"), csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains(";;TOTAL;;"), csv);
    }
}
