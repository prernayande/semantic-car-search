package com.prernayande.carsearch.domain;

// All trims of one make + model + year. score = best trim's score - diversity penalty.
public record CarGroup(ScoredCar best, int trimCount, Integer msrpMin, Integer msrpMax,
                       double diversityPenalty, double score) {}
