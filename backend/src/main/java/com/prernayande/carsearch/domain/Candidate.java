package com.prernayande.carsearch.domain;

// semantic = cosine similarity, lexical = ts_rank_cd (0 if no full-text query)
public record Candidate(Car car, double semantic, double lexical) {}
