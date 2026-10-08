package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Candidate;
import com.prernayande.carsearch.domain.Car;
import com.prernayande.carsearch.domain.CarGroup;
import com.prernayande.carsearch.domain.ScoredCar;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WeightedRankingTest {

    WeightedRanking ranking = new WeightedRanking();
    QueryParser parser = new RuleBasedQueryParser();

    static Car car(int id, String model, int year, String body, Integer msrp) {
        return new Car(id, "Ford", model, year, null, "gasoline", 200, 4, "AUTOMATIC", "fwd", 4, List.of(), "Midsize",
                null, body, 30, 22, 100, msrp, msrp != null);
    }

    @Test
    void scoresAreScaledAndWeighted() {
        // "suv" has a preference and no leftover words: 0.65 semantic + 0.35 constraints
        List<ScoredCar> s = ranking.score(List.of(
                new Candidate(car(1, "Escape", 2015, "suv", 25000), 0.60, 0),
                new Candidate(car(2, "Fusion", 2015, "sedan", 22000), 0.40, 0)), parser.parse("suv"));
        assertThat(s.get(0).finalScore()).isCloseTo(1.0, within(1e-9));
        assertThat(s.get(0).matched()).containsExactly("Body: SUV");
        assertThat(s.get(1).semantic()).isCloseTo(0.40 / 0.60, within(1e-9));   // divided by max
        assertThat(s.get(1).constraints()).isEqualTo(0.0);                      // contradicts -> 0
        assertThat(s.get(1).finalScore()).isCloseTo(0.65 * 0.40 / 0.60, within(1e-9));
    }

    @Test
    void lexicalSignalCountsWhenThereAreLeftoverWords() {
        List<ScoredCar> s = ranking.score(List.of(
                new Candidate(car(1, "Civic", 2015, "sedan", 20000), 0.50, 0.4),
                new Candidate(car(2, "Accord", 2015, "sedan", 24000), 0.50, 0.0)), parser.parse("civic"));
        assertThat(s.get(0).finalScore()).isCloseTo(1.0, within(1e-9));
        assertThat(s.get(1).finalScore()).isCloseTo(0.70, within(1e-9));
    }

    @Test
    void cutoffDropsWeakTail() {
        List<CarGroup> groups = ranking.rank(List.of(
                new Candidate(car(1, "Flex", 2015, "suv", 30000), 0.60, 0),
                new Candidate(car(2, "Focus", 2015, "sedan", 18000), 0.58, 0),
                new Candidate(car(3, "GT", 2015, "coupe", 400000), 0.20, 0)), parser.parse("family car"));
        assertThat(groups).extracting(g -> g.best().car().model()).containsExactly("Flex", "Focus");
    }

    @Test
    void groupsTrimsByMakeModelYear() {
        List<CarGroup> groups = ranking.rank(List.of(
                new Candidate(car(1, "F-150", 2016, "pickup", 30000), 0.62, 0),
                new Candidate(car(2, "F-150", 2016, "pickup", 45000), 0.60, 0),
                new Candidate(car(3, "F-150", 2015, "pickup", 28000), 0.61, 0),
                new Candidate(car(4, "Ranger", 2011, "pickup", null), 0.55, 0)), parser.parse("truck"));
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).best().car().id()).isEqualTo(1);
        assertThat(groups.get(0).trimCount()).isEqualTo(2);
        assertThat(groups.get(0).msrpMin()).isEqualTo(30000);
        assertThat(groups.get(0).msrpMax()).isEqualTo(45000);
    }

    @Test
    void diversitySpreadsNearTies() {
        List<CarGroup> groups = ranking.rank(List.of(
                new Candidate(car(1, "Sierra", 2016, "pickup", 30000), 0.600, 0),
                new Candidate(car(2, "Sierra", 2017, "pickup", 31000), 0.599, 0),
                new Candidate(car(3, "Sierra", 2015, "pickup", 29000), 0.598, 0),
                new Candidate(car(4, "Tundra", 2016, "pickup", 32000), 0.590, 0)), parser.parse("truck"));
        // without diversity the order would be Sierra, Sierra, Sierra, Tundra
        assertThat(groups).extracting(g -> g.best().car().model()).containsExactly("Sierra", "Tundra", "Sierra", "Sierra");
        assertThat(groups).extracting(CarGroup::score).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(groups.get(1).diversityPenalty()).isCloseTo(0.015, within(1e-9));   // same make, other model
    }

    @Test
    void tiesBreakByLowerPrice() {
        List<CarGroup> groups = ranking.rank(List.of(
                new Candidate(car(5, "F-250", 2016, "pickup", 40000), 0.60, 0),
                new Candidate(car(6, "F-150", 2016, "pickup", 30000), 0.60, 0)), parser.parse("truck"));
        assertThat(groups).extracting(g -> g.best().car().model()).containsExactly("F-150", "F-250");
    }
}
