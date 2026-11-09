package com.formulaone;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

final class TestAuth {

    private TestAuth() { }

    static final String ENTETE = "X-Requested-With";
    static final String VALEUR = "formulaone";

    /** Ouvre une session comme le fait la page de connexion et renvoie la session obtenue. */
    static MockHttpSession connecter(MockMvc mvc, String login, String mdp) throws Exception {
        MvcResult r = mvc.perform(post("/api/login").header(ENTETE, VALEUR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + mdp + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) r.getRequest().getSession();
    }
}
