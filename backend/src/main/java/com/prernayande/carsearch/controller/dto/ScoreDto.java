package com.prernayande.carsearch.controller.dto;

import com.prernayande.carsearch.domain.CarGroup;

// How a result's score was made: each signal scaled to [0, 1], total = blend - diversity penalty
public record ScoreDto(double total, double semantic, double keyword, double constraints, double diversityPenalty) {

    static ScoreDto from(CarGroup g) {
        return new ScoreDto(g.score(), g.best().semantic(), g.best().lexical(), g.best().constraints(), g.diversityPenalty());
    }
}
