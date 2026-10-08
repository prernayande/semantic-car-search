package com.prernayande.carsearch.repository;

import com.prernayande.carsearch.domain.Candidate;
import com.prernayande.carsearch.domain.Car;
import com.prernayande.carsearch.domain.Field;
import com.prernayande.carsearch.domain.Filters;
import com.prernayande.carsearch.domain.HardConstraint;
import com.prernayande.carsearch.domain.LatestGroup;
import com.prernayande.carsearch.domain.Op;
import com.prernayande.carsearch.domain.SoftConstraint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

// All SQL lives here. Every user value is a bind parameter.
@Repository
public class CarRepository {

    static final String COLUMNS = "c.id, c.make, c.model, c.year, c.fuel_type_raw, c.fuel, c.engine_hp, c.engine_cylinders, "
            + "c.transmission, c.drivetrain, c.doors, c.market_categories, c.vehicle_size, c.vehicle_style, "
            + "c.body_type, c.highway_mpg, c.city_mpg, c.popularity, c.msrp, c.price_known";

    private final JdbcTemplate jdbc;

    public CarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // nearest neighbours by cosine distance (HNSW index). ef_search must be >= limit or HNSW returns at most 40;
    // iterative_scan keeps searching when the WHERE filters out most neighbours.
    @Transactional(readOnly = true)
    public List<Candidate> findDense(float[] query, String lexicalQuery, Filters filters, int limit) {
        jdbc.execute("SET LOCAL hnsw.ef_search = " + Math.min(limit, 1000));
        jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        return select(query, lexicalQuery, filters, false, "c.embedding <=> CAST(? AS vector)", limit);
    }

    // text matches on the leftover words, best first
    public List<Candidate> findLexical(float[] query, String lexicalQuery, Filters filters, int limit) {
        return select(query, lexicalQuery, filters, true, "lex DESC", limit);
    }

    // a leftover word that appears inside the make or model name, e.g. "hd" in "Sierra 1500HD".
    // Full-text search alone misses these because "1500hd" is indexed as one word.
    static final String NAME_CONTAINS = "lower(c.make || ' ' || c.model) LIKE ?";

