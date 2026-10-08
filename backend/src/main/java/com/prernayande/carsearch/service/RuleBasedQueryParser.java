package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.domain.HardConstraint;
import com.prernayande.carsearch.domain.Op;
import com.prernayande.carsearch.domain.ParsedQuery;
import com.prernayande.carsearch.domain.SoftConstraint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Rule-based query parsing (no LLM): prices -> hard filters, makes -> hard filter, "not X" -> exclusion,
// lexicon words -> soft preferences, everything else -> residual text for full-text search.
// Matched text is blanked out so it is not matched twice.
@Service
public class RuleBasedQueryParser implements QueryParser {

    // explicit body type / fuel words remove contradicting rows ("truck" -> only pickups)
    static final Set<Field> GATED = EnumSet.of(Field.BODY_TYPE, Field.FUEL);

    static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "with", "in", "on", "to", "at", "by", "from", "as",
            "my", "me", "i", "im", "we", "our", "you", "want", "need", "looking", "look", "find", "show", "give",
            "get", "something", "some", "any", "that", "this", "is", "are", "be", "it", "its", "which", "what",
            "car", "cars", "vehicle", "vehicles", "auto", "automobile", "good", "great", "nice", "best", "new",
            "used", "lots", "lot", "plenty", "really", "very", "please", "like", "would", "can", "could",
            "under", "over", "below", "above", "than", "less", "more", "around", "about", "between");

    @Override
    public ParsedQuery parse(String query) {
        String normalized = normalize(query);
        StringBuilder text = new StringBuilder(normalized);

        List<HardConstraint> hard = new ArrayList<>(extractPrices(text));
        List<SoftConstraint> soft = new ArrayList<>();
        Set<String> makes = new LinkedHashSet<>();

        // negation: "not/no/non/without/except" before a word excludes it
        for (Lexicon.Term term : Lexicon.TERMS) {
            if (term.op() != Op.IN) continue;
            Pattern negated = Pattern.compile("(?<![\\p{L}\\p{N}])(?:not|no|non|without|except)\\s+" + word(term.phrase()));
            if (!consume(negated, text)) continue;
            List<Object> values = term.options().stream().map(Lexicon.Option::value).toList();
            String label = term.options().stream().map(Lexicon.Option::label).collect(Collectors.joining(" or "));
            hard.add(new HardConstraint(term.field(), Op.NOT_IN, values, "Exclude " + label));
        }

        // lexicon terms, longest first (Lexicon.TERMS is sorted)
        for (Lexicon.Term term : Lexicon.TERMS) {
            if (!consume(Pattern.compile("(?<![\\p{L}\\p{N}])" + word(term.phrase())), text)) continue;
            for (Lexicon.Option option : term.options()) {
                if (term.field() == Field.MAKE) {
                    makes.add((String) option.value());
                } else if (soft.stream().noneMatch(s -> s.field() == term.field() && s.value().equals(option.value()))) {
                    soft.add(new SoftConstraint(term.field(), term.op(), option.value(), term.weight(), false, option.label()));
                }
            }
        }
        if (!makes.isEmpty()) {
            hard.add(new HardConstraint(Field.MAKE, Op.IN, List.copyOf(makes), "Make: " + String.join(", ", makes)));
        }

        Set<Field> gated = soft.stream().map(SoftConstraint::field).filter(GATED::contains)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Field.class)));

        String residual = Arrays.stream(text.toString().split("\\s+"))
                .filter(tok -> tok.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}\\-]*"))
                .filter(tok -> !STOPWORDS.contains(tok))
                .collect(Collectors.joining(" "));
        return new ParsedQuery(query, normalized, residual, hard, soft, gated);
    }

    // lowercase, "pick-up" -> "pick up", drop punctuation that is not part of a number
    static String normalize(String raw) {
        String s = raw.toLowerCase().trim();
        s = s.replaceAll("(?<=\\p{L})-(?=\\p{L})", " ");
        s = s.replaceAll("[^\\p{L}\\p{N}$,.<>=+\\-\\s]", " ");
        s = s.replaceAll("(?<!\\d)[,.]|[,.](?!\\d)", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    // whole word, optional plural ("trucks", "suvs", "coupes")
    static String word(String phrase) {
        return Pattern.quote(phrase) + "(?:e?s)?(?![\\p{L}\\p{N}])";
    }

    // blank out every match; returns true if there was one
    static boolean consume(Pattern pattern, StringBuilder text) {
        return consume(pattern, text, m -> true);
    }

    // blank out every match the handler accepts
    static boolean consume(Pattern pattern, StringBuilder text, Predicate<Matcher> handler) {
        Matcher m = pattern.matcher(text.toString());
        boolean found = false;
        while (m.find()) {
            if (!handler.test(m)) continue;
            found = true;
            for (int i = m.start(); i < m.end(); i++) text.setCharAt(i, ' ');
        }
        return found;
    }

    // ---------- prices ----------

    static final String START = "(?<![\\p{L}\\p{N}])";
    static final Pattern BETWEEN = Pattern.compile(START + "(?:between|from)\\s+" + amount(1) + "\\s+(?:and|to|-)\\s+" + amount(2));
    static final Pattern RANGE = Pattern.compile(START + amount(1) + "\\s?(?:-|to)\\s?" + amount(2));
    static final Pattern AT_MOST = Pattern.compile(START + "(?:under|below|less than|cheaper than|no more than|up to|max|maximum|<|<=)\\s*" + amount(1));
    static final Pattern AT_LEAST = Pattern.compile(START + "(?:over|above|more than|at least|min|minimum|starting at|>|>=)\\s*" + amount(1));
    static final Pattern AROUND = Pattern.compile(START + "(?:around|about|approximately|roughly|near)\\s*" + amount(1));

    // an amount: optional $, a number, optional k/thousand/grand
    static String amount(int i) {
        return "(?<c" + i + ">\\$)?\\s?(?<n" + i + ">\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)"
                + "\\s?(?<u" + i + ">k|thousand|grand)?(?![\\p{L}\\p{N}])";
    }

    static List<HardConstraint> extractPrices(StringBuilder text) {
        List<HardConstraint> out = new ArrayList<>();
        Predicate<Matcher> range = m -> {
            Double lo = price(m, 1);
            Double hi = price(m, 2);
            if (m.group("u1") == null && m.group("u2") != null) lo = number(m.group("n1")) * 1000;   // "20-35k"
            if (lo == null || hi == null || lo > hi) return false;
            out.add(new HardConstraint(Field.MSRP, Op.BETWEEN, List.of(lo.intValue(), hi.intValue()),
                    "Price " + money(lo) + " to " + money(hi)));
            return true;
        };
        consume(BETWEEN, text, range);
        consume(RANGE, text, range);
        consume(AT_MOST, text, m -> {
            Double v = price(m, 1);
            if (v == null) return false;
            out.add(new HardConstraint(Field.MSRP, Op.LTE, List.of(v.intValue()), "Price under " + money(v)));
            return true;
        });
        consume(AT_LEAST, text, m -> {
            Double v = price(m, 1);
            if (v == null) return false;
            out.add(new HardConstraint(Field.MSRP, Op.GTE, List.of(v.intValue()), "Price over " + money(v)));
            return true;
        });
        consume(AROUND, text, m -> {   // +-15%
            Double v = price(m, 1);
            if (v == null) return false;
            List<Object> range15 = List.of((int) Math.round(v * 0.85), (int) Math.round(v * 1.15));
            out.add(new HardConstraint(Field.MSRP, Op.BETWEEN, range15, "Price around " + money(v)));
            return true;
        });
        return out;
    }

    // the amount as a price, or null. A bare number must be >= 5,000 so "400 hp" or "2015" are not prices.
    static Double price(Matcher m, int i) {
        double v = number(m.group("n" + i));
        boolean hasUnit = m.group("u" + i) != null;
        if (hasUnit) v *= 1000;
        if (!hasUnit && m.group("c" + i) == null && v < 5000) return null;
        return v > 0 && v <= 5_000_000 ? v : null;
    }

    static double number(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    static String money(double v) {
        return String.format("$%,d", Math.round(v));
    }
}
