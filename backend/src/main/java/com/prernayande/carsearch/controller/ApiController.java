package com.prernayande.carsearch.controller;

import com.prernayande.carsearch.controller.dto.ErrorResponse;
import com.prernayande.carsearch.controller.dto.HealthResponse;
import com.prernayande.carsearch.controller.dto.SearchResponse;
import com.prernayande.carsearch.service.EmbeddingService;
import com.prernayande.carsearch.service.LatestService;
import com.prernayande.carsearch.service.SearchService;
import com.prernayande.carsearch.service.SuggestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ApiController {

    private final SearchService search;
    private final EmbeddingService embeddings;
    private final SuggestService suggest;
    private final LatestService latest;

    public ApiController(SearchService search, EmbeddingService embeddings, SuggestService suggest, LatestService latest) {
        this.search = search;
        this.embeddings = embeddings;
        this.suggest = suggest;
        this.latest = latest;
    }

    // search-as-you-type: at most 8 completions for what is typed so far
    @GetMapping("/api/suggest")
    public List<String> suggest(@RequestParam(defaultValue = "") String q) {
        return q.length() > 200 ? List.of() : suggest.suggest(q);
    }

    @GetMapping("/api/search")
    public ResponseEntity<?> search(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        q = q.trim();
        if (q.isEmpty() || q.length() > 200) return error(400, "q must be 1 to 200 characters");
        if (page < 0 || size < 1 || size > 50) return error(400, "page must be >= 0 and size 1 to 50");
        if (!embeddings.isReady()) return error(503, "Embedding model is still loading");   // UI shows "starting up" and retries
        return ResponseEntity.ok(SearchResponse.from(search.search(q, page, size)));
    }

    // shown before anything is searched; no embeddings, so it works while the model is still loading
    @GetMapping("/api/latest")
    public ResponseEntity<?> latest(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 50) return error(400, "page must be >= 0 and size 1 to 50");
        return ResponseEntity.ok(SearchResponse.from(latest.page(page, size)));
    }

    // health check for the host and the UI; does not touch the database
    @GetMapping("/api/health")
    public HealthResponse health() {
        String model = embeddings.isReady() ? "READY" : embeddings.hasFailed() ? "FAILED" : "LOADING";
        return new HealthResponse("UP", model);
    }

    private static ResponseEntity<ErrorResponse> error(int status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}
