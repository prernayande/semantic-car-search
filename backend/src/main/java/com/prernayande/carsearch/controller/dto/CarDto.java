package com.prernayande.carsearch.controller.dto;

import com.prernayande.carsearch.domain.Car;

// The fields of one trim that the UI shows
public record CarDto(String make, String model, int year, String bodyType, String vehicleStyle, String fuel,
                     String drivetrain, String transmission, Integer engineHp, Integer highwayMpg) {

    static CarDto from(Car c) {
        return new CarDto(c.make(), c.model(), c.year(), c.bodyType(), c.vehicleStyle(), c.fuel(), c.drivetrain(),
                c.transmission(), c.engineHp(), c.highwayMpg());
    }
}
