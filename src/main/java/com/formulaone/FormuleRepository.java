package com.formulaone;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acces en lecture seule aux tables FORMULE et FORMULEDET de maBase.
 * Toutes les valeurs saisies par l'utilisateur passent par des parametres JDBC (pas de concatenation SQL).
 */
@Repository
public class FormuleRepository {

    private final JdbcTemplate jdbc;

    public FormuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Neutralise les caracteres speciaux de LIKE pour que la saisie soit prise litteralement. */
    private static String likePattern(String q) {
        String escaped = q.replace("\\", "\\\\")
                          .replace("%", "\\%")
                          .replace("_", "\\_")
                          .replace("[", "\\[");
        return "%" + escaped + "%";
    }

    public long count(String q) {
        String like = likePattern(q);
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.FORMULE
                WHERE (? = '' OR FORMULE_LIB LIKE ? ESCAPE '\\' OR FORMULE_CD LIKE ? ESCAPE '\\')
                """, Long.class, q, like, like);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> search(String q, int page, int size) {
        String like = likePattern(q);
        return jdbc.queryForList("""
                SELECT FORMULE_KEY, SOURCE, FORMULE_CD, FORMULE_LIB, POIDS_TOTAL, PRIX, LAST_MODIFICATION
                FROM dbo.FORMULE
                WHERE (? = '' OR FORMULE_LIB LIKE ? ESCAPE '\\' OR FORMULE_CD LIKE ? ESCAPE '\\')
                ORDER BY FORMULE_LIB, FORMULE_KEY
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, q, like, like, page * size, size);
    }

    public List<Map<String, Object>> findByKey(String key) {
        return jdbc.queryForList("""
                SELECT FORMULE_KEY, SOURCE, FORMULE_CD, FORMULE_LIB, POIDS_TOTAL, PRIX, PRIX_JMA,
                       LAST_MODIFICATION, CREATION_AMJ
                FROM dbo.FORMULE WHERE FORMULE_KEY = ?
                """, key);
    }

    public List<Map<String, Object>> lines(String key) {
        return jdbc.queryForList("""
                SELECT MATIERE_CD, MATIERE_LIB, FORMULE_POIDS
                FROM dbo.FORMULEDET WHERE FORMULE_KEY = ?
                ORDER BY MATIERE_LIB
                """, key);
    }
}
