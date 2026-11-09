package com.formulaone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.formulaone.ComptesService.ConflitException;
import com.formulaone.ComptesService.RefusException;

class ComptesServiceTest {

    private final PasswordEncoder encodeur = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private ComptesService service(Path dir) {
        return new ComptesService(dir.resolve("users.properties"), "admin", "mdpServeur1");
    }

    private boolean motDePasseOk(ComptesService s, String login, String mdp) {
        return encodeur.matches(mdp, s.loadUserByUsername(login).getPassword());
    }

    @Test
    void creerPersisteEtSeRecharge(@TempDir Path dir) throws Exception {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "USER");

        assertTrue(motDePasseOk(s, "alice", "motdepasse-1"));
        // un autre demarrage relit le fichier
        ComptesService relu = service(dir);
        assertTrue(motDePasseOk(relu, "alice", "motdepasse-1"));
        String contenu = Files.readString(dir.resolve("users.properties"));
        assertFalse(contenu.contains("motdepasse-1"), "jamais de mot de passe en clair");
        assertTrue(contenu.contains("alice=$2"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("users.properties"))));
    }

    @Test
    void compteServeurEstAdministrateurEtListeEnPremier(@TempDir Path dir) {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "USER");
        List<ComptesService.Compte> l = s.liste();
        assertEquals(new ComptesService.Compte("admin", "ADMIN", true, null), l.get(0));
        assertEquals("alice", l.get(1).login());
        UserDetails admin = s.loadUserByUsername("admin");
        assertTrue(admin.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void validationDesSaisies(@TempDir Path dir) {
        ComptesService s = service(dir);
        assertThrows(RefusException.class, () -> s.creer("ab", "motdepasse-1", "USER"));
        assertThrows(RefusException.class, () -> s.creer("al ice", "motdepasse-1", "USER"));
        assertThrows(RefusException.class, () -> s.creer("a=b;ADMIN", "motdepasse-1", "USER"));
        assertThrows(RefusException.class, () -> s.creer("alice", "court", "USER"));
        assertThrows(RefusException.class, () -> s.creer("alice", "x".repeat(73), "USER"));
        assertThrows(RefusException.class, () -> s.creer("alice", "motdepasse-1", "ROOT"));
        assertEquals(1, s.liste().size(), "rien n'a ete cree");
    }

    @Test
    void loginDejaPrisSansTenirCompteDeLaCasse(@TempDir Path dir) {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "USER");
        assertThrows(ConflitException.class, () -> s.creer("ALICE", "motdepasse-2", "USER"));
        assertThrows(ConflitException.class, () -> s.creer("Admin", "motdepasse-2", "USER"));
    }

    @Test
    void changerMotDePasseEtRole(@TempDir Path dir) {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "USER");
        s.changerMotDePasse("alice", "nouveau-mdp-2");
        assertFalse(motDePasseOk(s, "alice", "motdepasse-1"));
        assertTrue(motDePasseOk(service(dir), "alice", "nouveau-mdp-2"));
        s.changerRole("alice", "ADMIN");
        assertEquals("ADMIN", service(dir).liste().get(1).role());
    }

    @Test
    void compteServeurNonModifiable(@TempDir Path dir) {
        ComptesService s = service(dir);
        assertThrows(RefusException.class, () -> s.changerMotDePasse("admin", "nouveau-mdp-2"));
        assertThrows(RefusException.class, () -> s.supprimer("admin", "alice"));
        assertThrows(RefusException.class, () -> s.changerRole("admin", "USER"));
    }

    @Test
    void supprimerEtGardeFous(@TempDir Path dir) {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "ADMIN");
        s.creer("bob", "motdepasse-1", "USER");

        assertThrows(RefusException.class, () -> s.supprimer("alice", "alice"), "pas soi-meme");
        s.supprimer("bob", "alice");
        assertThrows(UsernameNotFoundException.class, () -> s.loadUserByUsername("bob"));
        assertThrows(UsernameNotFoundException.class, () -> service(dir).loadUserByUsername("bob"));
        assertThrows(RefusException.class, () -> s.supprimer("inconnu", "alice"));
    }

    @Test
    void sansAucunCompteLeDemarrageEstRefuse(@TempDir Path dir) {
        // sans compte serveur ni fichier : refus de demarrer
        assertThrows(IllegalStateException.class, () -> new ComptesService(dir.resolve("vide.properties"), "admin", ""));
    }

    @Test
    void dernierAdministrateurDuFichierProtege(@TempDir Path dir) throws Exception {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("motdepasse-1");
        Files.writeString(dir.resolve("u.properties"), "solo=" + hash + ";ADMIN\nlecteur=" + hash + "\n");
        ComptesService s = new ComptesService(dir.resolve("u.properties"), "admin", "");

        assertThrows(RefusException.class, () -> s.changerRole("solo", "USER"));
        assertThrows(RefusException.class, () -> s.supprimer("solo", "autre"));
        // un compte sans role dans le fichier est un simple utilisateur
        assertEquals("USER", s.liste().stream().filter(c -> c.login().equals("lecteur")).findFirst().orElseThrow().role());
    }

    @Test
    void ecritureImpossibleAnnuleLaModification(@TempDir Path dir) throws Exception {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "USER");
        // dossier supprime : l'ecriture echoue
        Files.delete(dir.resolve("users.properties"));
        Files.delete(dir);
        assertThrows(RuntimeException.class, () -> s.creer("bob", "motdepasse-1", "USER"));
        assertThrows(UsernameNotFoundException.class, () -> s.loadUserByUsername("bob"));
        assertThrows(RuntimeException.class, () -> s.changerMotDePasse("alice", "autre-mdp-123"));
        assertTrue(motDePasseOk(s, "alice", "motdepasse-1"), "ancien mot de passe conserve");
    }

    @Test
    void fichierInvalideEmpecheLeDemarrage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("u.properties"), "bob=enclair\n");
        assertThrows(IllegalStateException.class, () -> new ComptesService(dir.resolve("u.properties"), "admin", "x"));
        Files.writeString(dir.resolve("u2.properties"), "bob=$2a$10$abcdefghijklmnopqrstuv;ROOT\n");
        assertThrows(IllegalStateException.class, () -> new ComptesService(dir.resolve("u2.properties"), "admin", "x"));
    }

    @Test
    void sourcesAutoriseesParCompte(@TempDir Path dir) {
        ComptesService s = service(dir);
        s.creer("alice", "motdepasse-1", "USER", List.of("BIS", "CONCEPT"));
        s.creer("bob", "motdepasse-1", "USER");                    // sans restriction
        s.creer("carl", "motdepasse-1", "ADMIN", List.of("BIS"));   // un administrateur voit tout

        assertEquals(Set.of("BIS", "CONCEPT"), s.sourcesAutorisees("alice"));
        assertEquals(null, s.sourcesAutorisees("bob"));
        assertEquals(null, s.sourcesAutorisees("carl"));
        assertEquals(null, s.sourcesAutorisees("admin"), "compte du serveur : tout");
        assertEquals(Set.of(), s.sourcesAutorisees("inconnu"), "compte inconnu : rien");

        // persiste et se relit, y compris les noms avec espaces ou caracteres speciaux
        s.changerSources("bob", List.of("Mes formules;2024", "a,b"));
        ComptesService relu = service(dir);
        assertEquals(Set.of("BIS", "CONCEPT"), relu.sourcesAutorisees("alice"));
        assertEquals(Set.of("Mes formules;2024", "a,b"), relu.sourcesAutorisees("bob"));
        s.changerSources("bob", null);
        assertEquals(null, service(dir).sourcesAutorisees("bob"));
    }

    @Test
    void sourcesInvalidesRefusees(@TempDir Path dir) {
        ComptesService s = service(dir);
        assertThrows(RefusException.class, () -> s.creer("alice", "motdepasse-1", "USER", List.of()));
        assertThrows(RefusException.class, () -> s.creer("alice", "motdepasse-1", "USER", List.of(" ")));
        assertThrows(RefusException.class, () -> s.creer("alice", "motdepasse-1", "USER", List.of("x".repeat(51))));
        s.creer("alice", "motdepasse-1", "USER", List.of("BIS"));
        assertThrows(RefusException.class, () -> s.changerSources("alice", List.of()));
        assertThrows(RefusException.class, () -> s.changerSources("admin", List.of("BIS")));
        assertEquals(Set.of("BIS"), s.sourcesAutorisees("alice"), "rien n'a change");
    }

    @Test
    void ancienFichierSansSourcesGardeToutesLesSources(@TempDir Path dir) throws Exception {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("motdepasse-1");
        Files.writeString(dir.resolve("u.properties"), "vieux=" + hash + "\nmoyen=" + hash + ";USER\n");
        ComptesService s = new ComptesService(dir.resolve("u.properties"), "admin", "x");
        assertEquals(null, s.sourcesAutorisees("vieux"));
        assertEquals(null, s.sourcesAutorisees("moyen"));
    }
}
