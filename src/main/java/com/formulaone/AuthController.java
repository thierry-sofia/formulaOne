package com.formulaone;

import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** Connexion et deconnexion par session (cookie HttpOnly, Secure, SameSite=Strict, 30 minutes d'inactivite). */
@RestController
public class AuthController {

    static final String SESSION_SIGNATURE = "formulaone.signature";
    static final String SESSION_DEBUT = "formulaone.debut";
    static final String SESSION_LOGIN = "formulaone.login";
    static final int INACTIVITE_SECONDES = 30 * 60;

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Pattern LOGIN_LISIBLE = Pattern.compile("^[A-Za-z0-9._@-]{1,50}$");
    private static final String MESSAGE_ECHEC = "Identifiants incorrects";

    public record Identifiants(String login, String password) { }

    private final AuthenticationManager manager;
    private final ComptesService comptes;
    private final LoginThrottle throttle;
    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager manager, ComptesService comptes, LoginThrottle throttle) {
        this.manager = manager;
        this.comptes = comptes;
        this.throttle = throttle;
    }

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Identifiants id, HttpServletRequest req,
                                                     HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        String login = id.login() == null ? "" : id.login().trim();

        long attente = throttle.secondesDeBlocage(ip, login);
        if (attente > 0) {
            log.warn("[auth] connexion refusee (trop d'echecs) ip={}", ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(attente))
                    .body(Map.of("message", "Trop de tentatives. Réessayez dans " + ((attente + 59) / 60) + " minute(s)."));
        }
        // bornes : evite d'envoyer n'importe quoi a BCrypt (qui ne lit de toute facon que 72 octets)
        if (login.isEmpty() || login.length() > 50 || id.password() == null || id.password().isEmpty()
                || id.password().length() > 200) {
            throttle.echec(ip, login);
            return echec();
        }

        Authentication auth;
        try {
            auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login, id.password()));
        } catch (AuthenticationException e) {
            throttle.echec(ip, login);
            // le login saisi n'est journalise que s'il a une forme de login (pas de mot de passe tape au mauvais endroit)
            log.warn("[auth] echec de connexion login={} ip={}", LOGIN_LISIBLE.matcher(login).matches() ? login : "?", ip);
            return echec();
        }

        throttle.succes(login);
        if (req.getSession(false) != null) {
            req.changeSessionId();                       // contre la fixation de session
        }
        HttpSession session = req.getSession(true);
        session.setMaxInactiveInterval(INACTIVITE_SECONDES);
        session.setAttribute(SESSION_LOGIN, auth.getName());
        session.setAttribute(SESSION_SIGNATURE, comptes.signature(auth.getName()));
        session.setAttribute(SESSION_DEBUT, System.currentTimeMillis());

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        repository.saveContext(ctx, req, res);

        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + ComptesService.ADMIN));
        log.info("[auth] connexion login={} ip={}", auth.getName(), ip);
        return ResponseEntity.ok(Map.of("login", auth.getName(), "admin", admin));
    }

    @PostMapping("/api/logout")
    public Map<String, String> logout(HttpServletRequest req, HttpServletResponse res) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        res.addHeader(HttpHeaders.SET_COOKIE, "JSESSIONID=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Strict");
        return Map.of("message", "Déconnecté");
    }

    private static ResponseEntity<Map<String, Object>> echec() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", MESSAGE_ECHEC));
    }
}
