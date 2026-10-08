package com.prernayande.carsearch.domain;

// IN = equals one of the values, NOT_IN = exclusion ("not electric"), BETWEEN = [min, max]
public enum Op { IN, NOT_IN, LTE, GTE, BETWEEN }
