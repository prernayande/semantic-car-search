package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Candidate;
import com.prernayande.carsearch.domain.CarGroup;
import com.prernayande.carsearch.domain.Filters;
import com.prernayande.carsearch.domain.LatestGroup;
import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.domain.ScoredCar;
import com.prernayande.carsearch.repository.CarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// parse -> embed -> (infer category) -> retrieve dense + lexical -> rank -> page
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    static final int DENSE_LIMIT = 500;            // nearest neighbours for open-ended queries; 200 gave only 19 groups for "trucks"
    static final int FILTERED_DENSE_LIMIT = 2000;  // when filters already narrow the set (e.g. 1,597 pickups), so "trucks"
                                                   // shows all of them; still capped for Render's 512 MB / 0.1 CPU
    static final int LEXICAL_LIMIT = 200;   // full-text matches per query

    // ranked = false when the results are a plain filtered list (newest first) with no relevance scores
    public record SearchResult(ParsedQuery parsed, int totalGroups, int page, int size, List<CarGroup> results,
                               boolean ranked, String message, long tookMs) {}

    private final QueryParser parser;
    private final EmbeddingService embeddings;
    private final CategoryInferrer inferrer;
    private final RankingStrategy ranking;
    private final CarRepository cars;

    public SearchService(QueryParser parser, EmbeddingService embeddings, CategoryInferrer inferrer,
                         RankingStrategy ranking, CarRepository cars) {
        this.parser = parser;
        this.embeddings = embeddings;
        this.inferrer = inferrer;
        this.ranking = ranking;
        this.cars = cars;
    }

    public SearchResult search(String query, int page, int size) {
        long start = System.currentTimeMillis();
        ParsedQuery parsed = parser.parse(query);
        if (!parsed.hasSoft() && !parsed.hasResidual()) return filterOnly(parsed, page, size, start);
        float[] vector = embeddings.embed(parsed.normalized());
        parsed = inferrer.enrich(parsed, vector);

        // both sources use the same WHERE (price, make, gates, exclusions); merged by car id
        Filters filters = parsed.filters();
        String lexicalQuery = parsed.hasResidual() ? String.join(" or ", parsed.residual().split(" ")) : null;
        Map<Integer, Candidate> candidates = new LinkedHashMap<>();
        for (Candidate c : cars.findDense(vector, lexicalQuery, filters, denseLimit(filters))) candidates.put(c.car().id(), c);
        if (lexicalQuery != null) {
            for (Candidate c : cars.findLexical(vector, lexicalQuery, filters, LEXICAL_LIMIT)) candidates.putIfAbsent(c.car().id(), c);
        }

        List<CarGroup> groups = ranking.rank(new ArrayList<>(candidates.values()), parsed);
        String message = groups.isEmpty() ? explainEmpty(parsed) : null;
        int from = Math.min(page * size, groups.size());
        List<CarGroup> pageOfResults = groups.subList(from, Math.min(from + size, groups.size()));

        long tookMs = System.currentTimeMillis() - start;
        log.info("search query=\"{}\" groups={} tookMs={}", query, groups.size(), tookMs);
        return new SearchResult(parsed, groups.size(), page, size, pageOfResults, true, message, tookMs);
    }

    // Only filters, no words to rank by ("under 30k", "not electric", "ford"): every car that passes is equally
    // relevant, so list them all, newest first. Ranking by the embedding of "under 30k" would be meaningless, and
    // the similarity floor would drop nearly every car. Category inference is skipped for the same reason.
    SearchResult filterOnly(ParsedQuery parsed, int page, int size, long start) {
        List<LatestGroup> all = cars.latest(parsed.filters());
        int from = Math.min(page * size, all.size());
        List<CarGroup> pageOfResults = all.subList(from, Math.min(from + size, all.size())).stream()
                .map(g -> new CarGroup(new ScoredCar(g.car(), 0, 0, 0, 0, 0, List.of()), g.trimCount(),
                        g.msrpMin(), g.msrpMax(), 0, 0))
                .toList();
        String message = all.isEmpty() ? explainEmpty(parsed) : "Every car matching the filters, newest first.";
        long tookMs = System.currentTimeMillis() - start;
        log.info("search query=\"{}\" groups={} tookMs={} (filters only)", parsed.original(), all.size(), tookMs);
        return new SearchResult(parsed, all.size(), page, size, pageOfResults, false, message, tookMs);
    }

    static int denseLimit(Filters filters) {
        return filters.isEmpty() ? DENSE_LIMIT : FILTERED_DENSE_LIMIT;
    }

    // say why nothing came back: name the filter whose removal brings back the most cars
    String explainEmpty(ParsedQuery parsed) {
        Filters filters = parsed.filters();
        if (filters.isEmpty() || cars.count(filters) > 0) {
            return "No cars matched \"" + parsed.original() + "\" closely enough. Try different wording.";
        }
        String best = null;
        int bestCount = 0;
        for (Filters.Relaxation r : filters.relaxations()) {
            int n = cars.count(r.filters());
            if (n > bestCount) {
                best = r.removedLabel();
                bestCount = n;
            }
        }
        if (best != null) return "No cars match all filters. Removing '" + best + "' would return " + bestCount + " cars.";
        return "No cars match these filters, and removing any one of them would not help either: "
                + filters.relaxations().stream().map(Filters.Relaxation::removedLabel).collect(Collectors.joining(", ")) + ".";
    }
}
