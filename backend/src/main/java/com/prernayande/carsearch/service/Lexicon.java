package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.domain.Op;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Query vocabulary: phrase -> field value. Weights say how much a match counts in the constraint score.
class Lexicon {

    static final double W_BODY = 3, W_FUEL = 2, W_MARKET = 2, W_DRIVETRAIN = 1.5, W_SIZE = 1, W_TRANSMISSION = 1,
            W_SUBJECTIVE = 1.5;

    record Option(Object value, String label) {}

    // several options = alternatives ("off road" = SUV or pickup)
    record Term(String phrase, Field field, Op op, double weight, List<Option> options) {}

    static final List<Term> TERMS = new ArrayList<>();

    static {
        body("pickup", "Pickup", "truck", "pickup", "pick up", "crew cab", "ute");
        body("suv", "SUV", "suv", "crossover", "sport utility");
        body("sedan", "Sedan", "sedan", "saloon");
        body("coupe", "Coupe", "coupe", "2 door", "two door");
        body("convertible", "Convertible", "convertible", "cabriolet", "drop top", "roadster");
        body("hatchback", "Hatchback", "hatchback", "hatch");
        body("wagon", "Wagon", "wagon", "station wagon", "estate");
        body("minivan", "Minivan", "minivan", "mpv");
        body("van", "Van", "van", "cargo van");
        for (String p : List.of("off road", "offroad", "off roader")) {
            TERMS.add(new Term(p, Field.BODY_TYPE, Op.IN, W_BODY,
                    List.of(new Option("suv", "Body: SUV"), new Option("pickup", "Body: Pickup"))));
        }

        add(Field.FUEL, "electric", "Fuel: Electric", W_FUEL, "electric", "ev", "battery", "zero emission");
        add(Field.FUEL, "hybrid", "Fuel: Hybrid", W_FUEL, "hybrid", "plug in");
        add(Field.FUEL, "diesel", "Fuel: Diesel", W_FUEL, "diesel", "tdi");

        add(Field.DRIVETRAIN, "awd", "Drivetrain: AWD", W_DRIVETRAIN, "awd", "all wheel drive");
        add(Field.DRIVETRAIN, "4wd", "Drivetrain: 4WD", W_DRIVETRAIN, "4wd", "4x4", "four wheel drive");
        add(Field.DRIVETRAIN, "rwd", "Drivetrain: RWD", W_DRIVETRAIN, "rwd", "rear wheel drive");
        add(Field.DRIVETRAIN, "fwd", "Drivetrain: FWD", W_DRIVETRAIN, "fwd", "front wheel drive");

        add(Field.TRANSMISSION, "MANUAL", "Transmission: Manual", W_TRANSMISSION, "manual", "stick", "stick shift");
        add(Field.TRANSMISSION, "AUTOMATIC", "Transmission: Automatic", W_TRANSMISSION, "automatic");

        add(Field.SIZE, "Compact", "Size: Compact", W_SIZE, "compact", "small");
        add(Field.SIZE, "Midsize", "Size: Midsize", W_SIZE, "midsize", "mid size");
        add(Field.SIZE, "Large", "Size: Large", W_SIZE, "large", "full size", "big");

        add(Field.MARKET, "Luxury", "Category: Luxury", W_MARKET, "luxury", "premium", "upscale");
        add(Field.MARKET, "Performance", "Category: Performance", W_MARKET, "performance", "sporty", "sport", "sports", "fast", "quick");
        add(Field.MARKET, "Exotic", "Category: Exotic", W_MARKET, "exotic", "supercar", "hypercar");

        // subjective words only boost, they never filter
        for (String p : List.of("cheap", "affordable", "budget", "inexpensive")) {
            TERMS.add(new Term(p, Field.MSRP, Op.LTE, W_SUBJECTIVE, List.of(new Option(25_000, "Budget: under $25,000"))));
        }
        for (String p : List.of("fuel efficient", "good mileage", "good gas mileage", "economical")) {
            TERMS.add(new Term(p, Field.HIGHWAY_MPG, Op.GTE, W_SUBJECTIVE, List.of(new Option(30, "Fuel efficient: 30+ mpg highway"))));
        }

        // makes are hard filters (weight unused)
        String[] makes = {"Acura", "Alfa Romeo", "Aston Martin", "Audi", "BMW", "Bentley", "Bugatti", "Buick",
                "Cadillac", "Chevrolet", "Chrysler", "Dodge", "FIAT", "Ferrari", "Ford", "GMC", "Genesis", "HUMMER",
                "Honda", "Hyundai", "Infiniti", "Kia", "Lamborghini", "Land Rover", "Lexus", "Lincoln", "Lotus",
                "Maserati", "Maybach", "Mazda", "McLaren", "Mercedes-Benz", "Mitsubishi", "Nissan", "Oldsmobile",
                "Plymouth", "Pontiac", "Porsche", "Rolls-Royce", "Saab", "Scion", "Spyker", "Subaru", "Suzuki",
                "Tesla", "Toyota", "Volkswagen", "Volvo"};
        for (String make : makes) add(Field.MAKE, make, "Make: " + make, 0, make.toLowerCase().replace('-', ' '));
        add(Field.MAKE, "Chevrolet", "Make: Chevrolet", 0, "chevy");
        add(Field.MAKE, "Volkswagen", "Make: Volkswagen", 0, "vw");
        add(Field.MAKE, "Mercedes-Benz", "Make: Mercedes-Benz", 0, "mercedes", "benz", "merc");

        // longest phrase first, so "sport utility" wins over "sport" and "cargo van" over "van"
        TERMS.sort(Comparator.comparingInt((Term t) -> t.phrase().length()).reversed());
    }

    static void body(String value, String name, String... phrases) {
        add(Field.BODY_TYPE, value, "Body: " + name, W_BODY, phrases);
    }

    static void add(Field field, Object value, String label, double weight, String... phrases) {
        for (String p : phrases) TERMS.add(new Term(p, field, Op.IN, weight, List.of(new Option(value, label))));
    }
}
