# Assumptions

Grouped by the same scenarios as [LIMITATIONS.md](LIMITATIONS.md). Numbers 1–19 are the README's original numbering,
so references such as "assumption 9" still point to the same item; 20–24 were added after the 2026-10-08 fixes.

## Query understanding

Covers the rule-based query parser (prices, negation, lexicon words) and search-as-you-type suggestions.

2. **One word is enough:** there is no minimum query length, because "trucks" is the headline example.
6. **"cheap" means preferring MSRP of at most $25,000:** a rough cut near the low end of post-2000 prices; it boosts,
   it never filters.
7. **"fuel efficient" means preferring at least 30 highway mpg:** a common rule of thumb; it boosts, it never filters.
8. **"under 30k" means at most $30,000, and "around 30k" means ±15%:** a simple, predictable reading.
9. **A bare number is a price only if it is at least 5,000:** so "400 hp" or "2015" are not read as prices.
10. **Negation applies to the next word only:** "suv not electric" excludes electric, nothing else.
11. **"off road" means SUV or pickup:** that is what people mean, and it avoids trusting drivetrain labels alone.
16. **Suggestions are prefix matches only:** makes, models and lexicon words that start with what was typed, at most 8;
    no typo correction, which keeps them instant and predictable.
23. **Vague words are left to meaning on purpose:** phrases not in the hand-written word list, such as "heavy duty",
    are not parsed and go to the embedding model. Only explicit body words such as "truck" become filters, so a vague
    word never removes cars (LIMITATIONS 7.13).

## Filters

Covers the hard SQL filters (body type, fuel, make, price, exclusions) and the filters-only list.

3. **An explicit body type or fuel word is a hard filter:** showing a sedan for "truck" is a failure at any rank.
4. **A named make is a hard filter:** "ford trucks" must never show another make.
18. **A query that is only filters lists every match, newest first:** "under 30k" has no words to rank by, so all 875
    matching model-years are equally relevant.

## Ranking and the four gates

Covers grouping, the fetch cap, the name match and the drop-instead-of-pad rule behind the similarity floor and
relative cutoff.

12. **One card per make, model and year:** listing every trim would bury different models.
13. **Weak matches are dropped instead of padded:** fewer relevant results beat filler.
20. **Fetch cap: 500 nearest cars for open-ended queries, 2,000 when a filter already narrows the set** (body type,
    fuel, make, price or an exclusion): enough that "trucks" ranks all 1,597 pickups (126 model-years). It stays
    capped because the app runs on Render's free tier (512 MB, 0.1 CPU); measured with those limits, 2,000 left peak
    memory unchanged (about 357 MiB) and added roughly 0.3–0.7 s to the largest filtered queries.
21. **A typed name must match a whole word of the make or model:** so "red" does not match "Five Hundred" and "mini"
    does not match "Lumina Minivan", while "m3" still finds "BMW M3".
22. **A typed word right after a number also counts as a name match:** so "hd" finds "Sierra 1500HD", which full-text
    search indexes as one word. The cost is that "500" also matches "1500" models (LIMITATIONS 7.14).

## What the data covers

Covers what gets embedded, the latest-cars list, and how the app answers questions the data cannot.

15. **Price, horsepower and MPG are not embedded:** small models treat "$25,000" and "$95,000" almost the same, so
    numeric intent is handled by filters.
17. **An empty search shows the latest cars:** newest model-year first, then the dataset's popularity score, so the page
    is never blank. Popularity is one number per make in this dataset, so within a year the most popular make comes
    first.
24. **No generated data:** fields the dataset lacks, such as colour or seat count, are not invented. "red car" returns
    the nearest cars by meaning rather than cars with random colours (LIMITATIONS 7.4, 7.5).

## Performance and hosting

Covers the embedding model, hosting, the keep-alive ping and the cached latest list. The fetch cap's hosting reason
is under assumption 20.

14. **A small embedding model run in-process:** no key or cost and it fits a small server, at the price of less nuance.
19. **The link must open without setup:** so the app is hosted (Render + Neon, free plans) with a keep-alive ping, and
    the home page's latest list is cached in memory so a visit does not wake the database.

## Data quality

Covers the dataset choice and the cleaning done at ingest.

1. **Dataset:** the public Kaggle "Car Features and MSRP" file (11,914 rows, 16 columns), because it is messy enough to
   show real cleaning (715 duplicates, missing values, placeholder prices).
5. **Price is unknown for model years 2000 and earlier:** the MSRP median for those years is $2,000–$2,855 versus
   $22,768–$36,720 afterwards, so it is not a new-car price.

## Gaps: features with no assumption behind them

These features are built on numbers or rules that were chosen or tuned, not on a written assumption. They are listed
here instead of inventing one.

- **Category inference** (`CategoryInferrer`): the 0.50 similarity minimum, 0.08 margin and at most 3 guesses per field
  were tuned on the main evaluation queries (LIMITATIONS 7.15).
- **Blend weights:** α/β/γ (0.45/0.20/0.35 with preferences, 0.70/0.30/0 without) and the per-preference weights (body 3,
  fuel 2, category 2, ...).
- **Gate thresholds:** the 0.25 similarity floor and the 60% relative cutoff. Assumption 13 covers dropping weak
  matches, not where the line is.
- **Diversity penalty:** 0.015 per higher group of the same make, 0.03 more for the same model, capped at 0.10.
- **Lexical candidates:** up to 200 full-text matches per query.
- **Cleaning rules:** how the dataset's vehicle styles and fuel spellings map to one body type and fuel
  (`CarCleaner`).
- **Empty-result message:** naming the filter whose removal brings back the most cars.
- **Evaluation labels:** relevance is defined by rules over dataset fields, written for this project.
