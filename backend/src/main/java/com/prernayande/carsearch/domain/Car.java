package com.prernayande.carsearch.domain;

import java.util.List;

// One trim of a model-year, as stored in the car table (without the embedding).
public record Car(Integer id, String make, String model, int year, String fuelTypeRaw, String fuel,
                  Integer engineHp, Integer engineCylinders, String transmission, String drivetrain, Integer doors,
                  List<String> marketCategories, String vehicleSize, String vehicleStyle, String bodyType,
                  Integer highwayMpg, Integer cityMpg, Integer popularity, Integer msrp, boolean priceKnown) {}