    // every distinct {make, model}, for search suggestions
    public List<String[]> makesAndModels() {
        return jdbc.query("SELECT DISTINCT make, model FROM car ORDER BY make, model",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
    }

    public int count(Filters filters) {
        List<Object> params = new ArrayList<>();
        String where = where(filters, params);
        return jdbc.queryForObject("SELECT count(*) FROM car c WHERE " + where, Integer.class, params.toArray());
    }

    // every make + model + year with a trim that passes the filters, newest first (most popular make first
    // within a year). Trim counts and price ranges cover only the trims that pass.
    public List<LatestGroup> latest(Filters filters) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT * FROM (SELECT DISTINCT ON (c.make, c.model, c.year) " + COLUMNS + ","
                + " count(*) OVER w AS trims,"
                + " min(CASE WHEN c.price_known THEN c.msrp END) OVER w AS msrp_min,"
                + " max(CASE WHEN c.price_known THEN c.msrp END) OVER w AS msrp_max"
                + " FROM car c WHERE " + where(filters, params) + " WINDOW w AS (PARTITION BY c.make, c.model, c.year)"
                + " ORDER BY c.make, c.model, c.year, c.msrp NULLS LAST) g"
                + " ORDER BY g.year DESC, g.popularity DESC NULLS LAST, g.make, g.model";
        return jdbc.query(sql, (rs, i) -> new LatestGroup(car(rs), rs.getInt("trims"),
                rs.getObject("msrp_min", Integer.class), rs.getObject("msrp_max", Integer.class)), params.toArray());
    }

    private List<Candidate> select(float[] query, String lexicalQuery, Filters filters, boolean onlyTextHits,
                                   String orderBy, int limit) {
        String vector = vector(query);
        List<Object> params = new ArrayList<>();
        params.add(vector);
        String lex = "0";
        List<String> likes = new ArrayList<>();   // "%hd%" for each leftover word
        if (lexicalQuery != null) {
            for (String w : lexicalQuery.split(" or ")) likes.add("%" + w + "%");
            // full-text rank + 0.1 for each word found inside the make/model name
            lex = "ts_rank_cd(c.search_tsv, websearch_to_tsquery('english', ?), 32) + 0.1 * ("
                    + String.join(" + ", Collections.nCopies(likes.size(), "(" + NAME_CONTAINS + ")::int")) + ")";
            params.add(lexicalQuery);
            params.addAll(likes);
        }
        String sql = "SELECT " + COLUMNS + ", 1 - (c.embedding <=> CAST(? AS vector)) AS sem, " + lex + " AS lex"
                + " FROM car c WHERE " + where(filters, params);
        if (onlyTextHits) {
            sql += " AND (c.search_tsv @@ websearch_to_tsquery('english', ?) OR "
                    + String.join(" OR ", Collections.nCopies(likes.size(), NAME_CONTAINS)) + ")";
            params.add(lexicalQuery);
            params.addAll(likes);
        }
        if (orderBy.contains("?")) params.add(vector);
        sql += " ORDER BY " + orderBy + " LIMIT ?";
        params.add(limit);
        return jdbc.query(sql, (rs, i) -> new Candidate(car(rs), rs.getDouble("sem"), rs.getDouble("lex")), params.toArray());
    }

    // hard constraints AND gates (a gate passes if the row matches any value of that field)
    static String where(Filters filters, List<Object> params) {
        List<String> parts = new ArrayList<>();
        for (HardConstraint h : filters.hard()) {
            if (h.op() == Op.NOT_IN) {
                // exclude only rows known to match; rows with no data for the field stay
                for (Object v : h.values()) {
                    parts.add("NOT COALESCE(" + condition(new SoftConstraint(h.field(), Op.IN, v, 0, false, ""), params) + ", false)");
                }
            } else if (h.field() == Field.MAKE) {
                parts.add("c.make IN (" + String.join(", ", Collections.nCopies(h.values().size(), "?")) + ")");
                params.addAll(h.values());
            } else {   // price
                parts.add(switch (h.op()) {
                    case LTE -> "c.price_known AND c.msrp <= ?";
                    case GTE -> "c.price_known AND c.msrp >= ?";
                    default -> "c.price_known AND c.msrp BETWEEN ? AND ?";
                });
                params.addAll(h.values());
            }
        }
        for (List<SoftConstraint> gate : filters.gates().values()) {
            List<String> anyOf = new ArrayList<>();
            for (SoftConstraint s : gate) anyOf.add(condition(s, params));
            parts.add("(" + String.join(" OR ", anyOf) + ")");
        }
        return parts.isEmpty() ? "TRUE" : String.join(" AND ", parts);
    }

    // SQL version of WeightedRanking.match()
    static String condition(SoftConstraint s, List<Object> params) {
        Object v = s.value();
        String sql = switch (s.field()) {
            case BODY_TYPE -> "c.body_type = ?";
            case DRIVETRAIN -> "c.drivetrain = ?";
            case TRANSMISSION -> "c.transmission = ?";
            case SIZE -> "c.vehicle_size = ?";
            case MAKE -> "c.make = ?";
            case FUEL -> v.equals("hybrid") ? "'Hybrid' = ANY(c.market_categories)"
                    : v.equals("diesel") ? "(c.fuel = 'diesel' OR 'Diesel' = ANY(c.market_categories))"
                    : "c.fuel = ?";
            case MARKET -> v.equals("Performance")
                    ? "('Performance' = ANY(c.market_categories) OR 'High-Performance' = ANY(c.market_categories))"
                    : "? = ANY(c.market_categories)";
            case MSRP -> "(c.price_known AND c.msrp <= ?)";
            case HIGHWAY_MPG -> "c.highway_mpg >= ?";
        };
        if (sql.contains("?")) params.add(v);
        return sql;
    }

    static Car car(ResultSet rs) throws SQLException {
        Array cats = rs.getArray("market_categories");
        return new Car(rs.getInt("id"), rs.getString("make"), rs.getString("model"), rs.getInt("year"),
                rs.getString("fuel_type_raw"), rs.getString("fuel"), rs.getObject("engine_hp", Integer.class),
                rs.getObject("engine_cylinders", Integer.class), rs.getString("transmission"), rs.getString("drivetrain"),
                rs.getObject("doors", Integer.class), cats == null ? List.of() : List.of((String[]) cats.getArray()),
                rs.getString("vehicle_size"), rs.getString("vehicle_style"), rs.getString("body_type"),
                rs.getObject("highway_mpg", Integer.class), rs.getObject("city_mpg", Integer.class),
                rs.getObject("popularity", Integer.class), rs.getObject("msrp", Integer.class), rs.getBoolean("price_known"));
    }

    // pgvector text format: [0.1,0.2,...]
    static String vector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) sb.append(i > 0 ? "," : "").append(v[i]);
        return sb.append("]").toString();
    }

    // ingest: replace the whole table (caller wraps this in a transaction)
    public void replaceAll(List<Car> cars, List<String> texts, List<float[]> vectors) {
        jdbc.execute("TRUNCATE car RESTART IDENTITY");
        String sql = "INSERT INTO car (make, model, year, fuel_type_raw, fuel, engine_hp, engine_cylinders, transmission, "
                + "drivetrain, doors, market_categories, market_text, vehicle_size, vehicle_style, body_type, highway_mpg, "
                + "city_mpg, popularity, msrp, price_known, search_text, embedding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)";
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < cars.size(); i++) {
            Car c = cars.get(i);
            String pgArray = c.marketCategories().stream().map(x -> "\"" + x + "\"").collect(Collectors.joining(",", "{", "}"));
            rows.add(new Object[] {c.make(), c.model(), c.year(), c.fuelTypeRaw(), c.fuel(), c.engineHp(),
                    c.engineCylinders(), c.transmission(), c.drivetrain(), c.doors(), pgArray,
                    String.join(" ", c.marketCategories()), c.vehicleSize(), c.vehicleStyle(), c.bodyType(),
                    c.highwayMpg(), c.cityMpg(), c.popularity(), c.msrp(), c.priceKnown(), texts.get(i), vector(vectors.get(i))});
        }
        for (int i = 0; i < rows.size(); i += 500) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(i + 500, rows.size())));
        }
    }
}
