package com.prernayande.carsearch.controller.dto;

import com.prernayande.carsearch.domain.CarGroup;
import com.prernayande.carsearch.domain.LatestGroup;

import java.util.List;

// One make + model + year. score is null when the list is not ranked (latest cars, filter-only searches).
public record ResultDto(CarDto car, int trimCount, Integer msrpMin, Integer msrpMax, List<String> matched, ScoreDto score) {

    static ResultDto from(CarGroup g, boolean ranked) {
        return new ResultDto(CarDto.from(g.best().car()), g.trimCount(), g.msrpMin(), g.msrpMax(),
                g.best().matched(), ranked ? ScoreDto.from(g) : null);
    }

    static ResultDto from(LatestGroup g) {
        return new ResultDto(CarDto.from(g.car()), g.trimCount(), g.msrpMin(), g.msrpMax(), List.of(), null);
    }
}
