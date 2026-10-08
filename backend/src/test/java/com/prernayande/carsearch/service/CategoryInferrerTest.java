package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.service.CategoryInferrer.Category;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryInferrerTest {

    QueryParser parser = new RuleBasedQueryParser();

    static Category category(String value) {
        return CategoryInferrer.CATEGORIES.stream().filter(c -> c.value().equals(value)).findFirst().orElseThrow();
    }

    @Test
    void excludedCategoryIsNeverGuessed() {
        ParsedQuery p = parser.parse("ford not truck");
        assertThat(CategoryInferrer.excluded(p, category("pickup"))).isTrue();
        assertThat(CategoryInferrer.excluded(p, category("suv"))).isFalse();
    }

    @Test
    void otherCategoriesCanStillBeGuessed() {
        ParsedQuery p = parser.parse("family car not minivan");
        assertThat(CategoryInferrer.excluded(p, category("minivan"))).isTrue();
        assertThat(CategoryInferrer.excluded(p, category("suv"))).isFalse();
        assertThat(CategoryInferrer.excluded(p, category("Luxury"))).isFalse();
    }
}
