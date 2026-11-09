package com.formulaone;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tout est protege par une authentification HTTP Basic, a exposer uniquement derriere HTTPS (nginx).
 * - /api/admin/** et la page des comptes : administrateurs seulement ;
 * - les requetes qui modifient (POST, PUT, DELETE) doivent porter l'en-tete X-Requested-With: formulaone.
 *   Un site tiers ne peut pas l'ajouter sans l'accord du navigateur (preflight CORS), ce qui remplace le jeton
 *   CSRF pour cette API Basic ; la protection CSRF classique est donc desactivee.
 * Comptes : voir ComptesService (fichier des comptes + compte du serveur). Sans aucun compte, refus de demarrer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ENTETE_MODIF = "X-Requested-With";
    static final String VALEUR_ENTETE_MODIF = "formulaone";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .addFilterBefore(new EnteteModificationFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**", "/admin-comptes.html").hasRole(ComptesService.ADMIN)
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    ComptesService comptesService(@Value("${formulaone.users-file:}") String usersFile,
                                  @Value("${spring.security.user.name:admin}") String envUser,
                                  @Value("${spring.security.user.password:}") String envPassword) throws IOException {
        return new ComptesService(usersFile.isBlank() ? null : Path.of(usersFile), envUser, envPassword);
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
}
