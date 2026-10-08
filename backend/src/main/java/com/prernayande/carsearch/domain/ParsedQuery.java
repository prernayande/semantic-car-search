package com.prernayande.carsearch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// normalized = what gets embedded; residual = leftover words for full-text search;
// gatedFields = fields whose explicit preference also removes contradicting rows ("truck" -> only pickups)
public record ParsedQuery(String original, String normalized, String residual, List<HardConstraint> hard,
                          List<SoftConstraint> soft, Set<Field> gatedFields) {

    public boolean hasSoft() {
        return !soft.isEmpty();
    }

    public boolean hasResidual() {
        return !residual.isBlank();
    }

    // values of the same field are alternatives ("off road" = suv or pickup)
    public Map<Field, List<SoftConstraint>> softByField() {
        Map<Field, List<SoftConstraint>> groups = new LinkedHashMap<>();
        for (SoftConstraint s : soft) {
            groups.computeIfAbsent(s.field(), f -> new ArrayList<>()).add(s);
        }
        return groups;
    }

    // everything that removes rows in SQL: hard constraints + gated fields
    public Filters filters() {
        Map<Field, List<SoftConstraint>> gates = new LinkedHashMap<>();
        softByField().forEach((field, group) -> {
            if (gatedFields.contains(field)) gates.put(field, group);
        });
        return new Filters(hard, gates);
    }
}
