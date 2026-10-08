package com.prernayande.carsearch.repository;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CarRepositoryTest {

    // Postgres \m / \M (word start / end) behave like \b here, which Java's regex understands
    static boolean matches(String word, String name) {
        String javaRegex = CarRepository.wholeWord(word).replace("\\m", "\\b").replace("\\M", "\\b");
        return Pattern.compile(javaRegex).matcher(name).find();
    }

    @Test
    void wholeWordsMatch() {
        assertThat(matches("civic", "honda civic")).isTrue();
        assertThat(matches("m3", "bmw m3")).isTrue();
        assertThat(matches("f-150", "ford f-150")).isTrue();
    }

    @Test
    void wordsAfterANumberMatch() {
        assertThat(matches("hd", "gmc sierra 1500hd")).isTrue();
    }

    @Test
    void partsOfWordsDoNotMatch() {
        assertThat(matches("red", "ford five hundred")).isFalse();
        assertThat(matches("mini", "chevrolet lumina minivan")).isFalse();
        assertThat(matches("f-150", "ford f-1500")).isFalse();
    }

    @Test
    void punctuationIsEscaped() {
        assertThat(CarRepository.wholeWord("f-150")).isEqualTo("(\\m|(?<=[0-9]))f\\-150\\M");
    }
}
