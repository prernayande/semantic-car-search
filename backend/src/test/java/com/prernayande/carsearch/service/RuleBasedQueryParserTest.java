package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.domain.HardConstraint;
import com.prernayande.carsearch.domain.Op;
import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.domain.SoftConstraint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedQueryParserTest {

    QueryParser analyzer = new RuleBasedQueryParser();

    @ParameterizedTest
    @ValueSource(strings = {"truck", "trucks", "pickup", "pick-up", "Pickups", "TRUCKS!"})
    void truckSynonymsMapToPickupAndAreGated(String q) {
        ParsedQuery p = analyzer.parse(q);
        assertThat(soft(p)).containsExactly("Body: Pickup");
        assertThat(p.gatedFields()).containsExactly(Field.BODY_TYPE);
        assertThat(p.residual()).isEmpty();
    }

    @Test
    void suvUnder30k() {
        ParsedQuery p = analyzer.parse("SUV under 30k");
        assertThat(p.hard()).containsExactly(
                new HardConstraint(Field.MSRP, Op.LTE, List.of(30000), "Price under $30,000"));
        assertThat(soft(p)).containsExactly("Body: SUV");
    }

    @ParameterizedTest
    @ValueSource(strings = {"below $30,000", "less than 30000", "< 30k", "max 30 thousand"})
    void priceCeilingVariants(String q) {
        assertThat(analyzer.parse(q).hard()).singleElement()
                .satisfies(h -> {
                    assertThat(h.op()).isEqualTo(Op.LTE);
                    assertThat(h.values()).containsExactly(30000);
                });
    }

    @Test
    void priceRanges() {
        assertThat(analyzer.parse("between 20k and 35k").hard().getFirst().values()).containsExactly(20000, 35000);
        assertThat(analyzer.parse("20-35k sedan").hard().getFirst().values()).containsExactly(20000, 35000);
        assertThat(analyzer.parse("around 30k").hard().getFirst().values()).containsExactly(25500, 34500);
        assertThat(analyzer.parse("over 50k").hard().getFirst().op()).isEqualTo(Op.GTE);
    }

    @Test
    void numbersThatAreNotPrices() {
        assertThat(analyzer.parse("over 400 hp").hard()).isEmpty();
        assertThat(analyzer.parse("at least 30 mpg").hard()).isEmpty();
        assertThat(analyzer.parse("2015 civic").hard()).isEmpty();
        assertThat(analyzer.parse("ford f-150").residual()).isEqualTo("f-150");
    }

    @Test
    void electricSuvUnder60k() {
        ParsedQuery p = analyzer.parse("electric suv under 60k");
        assertThat(soft(p)).containsExactlyInAnyOrder("Fuel: Electric", "Body: SUV");
        assertThat(p.gatedFields()).containsExactlyInAnyOrder(Field.BODY_TYPE, Field.FUEL);
        assertThat(p.hard()).hasSize(1);
    }

    @Test
    void makeIsHardFilterWithAliases() {
        ParsedQuery p = analyzer.parse("chevy trucks");
        assertThat(p.hard()).containsExactly(
                new HardConstraint(Field.MAKE, Op.IN, List.of("Chevrolet"), "Make: Chevrolet"));
        assertThat(soft(p)).containsExactly("Body: Pickup");

        assertThat(analyzer.parse("mercedes-benz coupe").hard().getFirst().values()).containsExactly("Mercedes-Benz");
        assertThat(analyzer.parse("vw wagon").hard().getFirst().values()).containsExactly("Volkswagen");
        assertThat(analyzer.parse("benz").hard().getFirst().values()).containsExactly("Mercedes-Benz");

        // only a few common aliases are kept; others fall through to embeddings and full-text search
        ParsedQuery lambo = analyzer.parse("lambo");
        assertThat(lambo.hard()).isEmpty();
        assertThat(lambo.residual()).isEqualTo("lambo");
    }

    @Test
    void modelNamesFlowToResidual() {
        ParsedQuery p = analyzer.parse("Civic");
        assertThat(p.hard()).isEmpty();
        assertThat(p.soft()).isEmpty();
        assertThat(p.residual()).isEqualTo("civic");
    }

    @Test
    void longestTermWins() {
        assertThat(soft(analyzer.parse("sport utility vehicle"))).containsExactly("Body: SUV");
        assertThat(soft(analyzer.parse("minivan"))).containsExactly("Body: Minivan");
        assertThat(soft(analyzer.parse("manual sports car")))
                .containsExactlyInAnyOrder("Transmission: Manual", "Category: Performance");
    }

    @Test
    void subjectiveTermsAreSoftOnly() {
        ParsedQuery p = analyzer.parse("cheap fuel efficient car");
        assertThat(p.hard()).isEmpty();
        assertThat(soft(p)).containsExactlyInAnyOrder("Budget: under $25,000", "Fuel efficient: 30+ mpg highway");
        assertThat(p.gatedFields()).isEmpty();
    }

    @Test
    void negationExcludesTheNextTermOnly() {
        ParsedQuery p = analyzer.parse("suv not electric");
        assertThat(soft(p)).containsExactly("Body: SUV");
        assertThat(p.gatedFields()).containsExactly(Field.BODY_TYPE);
        assertThat(p.hard()).containsExactly(
                new HardConstraint(Field.FUEL, Op.NOT_IN, List.of("electric"), "Exclude Fuel: Electric"));

        ParsedQuery q = analyzer.parse("non-hybrid sedan");
        assertThat(soft(q)).containsExactly("Body: Sedan");
        assertThat(q.hard()).extracting(HardConstraint::label).containsExactly("Exclude Fuel: Hybrid");

        assertThat(analyzer.parse("truck without diesel").hard()).extracting(HardConstraint::label)
                .containsExactly("Exclude Fuel: Diesel");
    }

    @Test
    void offRoadMeansSuvOrPickup() {
        ParsedQuery p = analyzer.parse("off-road 4x4");
        assertThat(soft(p)).containsExactlyInAnyOrder("Body: SUV", "Body: Pickup", "Drivetrain: 4WD");
        assertThat(p.gatedFields()).containsExactly(Field.BODY_TYPE);
        assertThat(p.residual()).isEmpty();
    }

    @Test
    void fuzzyQueryKeepsMeaningfulResidual() {
        ParsedQuery p = analyzer.parse("family car with lots of space");
        assertThat(p.soft()).isEmpty();
        assertThat(p.residual()).isEqualTo("family space");
        assertThat(p.normalized()).isEqualTo("family car with lots of space");
    }

    private static List<String> soft(ParsedQuery p) {
        return p.soft().stream().map(SoftConstraint::label).toList();
    }
}
