package com.prernayande.carsearch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record Filters(List<HardConstraint> hard, Map<Field, List<SoftConstraint>> gates) {

    public static final Filters NONE = new Filters(List.of(), Map.of());

    public boolean isEmpty() {
        return hard.isEmpty() && gates.isEmpty();
    }

    // the filters with one of them removed at a time; used to explain an empty result
    public List<Relaxation> relaxations() {
        List<Relaxation> out = new ArrayList<>();
        for (HardConstraint h : hard) {
            List<HardConstraint> rest = new ArrayList<>(hard);
            rest.remove(h);
            out.add(new Relaxation(h.label(), new Filters(rest, gates)));
        }
        for (Field field : gates.keySet()) {
            Map<Field, List<SoftConstraint>> rest = new LinkedHashMap<>(gates);
            rest.remove(field);
            String label = gates.get(field).stream().map(SoftConstraint::label).collect(Collectors.joining(" or "));
            out.add(new Relaxation(label, new Filters(hard, rest)));
        }
        return out;
    }

    public record Relaxation(String removedLabel, Filters filters) {}
}
