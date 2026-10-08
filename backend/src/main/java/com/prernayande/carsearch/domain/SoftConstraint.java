package com.prernayande.carsearch.domain;

// A preference: matching rows score higher, contradicting rows lower.
// inferred = guessed from the query embedding, not typed by the user.
public record SoftConstraint(Field field, Op op, Object value, double weight, boolean inferred, String label) {}
