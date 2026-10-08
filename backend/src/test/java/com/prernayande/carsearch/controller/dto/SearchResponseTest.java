package com.prernayande.carsearch.controller.dto;

import com.prernayande.carsearch.service.RuleBasedQueryParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResponseTest {

    @Test
    void filtersAreLabelledByWhatTheyDo() {
        // make and explicit body type remove cars; drivetrain only boosts
        assertThat(SearchResponse.filters(new RuleBasedQueryParser().parse("ford trucks awd"))).containsExactly(
                new FilterDto("Make: Ford", "filter"),
                new FilterDto("Body: Pickup", "filter"),
                new FilterDto("Drivetrain: AWD", "preference"));
    }
}
