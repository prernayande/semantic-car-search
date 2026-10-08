package com.prernayande.carsearch.ingest;

import com.prernayande.carsearch.domain.Car;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CarCleanerTest {

    static Map<String, String> row(String year, String style, String fuel, String market, String msrp) {
        Map<String, String> r = new HashMap<>();
        r.put("Make", "Ford"); r.put("Model", "F-150"); r.put("Year", year); r.put("Engine Fuel Type", fuel);
        r.put("Engine HP", "282"); r.put("Engine Cylinders", "6"); r.put("Transmission Type", "AUTOMATIC");
        r.put("Driven_Wheels", "four wheel drive"); r.put("Number of Doors", "4"); r.put("Market Category", market);
        r.put("Vehicle Size", "Large"); r.put("Vehicle Style", style); r.put("highway MPG", "23");
        r.put("city mpg", "17"); r.put("Popularity", "5657"); r.put("MSRP", msrp);
        return r;
    }

    @Test
    void mapsStyleAndFuel() {
        assertThat(CarCleaner.bodyType("Crew Cab Pickup")).isEqualTo("pickup");
        assertThat(CarCleaner.bodyType("4dr SUV")).isEqualTo("suv");
        assertThat(CarCleaner.bodyType("Cargo Minivan")).isEqualTo("minivan");
        assertThat(CarCleaner.bodyType("Cargo Van")).isEqualTo("van");
        assertThat(CarCleaner.fuel("premium unleaded (required)")).isEqualTo("gasoline");
        assertThat(CarCleaner.fuel("flex-fuel (unleaded/natural gas)")).isEqualTo("flex");
        assertThat(CarCleaner.fuel("")).isNull();
    }

    @Test
    void cleansRow() {
        Car c = CarCleaner.clean(row("2015", "Crew Cab Pickup", "flex-fuel (unleaded/E85)", "Flex Fuel,Luxury", "45000"));
        assertThat(c.bodyType()).isEqualTo("pickup");
        assertThat(c.drivetrain()).isEqualTo("4wd");
        assertThat(c.marketCategories()).containsExactly("Flex Fuel", "Luxury");
        assertThat(c.priceKnown()).isTrue();
    }

    @Test
    void priceUnknownUpToModelYear2000() {
        assertThat(CarCleaner.clean(row("2000", "Coupe", "regular unleaded", "N/A", "2305")).priceKnown()).isFalse();
        assertThat(CarCleaner.clean(row("2001", "Coupe", "regular unleaded", "N/A", "13000")).priceKnown()).isTrue();
        assertThat(CarCleaner.clean(row("2000", "Coupe", "regular unleaded", "N/A", "2305")).marketCategories()).isEmpty();
    }

    @Test
    void searchText() {
        Car c = CarCleaner.clean(row("2015", "Crew Cab Pickup", "flex-fuel (unleaded/E85)", "Flex Fuel", "45000"));
        assertThat(CarCleaner.searchText(c)).isEqualTo("2015 Ford F-150. Large pickup truck (Crew Cab Pickup). "
                + "Flex-fuel engine, four wheel drive 4x4, automatic transmission. Flex Fuel.");
        Car noFuel = CarCleaner.clean(row("2015", "Sedan", "", "N/A", "20000"));
        assertThat(CarCleaner.searchText(noFuel)).contains("Gasoline engine");
    }
}
