package com.prernayande.carsearch.eval;

import com.prernayande.carsearch.domain.Car;
import com.prernayande.carsearch.domain.CarGroup;
import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.repository.CarRepository;
import com.prernayande.carsearch.service.EmbeddingService;
import com.prernayande.carsearch.service.SearchService;
import com.prernayande.carsearch.service.RankingStrategy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

// Offline evaluation: P@10 and "never" violations for labeled queries.
// R1 = dense retrieval only (no parsing, no full-text, no gate). R3 = the full pipeline as served.
// Run with: mvn spring-boot:run -Dspring-boot.run.profiles=eval
@Component
@Profile("eval")
public class EvalRunner implements CommandLineRunner {

    // A labeling rule over dataset fields. Set fields are AND-ed, values in a list are OR-ed, null = ignored.
    record Rule(List<String> body, List<String> bodyNot, List<String> style, List<String> fuel, List<String> fuelNot,
                List<String> market, List<String> make, List<String> makeNot, List<String> model,
                List<String> transmission, List<String> drivetrain, List<String> size,
                Integer maxMsrp, Integer minMsrp, Integer minMpg) {

        boolean matches(Car c) {
            return in(body, c.bodyType()) && notIn(bodyNot, c.bodyType())
                    && (style == null || (c.vehicleStyle() != null && style.stream().anyMatch(c.vehicleStyle()::contains)))
                    && in(fuel, c.fuel()) && notIn(fuelNot, c.fuel())
                    && (market == null || market.stream().anyMatch(c.marketCategories()::contains))
                    && in(make, c.make()) && notIn(makeNot, c.make()) && in(model, c.model())
                    && in(transmission, c.transmission()) && in(drivetrain, c.drivetrain()) && in(size, c.vehicleSize())
                    && (maxMsrp == null || (c.priceKnown() && c.msrp() <= maxMsrp))
                    && (minMsrp == null || (c.priceKnown() && c.msrp() >= minMsrp))
                    && (minMpg == null || (c.highwayMpg() != null && c.highwayMpg() >= minMpg));
        }

        static boolean in(List<String> allowed, String value) {
            return allowed == null || (value != null && allowed.contains(value));
        }

        static boolean notIn(List<String> excluded, String value) {
            return excluded == null || value == null || !excluded.contains(value);
        }
    }

    // grade 2 = fully relevant, 1 = partly relevant, 0 = not relevant; "never" = violation at any rank
    record Query(String query, List<Rule> grade2, List<Rule> grade1, List<Rule> never) {
        int grade(Car c) {
            if (grade2 != null && grade2.stream().anyMatch(r -> r.matches(c))) return 2;
            if (grade1 != null && grade1.stream().anyMatch(r -> r.matches(c))) return 1;
            return 0;
        }

        boolean violates(Car c) {
            return never != null && never.stream().anyMatch(r -> r.matches(c));
        }
    }

    record QueryFile(List<Query> queries) {}

    private final SearchService search;
    private final EmbeddingService embeddings;
    private final CarRepository cars;
    private final RankingStrategy ranking;
    private final String queriesPath;

    public EvalRunner(SearchService search, EmbeddingService embeddings, CarRepository cars, RankingStrategy ranking,
                      @Value("${carsearch.eval.queries-path:../eval/queries.json}") String queriesPath) {
        this.search = search;
        this.embeddings = embeddings;
        this.cars = cars;
        this.ranking = ranking;
        this.queriesPath = queriesPath;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Query> queries = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(new File(queriesPath), QueryFile.class).queries();
        embeddings.embedAll(List.of("warm up"));   // wait for the model

        Function<String, List<CarGroup>> denseOnly = q -> {
            ParsedQuery bare = new ParsedQuery(q, q.toLowerCase(), "", List.of(), List.of(), Set.of());
            return ranking.rank(cars.findDense(embeddings.embed(q.toLowerCase()), null, bare.filters(), 200), bare);
        };
        Function<String, List<CarGroup>> full = q -> search.search(q, 0, 10_000).results();

        StringBuilder rows = new StringBuilder();
        double p1 = 0, p3 = 0;
        int v1 = 0, v3 = 0;
        for (Query q : queries) {
            List<CarGroup> r1 = denseOnly.apply(q.query());
            List<CarGroup> r3 = full.apply(q.query());
            double qp1 = precisionAt10(q, r1), qp3 = precisionAt10(q, r3);
            int qv1 = violations(q, r1), qv3 = violations(q, r3);
            p1 += qp1; p3 += qp3; v1 += qv1; v3 += qv3;
            rows.append(String.format("| %s | %.2f | %d | %.2f | %d | %d |%n", q.query(), qp1, qv1, qp3, qv3, r3.size()));
        }
        int n = queries.size();
        String report = "## " + Path.of(queriesPath).getFileName() + "\n\n"
                + "| Run | Mean P@10 | Violations |\n| --- | --- | --- |\n"
                + String.format("| R1 dense only | %.3f | %d |%n| R3 full pipeline | %.3f | %d |%n%n", p1 / n, v1, p3 / n, v3)
                + "| Query | R1 P@10 | R1 viol. | R3 P@10 | R3 viol. | R3 groups |\n| --- | --- | --- | --- | --- | --- |\n"
                + rows;
        System.out.println(report);
        Path out = Path.of("../eval/results", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    // share of the top 10 groups with grade >= 1 (over fewer if fewer were returned)
    static double precisionAt10(Query q, List<CarGroup> groups) {
        int k = Math.min(10, groups.size());
        if (k == 0) return 0;
        return groups.subList(0, k).stream().filter(g -> q.grade(g.best().car()) >= 1).count() / (double) k;
    }

    static int violations(Query q, List<CarGroup> groups) {
        return (int) groups.stream().filter(g -> q.violates(g.best().car())).count();
    }
}
