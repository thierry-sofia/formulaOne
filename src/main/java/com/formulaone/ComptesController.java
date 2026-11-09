package com.formulaone;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Gestion des comptes : /api/admin/** est reserve aux administrateurs (cf. SecurityConfig). */
@RestController
public class ComptesController {

    public record NouveauCompte(String login, String password, String role, List<String> sources) { }
    public record Sources(List<String> sources) { }
    public record MotDePasse(String password) { }
    public record Role(String role) { }

    private final ComptesService comptes;

    public ComptesController(ComptesService comptes) {
        this.comptes = comptes;
    }

    /** Qui suis-je (l'interface affiche le lien « Comptes » aux administrateurs). */
    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + ComptesService.ADMIN));
        return Map.of("login", auth.getName(), "admin", admin);
    }

    @GetMapping("/api/admin/comptes")
    public List<ComptesService.Compte> liste() {
        return comptes.liste();
    }

    @PostMapping("/api/admin/comptes")
    public ResponseEntity<ComptesService.Compte> creer(@RequestBody NouveauCompte c) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comptes.creer(c.login(), c.password(), c.role(), c.sources()));
    }

    @PutMapping("/api/admin/comptes/{login}/password")
    public Map<String, String> motDePasse(@PathVariable String login, @RequestBody MotDePasse m) {
        comptes.changerMotDePasse(login, m.password());
        return Map.of("message", "Mot de passe modifié");
    }

    @PutMapping("/api/admin/comptes/{login}/role")
    public Map<String, String> role(@PathVariable String login, @RequestBody Role r) {
        comptes.changerRole(login, r.role());
        return Map.of("message", "Rôle modifié");
    }

    /** Sources autorisees : liste, ou null pour toutes les sources. */
    @PutMapping("/api/admin/comptes/{login}/sources")
    public Map<String, String> sources(@PathVariable String login, @RequestBody Sources s) {
        comptes.changerSources(login, s.sources());
        return Map.of("message", "Sources modifiées");
    }

    @DeleteMapping("/api/admin/comptes/{login}")
    public Map<String, String> supprimer(@PathVariable String login, Authentication auth) {
        comptes.supprimer(login, auth.getName());
        return Map.of("message", "Compte supprimé");
    }

    @ExceptionHandler(ComptesService.RefusException.class)
    ResponseEntity<Map<String, String>> refus(ComptesService.RefusException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ComptesService.ConflitException.class)
    ResponseEntity<Map<String, String>> conflit(ComptesService.ConflitException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> erreur(Exception e) {
        // detail non expose (chemin du fichier, etc.) : voir le journal du service
        org.slf4j.LoggerFactory.getLogger(ComptesController.class).error("Gestion des comptes", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Erreur serveur"));
    }
}
