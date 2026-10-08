package com.prernayande.carsearch.controller.dto;

// What the query was understood as. type: "filter" removes cars, "preference" boosts them,
// "guess" is a preference inferred from the query's meaning.
public record FilterDto(String label, String type) {}
