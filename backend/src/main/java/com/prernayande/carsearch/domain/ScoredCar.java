package com.prernayande.carsearch.domain;

import java.util.List;

// semantic/lexical/constraints are scaled to [0, 1]; rawSemantic is the cosine similarity
public record ScoredCar(Car car, double rawSemantic, double semantic, double lexical, double constraints,
                        double finalScore, List<String> matched) {}
