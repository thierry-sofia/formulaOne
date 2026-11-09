package com.formulaone;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Freine les essais de mots de passe : trop d'echecs depuis une meme adresse IP ou sur un meme login dans la
 * fenetre de 15 minutes bloquent les tentatives pendant 15 minutes, meme avec le bon mot de passe.
 * Une connexion reussie ne remet PAS le compteur de l'adresse a zero (sinon un compte valide servirait a
 * relancer les essais sur un autre). Memoire seulement : un redemarrage remet les compteurs a zero.
 */
@Component
public class LoginThrottle {

    static final int ECHECS_MAX_IP = 10;
    static final int ECHECS_MAX_LOGIN = 20;
    static final Duration FENETRE = Duration.ofMinutes(15);
    static final Duration VERROU = Duration.ofMinutes(15);
    private static final int TAILLE_MAX = 10_000;

    private static final class Etat {
        int echecs;
        Instant debut;
        Instant verrouJusqua;
    }

    private final Clock horloge;
    private final Map<String, Etat> etats = new HashMap<>();

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock horloge) {
        this.horloge = horloge;
    }

    private static String cleIp(String ip) {
        return "ip:" + ip;
    }

    private static String cleLogin(String login) {
        String l = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
        return "login:" + (l.length() > 50 ? l.substring(0, 50) : l);
    }

    /** Secondes restantes de blocage (0 = autorise). */
    public synchronized long secondesDeBlocage(String ip, String login) {
        Instant maintenant = horloge.instant();
        long max = 0;
        for (String cle : new String[] {cleIp(ip), cleLogin(login)}) {
            Etat e = etats.get(cle);
            if (e != null && e.verrouJusqua != null && e.verrouJusqua.isAfter(maintenant)) {
                max = Math.max(max, Duration.between(maintenant, e.verrouJusqua).toSeconds() + 1);
            }
        }
        return max;
    }

    public synchronized void echec(String ip, String login) {
        nettoyer();
        compter(cleIp(ip), ECHECS_MAX_IP);
        compter(cleLogin(login), ECHECS_MAX_LOGIN);
    }

    /** Connexion reussie : seul le compteur du login est remis a zero. */
    public synchronized void succes(String login) {
        etats.remove(cleLogin(login));
    }

    private void compter(String cle, int max) {
        Instant maintenant = horloge.instant();
        Etat e = etats.computeIfAbsent(cle, k -> new Etat());
        if (e.debut == null || e.debut.plus(FENETRE).isBefore(maintenant)) {
            e.debut = maintenant;
            e.echecs = 0;
        }
        e.echecs++;
        if (e.echecs >= max) {
            e.verrouJusqua = maintenant.plus(VERROU);
        }
    }

    /** Evite qu'un attaquant fasse grossir la memoire avec des logins inventes. */
    private void nettoyer() {
        if (etats.size() < TAILLE_MAX) {
            return;
        }
        Instant maintenant = horloge.instant();
        etats.values().removeIf(e -> (e.verrouJusqua == null || e.verrouJusqua.isBefore(maintenant))
                && e.debut != null && e.debut.plus(FENETRE).isBefore(maintenant));
        if (etats.size() >= TAILLE_MAX) {
            etats.clear();   // dernier recours : mieux vaut oublier que saturer la memoire
        }
    }
}
