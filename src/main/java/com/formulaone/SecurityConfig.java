package com.formulaone;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Tout est protege par une authentification HTTP Basic.
 * L'application est en lecture seule (GET uniquement), donc la protection CSRF n'apporte rien ici.
 * A exposer uniquement derriere HTTPS (nginx), sinon le mot de passe circule en clair.
 *
 * Comptes : le fichier des comptes (login=empreinte BCrypt, une ligne par compte, voir deploy/README.md),
 * charge au demarrage, plus le compte FORMULAONE_USER / FORMULAONE_PASSWORD s'il est renseigne.
 * L'application refuse de demarrer sans aucun compte.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    UserDetailsService users(@Value("${formulaone.users-file:}") String usersFile,
                             @Value("${spring.security.user.name:admin}") String envUser,
                             @Value("${spring.security.user.password:}") String envPassword) throws IOException {
        List<UserDetails> users = new ArrayList<>();

        if (!usersFile.isBlank() && Files.isReadable(Path.of(usersFile))) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(usersFile))) {
                p.load(in);
            }
            for (String login : p.stringPropertyNames()) {
                String hash = p.getProperty(login).trim();
                if (login.isBlank() || !hash.startsWith("$2")) {
                    throw new IllegalStateException("Fichier des comptes : l'empreinte de « " + login
                            + " » n'est pas une empreinte BCrypt (java -jar formulaone.jar hash)");
                }
                users.add(User.withUsername(login.trim()).password("{bcrypt}" + hash).roles("USER").build());
            }
        }
        if (!envPassword.isBlank() && users.stream().noneMatch(u -> u.getUsername().equals(envUser))) {
            users.add(User.withUsername(envUser).password("{noop}" + envPassword).roles("USER").build());
        }
        if (users.isEmpty()) {
            throw new IllegalStateException("Aucun compte : renseigner FORMULAONE_PASSWORD ou le fichier des comptes");
        }
        return new InMemoryUserDetailsManager(users);
    }
}
