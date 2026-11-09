package com.formulaone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    /** Horloge reglable. */
    static class Horloge extends Clock {
        Instant maintenant = Instant.parse("2026-11-09T12:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return maintenant; }
    }

    private final Horloge h = new Horloge();
    private final LoginThrottle t = new LoginThrottle(h);

    @Test
    void bloqueApresTropDEchecsDepuisUneAdresse() {
        for (int i = 0; i < LoginThrottle.ECHECS_MAX_IP - 1; i++) {
            t.echec("1.2.3.4", "login" + i);          // logins differents : c'est l'adresse qui compte
        }
        assertEquals(0, t.secondesDeBlocage("1.2.3.4", "autre"));
        t.echec("1.2.3.4", "dernier");
        assertTrue(t.secondesDeBlocage("1.2.3.4", "autre") > 0);
        assertEquals(0, t.secondesDeBlocage("5.6.7.8", "autre"), "les autres adresses ne sont pas touchees");
    }

    @Test
    void bloqueApresTropDEchecsSurUnLoginDepuisDesAdressesDifferentes() {
        for (int i = 0; i < LoginThrottle.ECHECS_MAX_LOGIN; i++) {
            t.echec("10.0.0." + i, "Alice");
        }
        assertTrue(t.secondesDeBlocage("9.9.9.9", "alice") > 0, "casse ignoree");
        assertEquals(0, t.secondesDeBlocage("9.9.9.9", "bob"));
    }

    @Test
    void deblocageApres15MinutesEtFenetreGlissante() {
        for (int i = 0; i < LoginThrottle.ECHECS_MAX_IP; i++) {
            t.echec("1.2.3.4", "x");
        }
        assertTrue(t.secondesDeBlocage("1.2.3.4", "x") > 0);
        h.maintenant = h.maintenant.plus(LoginThrottle.VERROU).plusSeconds(2);
        assertEquals(0, t.secondesDeBlocage("1.2.3.4", "x"));

        // des echecs espaces de plus de 15 minutes ne s'additionnent pas
        for (int i = 0; i < LoginThrottle.ECHECS_MAX_IP - 1; i++) {
            t.echec("5.5.5.5", "y");
            h.maintenant = h.maintenant.plus(LoginThrottle.FENETRE).plusSeconds(1);
        }
        assertEquals(0, t.secondesDeBlocage("5.5.5.5", "y"));
    }

    @Test
    void uneConnexionReussieNeRemetPasLeCompteurDeLAdresseAZero() {
        for (int i = 0; i < LoginThrottle.ECHECS_MAX_IP - 1; i++) {
            t.echec("1.2.3.4", "cible");
        }
        t.succes("mon-propre-compte");
        t.echec("1.2.3.4", "cible");
        assertTrue(t.secondesDeBlocage("1.2.3.4", "n-importe-qui") > 0);
    }
}
