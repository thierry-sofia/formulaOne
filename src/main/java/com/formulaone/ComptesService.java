package com.formulaone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Comptes de l'application. Deux sources :
 * - le compte du serveur (FORMULAONE_USER / FORMULAONE_PASSWORD) : administrateur, non modifiable ici ;
 * - le fichier des comptes (une ligne "login=empreinteBCrypt;ROLE", ROLE = ADMIN ou USER, USER par defaut),
 *   modifiable depuis l'ecran d'administration. Chaque modification est ecrite aussitot (fichier remplace
 *   de facon atomique, droits 600) ; si l'ecriture echoue, la modification est annulee en memoire.
 * Les mots de passe ne sont jamais stockes ni journalises en clair.
 */
public class ComptesService implements UserDetailsService {

    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";

    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9._@-]{3,50}$");
    private static final int MDP_MIN = 10;
    private static final int MDP_MAX_OCTETS = 72;   // limite de BCrypt

    /** sources : null = toutes les sources, sinon la liste des seules sources autorisees. */
    public record Compte(String login, String role, boolean compteServeur, List<String> sources) { }

    /** Refus de la regle metier (400). */
    public static class RefusException extends RuntimeException {
        public RefusException(String message) { super(message); }
    }

    /** Conflit (409) : login deja pris. */
    public static class ConflitException extends RuntimeException {
        public ConflitException(String message) { super(message); }
    }

    /** sources : null = toutes. Ignore pour un administrateur (toujours toutes). */
    private record Entree(String empreinte, String role, Set<String> sources) { }

    private final Path fichier;                       // null ou introuvable : pas de persistance
    private final String loginServeur;
    private final String mdpServeur;                  // vide : pas de compte serveur
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final Map<String, Entree> comptes = new LinkedHashMap<>();

    public ComptesService(Path fichier, String loginServeur, String mdpServeur) {
        this.fichier = fichier;
        this.loginServeur = loginServeur == null ? "" : loginServeur.trim();
        this.mdpServeur = mdpServeur == null ? "" : mdpServeur;
        charger();
        if (comptes.isEmpty() && this.mdpServeur.isBlank()) {
            throw new IllegalStateException("Aucun compte : renseigner FORMULAONE_PASSWORD ou le fichier des comptes");
        }
    }

    // ---------- chargement / ecriture du fichier ----------

    private void charger() {
        if (fichier == null || !Files.isReadable(fichier)) {
            return;
        }
        try {
            for (String ligne : Files.readAllLines(fichier, StandardCharsets.UTF_8)) {
                String l = ligne.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                int egal = l.indexOf('=');
                String login = egal < 0 ? "" : l.substring(0, egal).trim();
                String valeur = egal < 0 ? "" : l.substring(egal + 1).trim();
                String empreinte = valeur;
                String role = USER;
                Set<String> sources = null;
                String[] parties = valeur.split(";", -1);
                if (parties.length > 1) {
                    empreinte = parties[0].trim();
                    role = parties[1].trim().toUpperCase(Locale.ROOT);
                    if (parties.length > 2) {
                        sources = lireSources(parties[2].trim());
                    }
                }
                if (login.isEmpty() || !empreinte.startsWith("$2")) {
                    throw new IllegalStateException("Fichier des comptes : l'empreinte de « " + login
                            + " » n'est pas une empreinte BCrypt (java -jar formulaone.jar hash)");
                }
                if (!role.equals(ADMIN) && !role.equals(USER)) {
                    throw new IllegalStateException("Fichier des comptes : role inconnu pour « " + login + " » : " + role);
                }
                comptes.put(login, new Entree(empreinte, role, sources));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du fichier des comptes impossible", e);
        }
    }

    /** Ecrit le fichier de facon atomique. Appelee sous verrou. */
    private void ecrire() {
        if (fichier == null) {
            throw new RefusException("Fichier des comptes non configure : modification impossible");
        }
        StringBuilder sb = new StringBuilder("# Comptes formulaOne (login=empreinte BCrypt;ROLE;SOURCES, * = toutes). Gere par l'ecran d'administration.\n");
        comptes.forEach((login, e) -> sb.append(login).append('=').append(e.empreinte()).append(';').append(e.role())
                .append(';').append(ecrireSources(e.sources())).append('\n'));
        try {
            Path dossier = fichier.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dossier, ".comptes-", ".tmp");
            try {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignore) {
                    // systeme de fichiers sans droits POSIX
                }
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, fichier, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture du fichier des comptes impossible", e);
        }
    }

    // ---------- authentification ----------

    @Override
    public synchronized UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Entree e = comptes.get(username);
        if (e != null) {
            return User.withUsername(username).password("{bcrypt}" + e.empreinte()).roles(e.role()).build();
        }
        if (!mdpServeur.isBlank() && username.equals(loginServeur)) {
            return User.withUsername(username).password("{noop}" + mdpServeur).roles(ADMIN).build();
        }
        throw new UsernameNotFoundException("Compte inconnu");
    }

    // ---------- administration ----------

    public synchronized List<Compte> liste() {
        List<Compte> r = new ArrayList<>();
        if (!mdpServeur.isBlank()) {
            r.add(new Compte(loginServeur, ADMIN, true, null));
        }
        comptes.forEach((login, e) -> r.add(new Compte(login, e.role(), false, e.sources() == null ? null : List.copyOf(e.sources()))));
        return r;
    }

    public Compte creer(String login, String mdp, String role) {
        return creer(login, mdp, role, null);
    }

    public synchronized Compte creer(String login, String mdp, String role, List<String> sources) {
        String l = validerLogin(login);
        validerMdp(mdp);
        String r = validerRole(role);
        Set<String> src = validerSources(sources);
        if (existe(l)) {
            throw new ConflitException("Ce login existe déjà");
        }
        comptes.put(l, new Entree(bcrypt.encode(mdp), r, src));
        persisterOuAnnuler(l, null);
        return new Compte(l, r, false, src == null ? null : List.copyOf(src));
    }

    public synchronized void changerMotDePasse(String login, String mdp) {
        Entree avant = comptesFichier(login);
        validerMdp(mdp);
        comptes.put(login, new Entree(bcrypt.encode(mdp), avant.role(), avant.sources()));
        persisterOuAnnuler(login, avant);
    }

    public synchronized void changerRole(String login, String role) {
        Entree avant = comptesFichier(login);
        String r = validerRole(role);
        if (avant.role().equals(ADMIN) && !r.equals(ADMIN) && nbAdministrateurs() <= 1) {
            throw new RefusException("Impossible : ce serait le dernier administrateur");
        }
        comptes.put(login, new Entree(avant.empreinte(), r, avant.sources()));
        persisterOuAnnuler(login, avant);
    }

    /** @param sources null = toutes les sources ; sinon au moins une. */
    public synchronized void changerSources(String login, List<String> sources) {
        Entree avant = comptesFichier(login);
        comptes.put(login, new Entree(avant.empreinte(), avant.role(), validerSources(sources)));
        persisterOuAnnuler(login, avant);
    }

    /**
     * Sources que ce compte a le droit de voir : null = toutes (administrateurs et compte du serveur),
     * sinon l'ensemble des sources autorisees. Un compte inconnu n'a acces a rien (ensemble vide).
     */
    public synchronized Set<String> sourcesAutorisees(String login) {
        Entree e = comptes.get(login);
        if (e == null) {
            return (!mdpServeur.isBlank() && login.equals(loginServeur)) ? null : Set.of();
        }
        return e.role().equals(ADMIN) ? null : e.sources();
    }

    /** @param demandeur login de l'administrateur connecte (il ne peut pas se supprimer lui-meme) */
    public synchronized void supprimer(String login, String demandeur) {
        Entree avant = comptesFichier(login);
        if (login.equals(demandeur)) {
            throw new RefusException("Vous ne pouvez pas supprimer votre propre compte");
        }
        if (avant.role().equals(ADMIN) && nbAdministrateurs() <= 1) {
            throw new RefusException("Impossible : ce serait le dernier administrateur");
        }
        comptes.remove(login);
        persisterOuAnnuler(login, avant);
    }

    // ---------- outils ----------

    private boolean existe(String login) {
        if (!mdpServeur.isBlank() && login.equalsIgnoreCase(loginServeur)) {
            return true;
        }
        return comptes.keySet().stream().anyMatch(c -> c.equalsIgnoreCase(login));
    }

    private Entree comptesFichier(String login) {
        if (!mdpServeur.isBlank() && login.equals(loginServeur)) {
            throw new RefusException("Le compte du serveur se change dans scentsync.env (FORMULAONE_PASSWORD)");
        }
        Entree e = comptes.get(login);
        if (e == null) {
            throw new RefusException("Compte introuvable");
        }
        return e;
    }

    private long nbAdministrateurs() {
        long n = comptes.values().stream().filter(e -> e.role().equals(ADMIN)).count();
        return n + (mdpServeur.isBlank() ? 0 : 1);
    }

    /** Ecrit le fichier ; en cas d'echec remet la memoire dans l'etat d'avant (avant == null : compte ajoute). */
    private void persisterOuAnnuler(String login, Entree avant) {
        try {
            ecrire();
        } catch (RuntimeException e) {
            if (avant == null) {
                comptes.remove(login);
            } else {
                comptes.put(login, avant);
            }
            throw e;
        }
    }

    private static String validerLogin(String login) {
        String l = login == null ? "" : login.trim();
        if (!LOGIN.matcher(l).matches()) {
            throw new RefusException("Login : 3 à 50 caractères parmi lettres, chiffres, . _ @ -");
        }
        return l;
    }

    private static void validerMdp(String mdp) {
        if (mdp == null || mdp.length() < MDP_MIN) {
            throw new RefusException("Mot de passe : " + MDP_MIN + " caractères au moins");
        }
        if (mdp.getBytes(StandardCharsets.UTF_8).length > MDP_MAX_OCTETS) {
            throw new RefusException("Mot de passe trop long (72 octets au plus)");
        }
    }

    private static Set<String> validerSources(List<String> sources) {
        if (sources == null) {
            return null;
        }
        Set<String> r = new LinkedHashSet<>();
        for (String s : sources) {
            String v = s == null ? "" : s.trim();
            if (v.isEmpty() || v.length() > 50) {
                throw new RefusException("Source invalide");
            }
            r.add(v);
        }
        if (r.isEmpty()) {
            throw new RefusException("Choisir au moins une source, ou « toutes les sources »");
        }
        if (r.size() > 50) {
            throw new RefusException("Trop de sources");
        }
        return r;
    }

    private static Set<String> lireSources(String texte) {
        if (texte.isEmpty() || texte.equals("*")) {
            return null;
        }
        Set<String> r = new LinkedHashSet<>();
        for (String p : texte.split(",")) {
            r.add(URLDecoder.decode(p, StandardCharsets.UTF_8));
        }
        return r;
    }

    private static String ecrireSources(Set<String> sources) {
        if (sources == null) {
            return "*";
        }
        return String.join(",", sources.stream().map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8)).toList());
    }

    private static String validerRole(String role) {
        String r = role == null ? USER : role.trim().toUpperCase(Locale.ROOT);
        if (!r.equals(ADMIN) && !r.equals(USER)) {
            throw new RefusException("Rôle : ADMIN ou USER");
        }
        return r;
    }
}
