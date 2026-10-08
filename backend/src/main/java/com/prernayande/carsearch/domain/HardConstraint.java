package com.prernayande.carsearch.domain;

import java.util.List;

// Removes rows that do not satisfy it: price, make, exclusions.
public record HardConstraint(Field field, Op op, List<Object> values, String label) {}
