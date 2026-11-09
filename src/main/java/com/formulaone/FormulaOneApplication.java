package com.formulaone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class FormulaOneApplication {

    public static void main(String[] args) {
        // java -jar formulaone.jar hash : calcule l'empreinte BCrypt d'un mot de passe (saisi sans echo)
        // pour le fichier des comptes. Ne demarre pas l'application.
        if (args.length == 1 && "hash".equals(args[0])) {
            System.out.println(new BCryptPasswordEncoder().encode(lireMotDePasse()));
            return;
        }
        SpringApplication.run(FormulaOneApplication.class, args);
    }

    private static String lireMotDePasse() {
        if (System.console() != null) {
            char[] saisie = System.console().readPassword("Mot de passe : ");
            if (saisie != null && saisie.length > 0) {
                return new String(saisie);
            }
        } else {
            // entree redirigee (echo "motdepasse" | java -jar ... hash)
            String ligne = new java.util.Scanner(System.in).nextLine();
            if (!ligne.isEmpty()) {
                return ligne;
            }
        }
        throw new IllegalArgumentException("Mot de passe vide");
    }
}
