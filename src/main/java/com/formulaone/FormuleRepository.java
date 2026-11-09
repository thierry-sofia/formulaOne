package com.formulaone;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * Motif LIKE de la recherche. Si la saisie contient un joker SQL (% ou _), elle est utilisee telle quelle
     * ("toto%" = commence par toto, "%toto" = finit par toto, "t_to" = un caractere quelconque).
     * Sinon, recherche "contient" (%saisie%). Dans les deux cas, [ et \ restent litteraux.
     */
    static String likePattern(String q) {
        String escaped = q.replace("\\", "\\\\").replace("[", "\\[");
        boolean joker = q.indexOf('%') >= 0 || q.indexOf('_') >= 0;
        return joker ? escaped : "%" + escaped + "%";
    }

    /** Sources distinctes (liste deroulante), sans les espaces de remplissage des colonnes CHAR. */
    public List<String> sources(Collection<String> autorisees) {
        List<Object> params = new ArrayList<>();
        String filtre = clauseSources(autorisees, params);
        return jdbc.queryForList(
                "SELECT DISTINCT RTRIM(SOURCE) FROM dbo.FORMULE WHERE SOURCE IS NOT NULL" + filtre + " ORDER BY 1",
                String.class, params.toArray());
    }

    /**
     * Restriction aux sources autorisees d'un compte (null = aucune restriction). Les valeurs passent par des
     * parametres JDBC ; seul le nombre de "?" est ecrit dans le SQL.
     */
    private static String clauseSources(Collection<String> autorisees, List<Object> params) {
        if (autorisees == null) {
            return "";
        }
        if (autorisees.isEmpty()) {
            return " AND 1 = 0";
        }
        params.addAll(autorisees);
        return " AND RTRIM(SOURCE) IN (" + String.join(",", java.util.Collections.nCopies(autorisees.size(), "?")) + ")";
    }

    public long count(String q, String source, int maxLignes, Collection<String> autorisees) {
        String like = likePattern(q);
        List<Object> params = new ArrayList<>(List.of(q, like, like, source, source, maxLignes, maxLignes));
        String filtre = clauseSources(autorisees, params);
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.FORMULE
                WHERE (? = '' OR LTRIM(RTRIM(FORMULE_LIB)) LIKE ? ESCAPE '\\' OR RTRIM(FORMULE_CD) LIKE ? ESCAPE '\\')
                  AND (? = '' OR SOURCE = ?)
                  AND (? = 0 OR (SELECT COUNT(*) FROM dbo.FORMULEDET d WHERE d.FORMULE_KEY = dbo.FORMULE.FORMULE_KEY) <= ?)
                """ + filtre, Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    /** Colonnes de tri autorisees (liste blanche : jamais de texte de l'utilisateur dans le SQL). */
    private static final Map<String, String> TRIS = Map.of(
            "nom", "LTRIM(RTRIM(FORMULE_LIB))",
            "code", "RTRIM(FORMULE_CD)",
            "modif", "LAST_MODIFICATION",
            "prix", "PRIX");

    public List<Map<String, Object>> search(String q, String source, int maxLignes, Collection<String> autorisees,
                                            String sort, boolean desc, int page, int size) {
        String like = likePattern(q);
        String colonne = TRIS.getOrDefault(sort, TRIS.get("nom"));
        String ordre = colonne + (desc ? " DESC" : " ASC") + ", FORMULE_KEY";
        List<Object> params = new ArrayList<>(List.of(q, like, like, source, source, maxLignes, maxLignes));
        String filtre = clauseSources(autorisees, params);
        params.add(page * size);
        params.add(size);
        return jdbc.queryForList("""
                SELECT FORMULE_KEY, RTRIM(SOURCE) AS SOURCE, RTRIM(FORMULE_CD) AS FORMULE_CD,
                       LTRIM(RTRIM(FORMULE_LIB)) AS FORMULE_LIB, POIDS_TOTAL, PRIX, LAST_MODIFICATION,
                       TotalVendu, PrixMoyenVendu, CoutProductionMoyen, RTRIM(APPLICATION) AS APPLICATION,
                       (SELECT COUNT(*) FROM dbo.FORMULEDET d WHERE d.FORMULE_KEY = dbo.FORMULE.FORMULE_KEY) AS NB_INGREDIENTS
                FROM dbo.FORMULE
                WHERE (? = '' OR LTRIM(RTRIM(FORMULE_LIB)) LIKE ? ESCAPE '\\' OR RTRIM(FORMULE_CD) LIKE ? ESCAPE '\\')
                  AND (? = '' OR SOURCE = ?)
                  AND (? = 0 OR (SELECT COUNT(*) FROM dbo.FORMULEDET d WHERE d.FORMULE_KEY = dbo.FORMULE.FORMULE_KEY) <= ?)
                %s
                ORDER BY %s
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """.formatted(filtre, ordre), params.toArray());
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
