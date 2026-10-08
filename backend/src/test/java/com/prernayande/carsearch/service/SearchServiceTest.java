package com.prernayande.carsearch.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    RuleBasedQueryParser parser = new RuleBasedQueryParser();

    @Test
    void openEndedQueriesFetch500() {
        assertThat(SearchService.denseLimit(parser.parse("family car with lots of space").filters())).isEqualTo(500);
    }

    @Test
    void filteredQueriesFetch2000() {
        assertThat(SearchService.denseLimit(parser.parse("trucks").filters())).isEqualTo(2000);
        assertThat(SearchService.denseLimit(parser.parse("electric suv under 60k").filters())).isEqualTo(2000);
    }
}
