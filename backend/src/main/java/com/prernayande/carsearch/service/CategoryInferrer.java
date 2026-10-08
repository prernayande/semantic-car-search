package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.domain.Op;
import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.domain.SoftConstraint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// If the query names no body type / category, compare its embedding with a short description of each
// category. Close matches become soft, half-weight, never-gated preferences.
// "family car with lots of space" -> SUV or minivan, "something to haul lumber" -> pickup.
@Service
public class CategoryInferrer {

    static final double MIN_SIMILARITY = 0.50;   // chosen; "cheap fuel efficient car" scores 0.499 for Performance and is not inferred
    static final double MARGIN = 0.08;           // also infer categories this close to the best one; widened from 0.05
                                                 // so "family car" gets minivan (0.654) next to SUV (0.706)
    static final int MAX_PER_FIELD = 3;          // more than this is too vague to use

    record Category(Field field, String value, String label, double weight, String description) {}

    static final List<Category> CATEGORIES = List.of(
            new Category(Field.BODY_TYPE, "pickup", "Body: Pickup", Lexicon.W_BODY, "pickup truck with an open cargo bed for hauling, towing and work"),
            new Category(Field.BODY_TYPE, "suv", "Body: SUV", Lexicon.W_BODY, "SUV with high ground clearance, a roomy cabin and space for the whole family"),
            new Category(Field.BODY_TYPE, "minivan", "Body: Minivan", Lexicon.W_BODY, "minivan family hauler with three rows of seats, sliding doors and lots of space for kids"),
            new Category(Field.BODY_TYPE, "wagon", "Body: Wagon", Lexicon.W_BODY, "station wagon with a long roof and a large cargo area for luggage"),
            new Category(Field.BODY_TYPE, "sedan", "Body: Sedan", Lexicon.W_BODY, "four door sedan passenger car for commuting"),
            new Category(Field.BODY_TYPE, "coupe", "Body: Coupe", Lexicon.W_BODY, "two door coupe with a sleek sporty profile"),
            new Category(Field.BODY_TYPE, "convertible", "Body: Convertible", Lexicon.W_BODY, "convertible with a folding soft top roof for open air driving"),
            new Category(Field.BODY_TYPE, "hatchback", "Body: Hatchback", Lexicon.W_BODY, "small hatchback city car that is easy to park"),
            new Category(Field.BODY_TYPE, "van", "Body: Van", Lexicon.W_BODY, "cargo van for commercial deliveries and tradesmen"),
            new Category(Field.MARKET, "Luxury", "Category: Luxury", Lexicon.W_MARKET, "luxury car with a premium leather interior, comfort and prestige"),
            new Category(Field.MARKET, "Performance", "Category: Performance", Lexicon.W_MARKET, "fast high performance sports car with a powerful engine and quick acceleration"),
            new Category(Field.MARKET, "Exotic", "Category: Exotic", Lexicon.W_MARKET, "exotic supercar, rare, extremely expensive and exclusive"));

    private final EmbeddingService embeddings;
    private final boolean enabled;
    private List<float[]> vectors;   // embedded descriptions, same order as CATEGORIES

    public CategoryInferrer(EmbeddingService embeddings,
                            @Value("${carsearch.category-inference:true}") boolean enabled) {
        this.embeddings = embeddings;
        this.enabled = enabled;
    }

    public ParsedQuery enrich(ParsedQuery query, float[] queryVector) {
        if (!enabled) return query;
        if (vectors == null) vectors = embeddings.embedAll(CATEGORIES.stream().map(Category::description).toList());

        List<SoftConstraint> soft = new ArrayList<>(query.soft());
        for (Field field : List.of(Field.BODY_TYPE, Field.MARKET)) {
            if (query.soft().stream().anyMatch(s -> s.field() == field)) continue;   // user already said it

            List<double[]> ranked = new ArrayList<>();   // [category index, similarity]
            for (int i = 0; i < CATEGORIES.size(); i++) {
                Category c = CATEGORIES.get(i);
                if (c.field() == field && !excluded(query, c)) ranked.add(new double[] {i, cosine(queryVector, vectors.get(i))});
            }
            if (ranked.isEmpty()) continue;
            ranked.sort(Comparator.comparingDouble((double[] r) -> r[1]).reversed());
            double best = ranked.get(0)[1];
            if (best < MIN_SIMILARITY) continue;

            List<Category> picked = ranked.stream().filter(r -> r[1] >= best - MARGIN)
                    .map(r -> CATEGORIES.get((int) r[0])).toList();
            if (picked.size() > MAX_PER_FIELD) continue;
            for (Category c : picked) {
                soft.add(new SoftConstraint(c.field(), Op.IN, c.value(), c.weight() / 2, true, c.label()));
            }
        }
        return new ParsedQuery(query.original(), query.normalized(), query.residual(), query.hard(), soft,
                query.gatedFields());
    }

    // the user excluded this category ("not truck"), so never guess it: "ford not truck" used to get an inferred
    // Pickup preference. The other values of the field can still be guessed ("family car not minivan" -> SUV).
    static boolean excluded(ParsedQuery query, Category c) {
        return query.hard().stream().anyMatch(h -> h.field() == c.field() && h.op() == Op.NOT_IN && h.values().contains(c.value()));
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
