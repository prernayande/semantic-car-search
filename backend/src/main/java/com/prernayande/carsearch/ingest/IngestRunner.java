package com.prernayande.carsearch.ingest;

import com.prernayande.carsearch.domain.Car;
import com.prernayande.carsearch.repository.CarRepository;
import com.prernayande.carsearch.service.EmbeddingService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// One-time load: read CSV, drop duplicates, clean, embed, replace the car table.
// Run with: mvn spring-boot:run -Dspring-boot.run.profiles=ingest
@Component
@Profile("ingest")
public class IngestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);
    static final String CSV = "../data/car_features_msrp.csv";

    private final EmbeddingService embeddings;
    private final CarRepository cars;
    private final TransactionTemplate tx;

    public IngestRunner(EmbeddingService embeddings, CarRepository cars, TransactionTemplate tx) {
        this.embeddings = embeddings;
        this.cars = cars;
        this.tx = tx;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Map<String, String>> rows;
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new FileReader(CSV))) {
            rows = parser.stream().map(CSVRecord::toMap).toList();
        }
        List<Map<String, String>> unique = new ArrayList<>(new LinkedHashSet<>(rows));   // exact duplicates
        log.info("Read {} rows, dropped {} duplicates", rows.size(), rows.size() - unique.size());

        List<Car> cleaned = unique.stream().map(CarCleaner::clean).toList();
        log.info("{} rows with unknown price (model year <= 2000)", cleaned.stream().filter(c -> !c.priceKnown()).count());

        List<String> texts = cleaned.stream().map(CarCleaner::searchText).toList();
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 256) {
            vectors.addAll(embeddings.embedAll(texts.subList(i, Math.min(i + 256, texts.size()))));
        }

        tx.executeWithoutResult(status -> cars.replaceAll(cleaned, texts, vectors));
        log.info("Ingest complete: {} rows written", cleaned.size());
    }
}
