# Known limitations

Grouped by scenario. Each item's number matches section 7 of [TEST_SCENARIOS.md](TEST_SCENARIOS.md).

## Query understanding

- **7.7 `over 400 hp`, `2015 or newer`:** horsepower, MPG and model-year phrases are not parsed, so the number is not
  applied as a filter and the query falls back to meaning.
- **7.8 `under 30`:** not treated as a price. A bare number below 5,000 is ignored as a price (README assumption 9),
  so it becomes search text.
- **7.12 Vague queries:** less nuance than a large hosted model. The embedding model is small and runs in-process,
  which is why category inference uses a conservative threshold.
- **7.13 `heavy duty truck`:** synonyms are hand written, so phrases not in the word list, such as "heavy duty", are
  left to meaning. This is on purpose: only explicit body words such as "truck" become filters.
- **7.19 `"%'`:** a query of only special characters lists every car, newest first, as if it were filters only.

## Ranking and gates

- **7.1 `vehicle for a big family`:** 2 cars that must never appear, a 2007 Bentley Azure convertible (rank 50) and a
  2000 Cadillac Eldorado coupe (rank 63 of 125). "big" matches Size: Large, so they get partial credit and survive the
  cutoff. Not fixed, to keep the held-out set honest.
- **7.10 `sedan`:** the fetch cap still applies to very large filtered sets. A filtered query ranks at most the 2,000
  nearest cars (2,843 sedans pass "sedan"), and an open-ended query ranks the 500 nearest.
- **7.14 `500`:** also matches "Sierra 1500" and other 1500 models, because a typed word may follow a digit so that
  "hd" finds "1500HD".
- **7.15 Hand-tuned thresholds:** the cutoff, inference margin and diversity penalties were chosen on the same 20
  queries they are measured on (see the comments next to the constants in `WeightedRanking` and `CategoryInferrer`).

## What the data cannot answer

- **7.4 `red car`:** 221 cars, none chosen for colour. The dataset has no colour.
- **7.5 `7 seater`:** cars ranked by meaning, not by seat count. The dataset has no seat counts (or review data).
- **7.6 `mini cooper`:** 177 unrelated cars. There are no MINI cars in the dataset.
- **7.18 Latest cars:** the dataset covers model years 1990–2017, so the latest cars are 2017 models and prices are
  their original MSRP.

## Nonsense queries

- **7.2 `asdfgh`:** returns 1 car (a Ford Aspire) instead of nothing. Its best similarity (0.255) is just above the
  0.25 floor.
- **7.3 `zzzz`, `xkcd`:** return 22 and 33 cars. Nonsense and real queries overlap in similarity (0.26–0.39 versus
  "exotic" 0.37 and "sporty" 0.34), so no floor separates them: raising it to 0.30 would drop 44 "exotic" and 32
  "sporty" results and still not stop "zzzz".

## Filters and short lists

- **7.9 `electric SUV under 60k`:** only 3 results, the Toyota RAV4 EV years. Filters are never relaxed, so strict
  queries give short lists. By design.

## Performance and hosting

- **7.17 First search after an idle period:** a few seconds slower. The keep-alive pings Render every 10 minutes so it
  normally doesn't sleep, but GitHub's scheduled runs can be delayed or skipped, so a cold start is still possible.
  Neon also suspends when idle, since the ping doesn't touch the database. Normal searches take 1–2 s at Render's
  0.1 CPU, mostly to embed the query.
- **7.16 The latest list is cached:** it is read from the database once per app start, so restart the app after
  re-ingesting.

## Data quality

- **7.11 Label errors flow through:** for example, a 2002 Chrysler Concorde sedan is labelled 4WD in the source
  dataset.
