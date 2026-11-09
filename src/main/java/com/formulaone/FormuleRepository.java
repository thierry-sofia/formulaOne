package com.formulaone;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** Sources distinctes (liste deroulante), sans les espaces de remplissage des colonnes CHAR. */
    public List<String> sources() {
        return jdbc.queryForList("""
                SELECT DISTINCT RTRIM(SOURCE) FROM dbo.FORMULE WHERE SOURCE IS NOT NULL ORDER BY 1
                """, String.class);
    }

    public long count(String q, String source, int maxLignes) {
        String like = likePattern(q);
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.FORMULE
                WHERE (? = '' OR FORMULE_LIB LIKE ? ESCAPE '\\' OR FORMULE_CD LIKE ? ESCAPE '\\')
                  AND (? = '' OR SOURCE = ?)
                  AND (? = 0 OR (SELECT COUNT(*) FROM dbo.FORMULEDET d WHERE d.FORMULE_KEY = dbo.FORMULE.FORMULE_KEY) <= ?)
                """, Long.class, q, like, like, source, source, maxLignes, maxLignes);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> search(String q, String source, int maxLignes, int page, int size) {
        String like = likePattern(q);
        return jdbc.queryForList("""
                SELECT FORMULE_KEY, RTRIM(SOURCE) AS SOURCE, RTRIM(FORMULE_CD) AS FORMULE_CD,
                       LTRIM(RTRIM(FORMULE_LIB)) AS FORMULE_LIB, POIDS_TOTAL, PRIX, LAST_MODIFICATION,
                       TotalVendu, PrixMoyenVendu, CoutProductionMoyen, RTRIM(APPLICATION) AS APPLICATION,
                       (SELECT COUNT(*) FROM dbo.FORMULEDET d WHERE d.FORMULE_KEY = dbo.FORMULE.FORMULE_KEY) AS NB_INGREDIENTS
                FROM dbo.FORMULE
                WHERE (? = '' OR FORMULE_LIB LIKE ? ESCAPE '\\' OR FORMULE_CD LIKE ? ESCAPE '\\')
                  AND (? = '' OR SOURCE = ?)
                  AND (? = 0 OR (SELECT COUNT(*) FROM dbo.FORMULEDET d WHERE d.FORMULE_KEY = dbo.FORMULE.FORMULE_KEY) <= ?)
                ORDER BY FORMULE_LIB, FORMULE_KEY
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, q, like, like, source, source, maxLignes, maxLignes, page * size, size);
    }

    public List<Map<String, Object>> findByKey(String key) {
        return jdbc.queryForList("""
                SELECT FORMULE_KEY, RTRIM(SOURCE) AS SOURCE, RTRIM(FORMULE_CD) AS FORMULE_CD,
                       LTRIM(RTRIM(FORMULE_LIB)) AS FORMULE_LIB, POIDS_TOTAL, PRIX, PRIX_JMA,
                       LAST_MODIFICATION, CREATION_AMJ,
                       TotalVendu, PrixMoyenVendu, CoutProductionMoyen, RTRIM(APPLICATION) AS APPLICATION, NOTES
                FROM dbo.FORMULE WHERE FORMULE_KEY = ?
                """, key);
    }

    /** Somme des poids de la composition (calculee par la base, arrondie a 4 decimales). */
    public BigDecimal sumPoids(String key) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT SUM(FORMULE_POIDS) FROM dbo.FORMULEDET WHERE FORMULE_KEY = ?", BigDecimal.class, key);
        return sum == null ? BigDecimal.ZERO : sum.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public List<Map<String, Object>> lines(String key) {
        return jdbc.queryForList("""
                SELECT MATIERE_CD, MATIERE_LIB, FORMULE_POIDS
                FROM dbo.FORMULEDET WHERE FORMULE_KEY = ?
                ORDER BY MATIERE_LIB
                """, key);
    }
}
