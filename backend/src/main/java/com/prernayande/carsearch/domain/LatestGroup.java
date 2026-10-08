package com.prernayande.carsearch.domain;

// One make + model + year for the "latest cars" list shown before any search. car = cheapest trim.
public record LatestGroup(Car car, int trimCount, Integer msrpMin, Integer msrpMax) {}
