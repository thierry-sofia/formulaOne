package com.formulaone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CasServiceTest {

    // lignes copiees du Kardex : code, libelle, type, CAS, cout, naturelle (separees par des tabulations)
    private static final String KARDEX = String.join("\n",
        "100750\tCIS 3 HEXENOL\tMat.Premières\t928-96-1\t47,4\tNON",
        "10075010DP\tCIS 3 HEXENOL 10% DPG\tSolutions\t\t6,32\tNON",
        "1007501CDP\tCIS 3 HEXENOL 1%DPG\tSolutions\t\t2,25\tNON",
        "100133\tAMBERKETAL GIV IPM 8.5%\tMat.Premières\t.\t655,4\tNON",
        "10013310DP\tAMBERKETAL 8.5 IPM 10% DANS DPG\tSolutions\t\t67,62\tNON",
        "101405\tFARNESENE N808\tMat.Premières\tmixture\t340\tNON",
        "101204\tTRUC\tMat.Premières\t2050-08-0 \t6,8\tNON",
        "");

    @Test
    void lectureDuFormatKardexSansEntete(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cas.tsv");
        Files.writeString(f, KARDEX);
        CasService s = new CasService(f.toString());

        assertEquals("928-96-1", s.cas("100750"));
        assertEquals("2050-08-0", s.cas("101204"), "espaces autour du CAS ignores");
        assertNull(s.cas("100133"), "'.' n'est pas un CAS");
        assertNull(s.cas("101405"), "'mixture' n'est pas un CAS");
        assertNull(s.cas("999999"), "code inconnu du Kardex : rien n'est devine");
        assertNull(s.cas(null));
    }

    @Test
    void uneSolutionReprendLeCasDeSaMatiereDeBase(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cas.tsv");
        Files.writeString(f, KARDEX);
        CasService s = new CasService(f.toString());

        assertEquals("928-96-1", s.cas("10075010DP"));
        assertEquals("928-96-1", s.cas("1007501CDP"));
        assertEquals("928-96-1", s.cas("10075010dp"), "casse ignoree");
        assertNull(s.cas("10013310DP"), "la matiere de base n'a pas de CAS : pas de CAS");
    }

    @Test
    void enteteAvecColonneCasOuPointsVirgules(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cas.csv");
        Files.writeString(f, "﻿Code matière;Libellé;Type;Numéro CAS;Coût\n\"100010\";ACETANISOLE;Mat.Premières;100-06-1;440,9\n100014;ACETOIN;Mat.Premières;513-86-0;13,4\n");
        CasService s = new CasService(f.toString());
        assertEquals("100-06-1", s.cas("100010"));
        assertEquals("513-86-0", s.cas("100014"));

        // l'en-tete peut placer le CAS ailleurs
        Path g = dir.resolve("cas2.csv");
        Files.writeString(g, "CAS;code\n100-06-1;100010\n");
        // (premiere colonne = code dans ce format : la colonne CAS est reperee par son titre, le code reste la 1re colonne)
        CasService s2 = new CasService(g.toString());
        assertNull(s2.cas("100010"), "le code est toujours la 1re colonne");
    }

    @Test
    void fichierAbsentOuNonConfigure(@TempDir Path dir) {
        assertNull(new CasService(dir.resolve("absent.tsv").toString()).cas("100750"));
        assertNull(new CasService("").cas("100750"));
    }

    @Test
    void fichierRelAutomatiquementQuandIlChange(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cas.tsv");
        Files.writeString(f, "100750\tCIS 3 HEXENOL\tMat.Premières\t928-96-1\t47,4\tNON\n");
        CasService s = new CasService(f.toString());
        assertEquals("928-96-1", s.cas("100750"));

        Files.writeString(f, "100750\tCIS 3 HEXENOL\tMat.Premières\t111-11-1\t47,4\tNON\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals("111-11-1", s.cas("100750"));
    }
}
