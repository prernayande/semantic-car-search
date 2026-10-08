package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Candidate;
import com.prernayande.carsearch.domain.CarGroup;
import com.prernayande.carsearch.domain.ParsedQuery;

import java.util.List;

// Scores candidates and returns them grouped, best first. Implementation: WeightedRanking.
public interface RankingStrategy {

    List<CarGroup> rank(List<Candidate> candidates, ParsedQuery query);
}
