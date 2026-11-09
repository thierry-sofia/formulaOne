package com.formulaone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
        result.put("total", repo.count(term, src));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("items", repo.search(term, src, safePage, safeSize));
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
        result.put("lignes", repo.lines(key));
        return result;
    }
}
