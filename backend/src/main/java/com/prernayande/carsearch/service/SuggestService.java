package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.repository.CarRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Search-as-you-type suggestions: plain prefix matching on make and model names and lexicon words.
// No embeddings; the name list is loaded from the database once.
@Service
public class SuggestService {

    static final int MAX = 8;

    private final CarRepository cars;
    private List<String[]> names;   // {make, model}, loaded on first use

    public SuggestService(CarRepository cars) {
        this.cars = cars;
    }

    public List<String> suggest(String typed) {
        String q = typed.toLowerCase().trim();
        if (q.isEmpty()) return List.of();
        if (names == null) names = cars.makesAndModels();

        Set<String> out = new LinkedHashSet<>();   // keeps order, drops duplicates
        for (String[] n : names) if (n[0].toLowerCase().startsWith(q)) out.add(n[0]);                    // "fo" -> Ford
        for (String[] n : names) if ((n[0] + " " + n[1]).toLowerCase().startsWith(q)) out.add(n[0] + " " + n[1]);   // "ford f" -> Ford F-150
        for (String[] n : names) if (n[1].toLowerCase().startsWith(q)) out.add(n[0] + " " + n[1]);      // "sier" -> GMC Sierra 1500

        // lexicon words complete the last word typed: "electric su" -> "electric suv"
        int space = q.lastIndexOf(' ');
        String head = q.substring(0, space + 1), last = q.substring(space + 1);
        if (!last.isEmpty()) {
            for (Lexicon.Term t : Lexicon.TERMS) {
                if (t.field() != Field.MAKE && t.phrase().startsWith(last)) out.add(head + t.phrase());
            }
        }
        return out.stream().limit(MAX).toList();
    }
}
