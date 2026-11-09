package com.formulaone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Numeros CAS des matieres, lus dans un fichier d'extraction du Kardex (le CAS n'est pas dans maBase).
 * Format : une ligne par matiere, colonnes separees par des tabulations ou des points-virgules, comme le Kardex :
 * code ; libelle ; type ; CAS ; cout ; naturelle. Une premiere ligne d'en-tete avec une colonne "CAS" est acceptee,
 * et dans ce cas c'est elle qui indique la colonne du CAS. Le fichier est relu automatiquement s'il change.
 * Une solution (code absent du CAS mais present dans le fichier) reprend le CAS de sa matiere de base
 * (les 6 premiers caracteres du code). Un code absent du fichier n'a pas de CAS : rien n'est devine.
 */
@Component
public class CasService {

    private static final Logger log = LoggerFactory.getLogger(CasService.class);
    private static final Pattern CAS = Pattern.compile("^\\d{2,7}-\\d{2}-\\d$");
    private static final int COLONNE_CAS_PAR_DEFAUT = 3;

    private final Path fichier;
    private Map<String, String> table = Map.of();   // code -> CAS ("" si la ligne n'a pas de CAS valide)
    private long derniereModif = Long.MIN_VALUE;

    public CasService(@Value("${formulaone.cas-file:}") String chemin) {
        this.fichier = chemin == null || chemin.isBlank() ? null : Path.of(chemin);
    }

    /** CAS de la matiere, ou null s'il n'y en a pas. */
    public synchronized String cas(String codeMatiere) {
        if (codeMatiere == null) {
            return null;
        }
        recharger();
        String code = codeMatiere.trim().toUpperCase(Locale.ROOT);
        String cas = table.get(code);
        if (cas == null) {
            return null;                                   // code inconnu du Kardex : on ne devine pas
        }
        if (!cas.isEmpty()) {
            return cas;
        }
        // ligne connue mais sans CAS (typiquement une solution) : CAS de la matiere de base
        if (code.length() > 6) {
            String base = table.get(code.substring(0, 6));
            if (base != null && !base.isEmpty()) {
                return base;
            }
        }
        return null;
    }

    private void recharger() {
        if (fichier == null) {
            return;
        }
        long modif;
        try {
            if (!Files.isReadable(fichier)) {
                table = Map.of();
                derniereModif = Long.MIN_VALUE;
                return;
            }
            modif = Files.getLastModifiedTime(fichier).toMillis();
            if (modif == derniereModif) {
                return;
            }
            table = lire(Files.readAllLines(fichier, StandardCharsets.UTF_8));
            derniereModif = modif;
            long avecCas = table.values().stream().filter(v -> !v.isEmpty()).count();
            log.info("[cas] {} matieres lues dans {} ({} avec un CAS)", table.size(), fichier, avecCas);
        } catch (IOException | RuntimeException e) {
            log.warn("[cas] lecture impossible de {} : {}", fichier, e.getMessage());
        }
    }

    static Map<String, String> lire(List<String> lignes) {
        Map<String, String> r = new HashMap<>();
        int colonneCas = COLONNE_CAS_PAR_DEFAUT;
        boolean premiere = true;
        for (String brut : lignes) {
            String ligne = brut.replace("﻿", "");
            if (ligne.isBlank() || ligne.startsWith("#")) {
                continue;
            }
            String[] c = ligne.split(ligne.indexOf('\t') >= 0 ? "\t" : ";", -1);
            if (premiere) {
                premiere = false;
                int entete = indexEntete(c);
                if (entete >= 0) {
                    colonneCas = entete;
                    continue;
                }
            }
            String code = c[0].trim().replace("\"", "").toUpperCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            String cas = colonneCas < c.length ? c[colonneCas].trim().replace("\"", "") : "";
            r.put(code, CAS.matcher(cas).matches() ? cas : "");   // "." , "mixture", vide... : pas de CAS
        }
        return r;
    }

    /** Indice de la colonne dont le titre contient "CAS", si la premiere ligne est un en-tete ; sinon -1. */
    private static int indexEntete(String[] c) {
        for (int i = 0; i < c.length; i++) {
            String t = c[i].trim().replace("\"", "").toUpperCase(Locale.ROOT);
            if (t.equals("CAS") || t.startsWith("CAS ") || t.startsWith("CAS#") || t.endsWith(" CAS") || t.contains("NUMERO CAS")
                    || t.contains("NUMÉRO CAS")) {
                return i;
            }
        }
        return -1;
    }
}
