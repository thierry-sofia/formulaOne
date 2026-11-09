package com.formulaone;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/formules")
public class FormuleController {

    private static final int MAX_SIZE = 100;

    private final FormuleRepository repo;

    public FormuleController(FormuleRepository repo) {
        this.repo = repo;
    }

    /** Sources disponibles (liste deroulante de la recherche). */
    @GetMapping("/sources")
    public List<String> sources() {
        return repo.sources();
    }

    /** Recherche paginee par libelle ou code de formule, filtree par source si elle est donnee. */
    @GetMapping
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "") String source,
                                      @RequestParam(defaultValue = "0") int maxLignes,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size) {
        String term = q.trim();
        if (term.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recherche trop longue");
        }
        String src = source.trim();
        if (src.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source invalide");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", repo.count(term, src, Math.max(maxLignes, 0)));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("items", repo.search(term, src, Math.max(maxLignes, 0), safePage, safeSize));
        return result;
    }

    /** Detail d'une formule : en-tete et composition. */
    @GetMapping("/{key}")
    public Map<String, Object> detail(@PathVariable String key) {
        List<Map<String, Object>> found = repo.findByKey(key);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Formule introuvable");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formule", found.get(0));
        List<Map<String, Object>> lignes = repo.lines(key);
        result.put("lignes", lignes);
        result.put("nbIngredients", lignes.size());
        result.put("sommePoids", repo.sumPoids(key));
        return result;
    }

    /** Formule au format CSV (separateur ';', UTF-8 avec BOM : s'ouvre directement dans Excel en francais). */
    @GetMapping("/{key}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String key) {
        List<Map<String, Object>> found = repo.findByKey(key);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Formule introuvable");
        }
        Map<String, Object> f = found.get(0);
        String cd = text(f.get("FORMULE_CD"));
        String lib = text(f.get("FORMULE_LIB"));

        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Code formule;Libellé formule;Code matière;Matière;Poids\r\n");
        for (Map<String, Object> l : repo.lines(key)) {
            sb.append(cell(cd)).append(';').append(cell(lib)).append(';')
              .append(cell(text(l.get("MATIERE_CD")))).append(';').append(cell(text(l.get("MATIERE_LIB")))).append(';')
              .append(number(l.get("FORMULE_POIDS"))).append("\r\n");
        }
        sb.append(cell(cd)).append(';').append(cell(lib)).append(";;TOTAL;")
          .append(number(repo.sumPoids(key))).append("\r\n");

        String base = safeName(cd.isBlank() ? key : cd);
        String nom = safeName(lib);
        String fileName = "formule_" + base + (nom.isEmpty() ? "" : "_" + nom) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Partie de nom de fichier : accents retires, caracteres hors [A-Za-z0-9.-] remplaces par _, 60 caracteres au plus. */
    private static String safeName(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9.-]+", "_")
                .replaceAll("^_+|_+$", "");
        return n.length() > 60 ? n.substring(0, 60).replaceAll("_+$", "") : n;
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** Nombre au format francais (virgule decimale), sans notation scientifique. */
    private static String number(Object o) {
        if (o == null) {
            return "";
        }
        BigDecimal n = o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
        return n.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Cellule CSV : guillemets doubles echappes, et neutralisation des formules (=, +, -, @) pour Excel. */
    private static String cell(String s) {
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
