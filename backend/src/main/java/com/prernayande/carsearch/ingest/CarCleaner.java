package com.prernayande.carsearch.ingest;

import com.prernayande.carsearch.domain.Car;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Cleaning rules for one CSV row, and the sentence that gets embedded.
public class CarCleaner {

    public static Car clean(Map<String, String> row) {
        Integer msrp = toInt(row.get("MSRP"));
        int year = Integer.parseInt(row.get("Year").trim());
        return new Car(null, row.get("Make").trim(), row.get("Model").trim(), year,
                blankToNull(row.get("Engine Fuel Type")), fuel(row.get("Engine Fuel Type")),
                toInt(row.get("Engine HP")), toInt(row.get("Engine Cylinders")),
                transmission(row.get("Transmission Type")), drivetrain(row.get("Driven_Wheels")),
                toInt(row.get("Number of Doors")), marketCategories(row.get("Market Category")),
                blankToNull(row.get("Vehicle Size")), blankToNull(row.get("Vehicle Style")),
                bodyType(row.get("Vehicle Style")), toInt(row.get("highway MPG")), toInt(row.get("city mpg")),
                toInt(row.get("Popularity")),
                // MSRP for model years <= 2000 is not a real price (medians ~$2,000-2,900 vs $22k-37k after)
                msrp, msrp != null && year > 2000);
    }

    // six raw spellings -> gasoline / flex / electric / diesel / natural_gas
    static String fuel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.toLowerCase();
        if (s.contains("flex-fuel")) return "flex";   // checked first: "flex-fuel (unleaded/natural gas)" is flex
        if (s.contains("electric")) return "electric";
        if (s.contains("diesel")) return "diesel";
        if (s.contains("natural gas")) return "natural_gas";
        if (s.contains("unleaded")) return "gasoline";
        return null;
    }

    static String drivetrain(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "front wheel drive" -> "fwd";
            case "rear wheel drive" -> "rwd";
            case "all wheel drive" -> "awd";
            case "four wheel drive" -> "4wd";
            default -> null;
        };
    }

    static String transmission(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("UNKNOWN")) return null;
        return raw.trim().toUpperCase();
    }

    // Vehicle Style -> one body type. Order matters: "Cargo Minivan" is a minivan, not a van.
    static String bodyType(String style) {
        if (style == null || style.isBlank()) return null;
        String s = style.toLowerCase();
        for (String type : List.of("pickup", "suv", "minivan", "van", "convertible", "hatchback", "wagon", "coupe", "sedan")) {
            if (s.contains(type)) return type;
        }
        return null;
    }

    static List<String> marketCategories(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("N/A")) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static Integer toInt(String raw) {
        return raw == null || raw.isBlank() ? null : (int) Double.parseDouble(raw.trim());
    }

    static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    // The text that gets embedded, written with the words people search with ("pickup truck").
    // Price, HP and MPG are left out: small embedding models handle numbers badly.
    // e.g. "2015 Ford F-150. Large pickup truck (Crew Cab Pickup). Flex-fuel engine, four wheel drive 4x4, automatic transmission. Flex Fuel."
    static final Map<String, String> BODY_TEXT = Map.of("pickup", "pickup truck", "suv", "SUV sport utility vehicle",
            "wagon", "station wagon");
    static final Map<String, String> FUEL_TEXT = Map.of("gasoline", "Gasoline", "diesel", "Diesel",
            "electric", "Electric battery", "flex", "Flex-fuel", "natural_gas", "Natural gas");
    static final Map<String, String> DRIVE_TEXT = Map.of("fwd", "front wheel drive", "rwd", "rear wheel drive",
            "awd", "all wheel drive", "4wd", "four wheel drive 4x4");

    public static String searchText(Car c) {
        String text = c.year() + " " + c.make() + " " + c.model() + ". ";
        if (c.vehicleSize() != null) text += c.vehicleSize() + " ";
        text += c.bodyType() == null ? "car" : BODY_TEXT.getOrDefault(c.bodyType(), c.bodyType());
        if (c.vehicleStyle() != null) text += " (" + c.vehicleStyle() + ")";
        text += ". " + (c.fuel() == null ? "Gasoline" : FUEL_TEXT.get(c.fuel())) + " engine";
        if (c.drivetrain() != null) text += ", " + DRIVE_TEXT.get(c.drivetrain());
        if (c.transmission() != null) text += ", " + c.transmission().toLowerCase().replace('_', ' ') + " transmission";
        text += ".";
        if (!c.marketCategories().isEmpty()) text += " " + String.join(", ", c.marketCategories()) + ".";
        return text;
    }
}
