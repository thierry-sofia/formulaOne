package com.formulaone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();
    private final org.springframework.security.crypto.password.PasswordEncoder encodeur = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void chargeLesComptesDuFichierEtLeCompteUnique(@TempDir Path dir) throws Exception {
        String hash = new BCryptPasswordEncoder().encode("secret1");
        Path f = dir.resolve("users.properties");
        Files.writeString(f, "alice=" + hash + "\n");

        UserDetailsService s = config.users(f.toString(), "admin", "pwdadmin");

        UserDetails alice = s.loadUserByUsername("alice");
        assertTrue(encodeur.matches("secret1", alice.getPassword()));
        assertTrue(encodeur.matches("pwdadmin", s.loadUserByUsername("admin").getPassword()));
    }

    @Test
    void fichierAbsentEtMotDePasseUniqueSuffit(@TempDir Path dir) throws Exception {
        UserDetailsService s = config.users(dir.resolve("absent").toString(), "admin", "pwd");
        assertEquals("admin", s.loadUserByUsername("admin").getUsername());
    }

    @Test
    void sansAucunCompteLeDemarrageEstRefuse(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> config.users("", "admin", ""));
    }

    @Test
    void empreinteInvalideRefusee(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("users.properties");
        Files.writeString(f, "bob=motdepasseenclair\n");
        assertThrows(IllegalStateException.class, () -> config.users(f.toString(), "admin", ""));
    }
}
