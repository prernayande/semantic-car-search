package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Candidate;
import com.prernayande.carsearch.domain.Car;
import com.prernayande.carsearch.domain.CarGroup;
import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.domain.ScoredCar;
import com.prernayande.carsearch.domain.SoftConstraint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// score = alpha * semantic + beta * lexical + gamma * constraints  ->  cutoff  ->  group trims  ->  diversify
// (the category gate already ran in SQL)
@Service
public class WeightedRanking implements RankingStrategy {

    // weights: chosen in the initial design, not tuned
    static final double[] WEIGHTS_WITH_PREFS = {0.45, 0.20, 0.35};   // semantic, lexical, constraints
    static final double[] WEIGHTS_NO_PREFS = {0.70, 0.30, 0.0};      // e.g. "Civic"
    static final double MIN_SIMILARITY = 0.25;    // cosine floor, waived for text matches and full constraint matches
    static final double RELATIVE_CUTOFF = 0.60;   // keep rows >= 60% of the top score; raised from 0.55 on the eval set
    static final double MAKE_PENALTY = 0.015;     // diversity: per higher-ranked group of the same make
    static final double MODEL_PENALTY = 0.03;     // diversity: extra, per higher-ranked group of the same model
    static final double MAX_PENALTY = 0.10;

    // best trim first: score, then cheaper, then newer, then id
    static final Comparator<ScoredCar> TRIM_ORDER = Comparator.comparingDouble(ScoredCar::finalScore).reversed()
            .thenComparing(s -> s.car().msrp(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(s -> s.car().year(), Comparator.reverseOrder())
            .thenComparing(s -> s.car().id(), Comparator.nullsLast(Comparator.naturalOrder()));

    static final Comparator<CarGroup> GROUP_ORDER = Comparator.comparingDouble(CarGroup::score).reversed()
            .thenComparing(CarGroup::msrpMin, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(g -> g.best().car().year(), Comparator.reverseOrder())
            .thenComparing(g -> g.best().car().id(), Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    public List<CarGroup> rank(List<Candidate> candidates, ParsedQuery query) {
        return diversify(group(cutoff(score(candidates, query), query)));
    }

    List<ScoredCar> score(List<Candidate> candidates, ParsedQuery query) {
        double[] w = query.hasSoft() ? WEIGHTS_WITH_PREFS : WEIGHTS_NO_PREFS;
        double alpha = w[0], beta = w[1], gamma = w[2];
        if (!query.hasResidual()) {   // no words left for full-text search: give that weight to semantic
            alpha += beta;
            beta = 0;
        }

        // semantic is divided by the max (not min-max): after the gate all candidates are similar,
        // and min-max would stretch tiny differences to the full 0..1 range
        double maxSem = candidates.stream().mapToDouble(Candidate::semantic).max().orElse(0);
        double maxLex = candidates.stream().mapToDouble(Candidate::lexical).max().orElse(0);

        Map<Field, List<SoftConstraint>> prefs = query.softByField();
        double totalWeight = 0;
        for (List<SoftConstraint> group : prefs.values()) totalWeight += group.get(0).weight();

        List<ScoredCar> out = new ArrayList<>();
        for (Candidate c : candidates) {
            double sem = maxSem > 0 ? Math.max(0, c.semantic()) / maxSem : 0;
            double lex = maxLex > 0 ? c.lexical() / maxLex : 0;

            // +weight per matched preference, -weight per contradicted one, mapped from [-1, 1] to [0, 1]
            double sum = 0;
            List<String> matched = new ArrayList<>();
            for (List<SoftConstraint> group : prefs.values()) {
                int m = matchAny(c.car(), group);
                sum += m * group.get(0).weight();
                if (m == 1) {
                    group.stream().filter(s -> match(c.car(), s) == 1).findFirst().ifPresent(s -> matched.add(s.label()));
                }
            }
            double con = totalWeight > 0 ? (sum / totalWeight + 1) / 2 : 0;

            out.add(new ScoredCar(c.car(), c.semantic(), sem, lex, con, alpha * sem + beta * lex + gamma * con, matched));
        }
        return out;
    }

    List<ScoredCar> cutoff(List<ScoredCar> scored, ParsedQuery query) {
        double top = scored.stream().mapToDouble(ScoredCar::finalScore).max().orElse(0);
        long explicitFields = query.soft().stream().filter(s -> !s.inferred()).map(SoftConstraint::field).distinct().count();
        // A pure name search ("Civic", "M3", "range rover": no preferences) where some cars literally contain the
        // typed words: show only those cars. If nothing matches literally, fall back to meaning (embeddings).
        if (!query.hasSoft() && scored.stream().anyMatch(s -> s.lexical() > 0)) {
            scored = scored.stream().filter(s -> s.lexical() > 0).toList();
        }
        // the similarity floor is waived for rows that literally contain the typed words (s.lexical() > 0):
        // a short word like "M3" has too little meaning for the embedding but is still a clear request
        return scored.stream()
                .filter(s -> s.rawSemantic() >= MIN_SIMILARITY || s.lexical() > 0
                        || (explicitFields > 0 && s.matched().size() >= explicitFields))
                .filter(s -> s.finalScore() >= RELATIVE_CUTOFF * top)
                .toList();
    }

    // one group per make + model + year, represented by its best trim
    static List<CarGroup> group(List<ScoredCar> rows) {
        Map<String, List<ScoredCar>> byModel = new LinkedHashMap<>();
        for (ScoredCar s : rows) {
            byModel.computeIfAbsent(s.car().make() + "|" + s.car().model() + "|" + s.car().year(), k -> new ArrayList<>()).add(s);
        }
        List<CarGroup> groups = new ArrayList<>();
        for (List<ScoredCar> trims : byModel.values()) {
            trims.sort(TRIM_ORDER);
            List<Integer> prices = trims.stream().map(ScoredCar::car).filter(Car::priceKnown)
                    .map(Car::msrp).filter(Objects::nonNull).toList();
            Integer min = prices.stream().min(Integer::compare).orElse(null);
            Integer max = prices.stream().max(Integer::compare).orElse(null);
            groups.add(new CarGroup(trims.get(0), trims.size(), min, max, 0, trims.get(0).finalScore()));
        }
        groups.sort(GROUP_ORDER);
        return groups;
    }

    // Greedy re-rank: repeating a make or model costs a little, so near-ties ("trucks": all pickups score
    // 0.9-1.0) spread across makes instead of showing three years of one truck.
    static List<CarGroup> diversify(List<CarGroup> sorted) {
        List<CarGroup> remaining = new ArrayList<>(sorted);
        List<CarGroup> out = new ArrayList<>();
        Map<String, Integer> makes = new HashMap<>();
        Map<String, Integer> models = new HashMap<>();
        while (!remaining.isEmpty()) {
            CarGroup best = null;
            for (CarGroup g : remaining) {
                Car c = g.best().car();
                double penalty = Math.min(MAX_PENALTY, MAKE_PENALTY * makes.getOrDefault(c.make(), 0)
                        + MODEL_PENALTY * models.getOrDefault(c.make() + "|" + c.model(), 0));
                double score = g.best().finalScore() - penalty;
                if (best == null || score > best.score()) {   // strict ">" keeps the original order on ties
                    best = new CarGroup(g.best(), g.trimCount(), g.msrpMin(), g.msrpMax(), penalty, score);
                }
            }
            CarGroup picked = best;
            remaining.removeIf(g -> g.best() == picked.best());
            out.add(picked);
            makes.merge(picked.best().car().make(), 1, Integer::sum);
            models.merge(picked.best().car().make() + "|" + picked.best().car().model(), 1, Integer::sum);
        }
        return out;
    }

    // values of one field are alternatives: any match -> 1, all contradict -> -1, else 0
    static int matchAny(Car car, List<SoftConstraint> group) {
        boolean allContradict = true;
        for (SoftConstraint s : group) {
            int m = match(car, s);
            if (m == 1) return 1;
            if (m != -1) allContradict = false;
        }
        return allContradict ? -1 : 0;
    }

    // 1 = car matches, -1 = car contradicts, 0 = no data. Same rules as CarRepository.condition() in SQL.
    static int match(Car car, SoftConstraint s) {
        Object v = s.value();
        List<String> cats = car.marketCategories();
        return switch (s.field()) {
            case BODY_TYPE -> eq(car.bodyType(), v);
            case DRIVETRAIN -> eq(car.drivetrain(), v);
            case TRANSMISSION -> eq(car.transmission(), v);
            case SIZE -> eq(car.vehicleSize(), v);
            case MAKE -> eq(car.make(), v);
            case FUEL -> switch ((String) v) {
                case "hybrid" -> cats.contains("Hybrid") ? 1 : -1;
                case "diesel" -> "diesel".equals(car.fuel()) || cats.contains("Diesel") ? 1 : car.fuel() == null ? 0 : -1;
                default -> eq(car.fuel(), v);
            };
            case MARKET -> cats.isEmpty() ? 0
                    : "Performance".equals(v) ? (cats.contains("Performance") || cats.contains("High-Performance") ? 1 : -1)
                    : (cats.contains(v) ? 1 : -1);
            case MSRP -> !car.priceKnown() || car.msrp() == null ? 0 : car.msrp() <= ((Number) v).intValue() ? 1 : -1;
            case HIGHWAY_MPG -> car.highwayMpg() == null ? 0 : car.highwayMpg() >= ((Number) v).intValue() ? 1 : -1;
        };
    }

    static int eq(String actual, Object expected) {
        return actual == null ? 0 : actual.equals(expected) ? 1 : -1;
    }
}
