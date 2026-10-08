package com.prernayande.carsearch.service;

import com.prernayande.carsearch.domain.Filters;
import com.prernayande.carsearch.domain.LatestGroup;
import com.prernayande.carsearch.repository.CarRepository;
import org.springframework.stereotype.Service;

import java.util.List;

// The "latest cars" list shown before any search. Loaded from the database once and paged in memory,
// so opening the home page costs no query. Restart the app after re-ingesting.
@Service
public class LatestService {

    public record LatestResult(int totalGroups, int page, int size, List<LatestGroup> results) {}

    private final CarRepository cars;
    private volatile List<LatestGroup> all;   // loaded on first use

    public LatestService(CarRepository cars) {
        this.cars = cars;
    }

    public LatestResult page(int page, int size) {
        if (all == null) all = cars.latest(Filters.NONE);
        int from = (int) Math.min((long) page * size, all.size());
        return new LatestResult(all.size(), page, size, all.subList(from, Math.min(from + size, all.size())));
    }
}
