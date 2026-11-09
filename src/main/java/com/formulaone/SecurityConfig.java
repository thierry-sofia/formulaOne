package com.formulaone;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Connexion par session (page login.html, POST /api/login), a exposer uniquement derriere HTTPS (nginx).
 * - Plus d'authentification HTTP Basic : elle permettrait d'essayer des mots de passe sans le frein de LoginThrottle.
 * - Cookie de session : HttpOnly, Secure, SameSite=Strict, 30 minutes d'inactivite (application.properties).
 * - Une session est coupee des qu'on supprime le compte, change son mot de passe ou son role, et au bout de
 *   12 heures quoi qu'il arrive (SessionValidationFilter).
 * - /api/admin/** et la page des comptes : administrateurs seulement.
 * - Les requetes qui modifient (POST, PUT, DELETE, y compris la connexion) doivent porter l'en-tete
 *   X-Requested-With: formulaone. Un site tiers ne peut pas l'envoyer sans l'accord du navigateur (preflight CORS,
 *   que l'application n'accorde jamais) ; avec SameSite=Strict cela remplace le jeton CSRF.
 * - Non authentifie : 401 pour /api/**, redirection vers login.html pour les pages.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ENTETE_MODIF = "X-Requested-With";
    static final String VALEUR_ENTETE_MODIF = "formulaone";
    static final long DUREE_MAX_SESSION_MS = 12L * 60 * 60 * 1000;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ComptesService comptes) throws Exception {
        http
            .addFilterBefore(new SessionValidationFilter(comptes), SecurityContextHolderFilter.class)
            .addFilterBefore(new EnteteModificationFilter(), SessionValidationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login.html", "/api/login", "/api/logout").permitAll()
                .requestMatchers("/api/admin/**", "/admin-comptes.html").hasRole(ComptesService.ADMIN)
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(pointDEntree()))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; "
                        + "form-action 'self'; base-uri 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                // HSTS : laisse a nginx (le meme nom d'hote sert aussi ParfumIQ sur d'autres ports)
                .httpStrictTransportSecurity(hsts -> hsts.disable()))
            .csrf(csrf -> csrf.disable());   // remplace par l'en-tete de controle + SameSite=Strict, voir ci-dessus
        return http.build();
    }

    /** Non authentifie : 401 pour l'API (le JavaScript redirige alors vers login.html), redirection pour les pages. */
    private static AuthenticationEntryPoint pointDEntree() {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> parRequete = new LinkedHashMap<>();
        parRequete.put(new AntPathRequestMatcher("/api/**"), new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        DelegatingAuthenticationEntryPoint ep = new DelegatingAuthenticationEntryPoint(parRequete);
        // Redirection RELATIVE (Location: /login.html) : on reste sur l'adresse utilisee par le visiteur. Une adresse
        // absolue serait reconstruite a partir des en-tetes (Host, X-Forwarded-*) que nginx envoie, et renverrait
        // vers un autre nom d'hote si ces en-tetes sont ceux d'un autre site.
        // (ecrite a la main : sendRedirect() la transformerait en adresse absolue)
        ep.setDefaultEntryPoint((req, res, e) -> {
            res.setStatus(HttpServletResponse.SC_FOUND);
            res.setHeader("Location", "/login.html");
        });
        return ep;
    }

    @Bean
    ComptesService comptesService(@Value("${formulaone.users-file:}") String usersFile,
                                  @Value("${spring.security.user.name:admin}") String envUser,
                                  @Value("${spring.security.user.password:}") String envPassword) throws IOException {
        return new ComptesService(usersFile.isBlank() ? null : Path.of(usersFile), envUser, envPassword);
    }

    @Bean
    AuthenticationManager authenticationManager(ComptesService comptes) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(comptes);
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        return new ProviderManager(provider);
    }

    /** Refuse (403) toute requete qui modifie sans l'en-tete de controle. */
    static class EnteteModificationFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String m = req.getMethod();
            boolean lecture = HttpMethod.GET.matches(m) || HttpMethod.HEAD.matches(m) || HttpMethod.OPTIONS.matches(m);
            if (!lecture && !VALEUR_ENTETE_MODIF.equals(req.getHeader(ENTETE_MODIF))) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "En-tete de controle manquant");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    /** Coupe la session si le compte a change ou disparu depuis la connexion, ou si elle a plus de 12 heures. */
    static class SessionValidationFilter extends OncePerRequestFilter {
        private final ComptesService comptes;

        SessionValidationFilter(ComptesService comptes) {
            this.comptes = comptes;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            HttpSession session = req.getSession(false);
            if (session != null) {
                Object login = session.getAttribute(AuthController.SESSION_LOGIN);
                Object signature = session.getAttribute(AuthController.SESSION_SIGNATURE);
                Object debut = session.getAttribute(AuthController.SESSION_DEBUT);
                if (login != null) {
                    String actuelle = comptes.signature(login.toString());
                    boolean compteChange = actuelle == null || !actuelle.equals(signature);
                    boolean tropVieille = !(debut instanceof Long d) || System.currentTimeMillis() - d > DUREE_MAX_SESSION_MS;
                    if (compteChange || tropVieille) {
                        session.invalidate();
                    }
                }
            }
            chain.doFilter(req, res);
        }
    }
}
