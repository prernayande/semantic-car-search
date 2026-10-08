package com.prernayande.carsearch.service;

import com.prernayande.carsearch.repository.CarRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestServiceTest {

    // fake repository: a few names instead of the database
    SuggestService suggest = new SuggestService(new CarRepository(null) {
        @Override
        public List<String[]> makesAndModels() {
            return List.of(new String[] {"Ford", "F-150"}, new String[] {"Ford", "F-250"},
                    new String[] {"GMC", "Sierra 1500"}, new String[] {"GMC", "Sierra 1500HD"});
        }
    });

    @Test
    void suggestsMakesModelsAndWords() {
        assertThat(suggest.suggest("fo")).startsWith("Ford", "Ford F-150", "Ford F-250");
        assertThat(suggest.suggest("sier")).containsExactly("GMC Sierra 1500", "GMC Sierra 1500HD");
        assertThat(suggest.suggest("tru")).contains("truck");
        assertThat(suggest.suggest("electric su")).contains("electric suv");
        assertThat(suggest.suggest("  ")).isEmpty();
    }
}
