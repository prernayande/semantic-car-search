# Test scenarios

Each scenario is tied to a requirement in the problem statement. The **Outcome** column records what the app
returned. **Source** says where that came from:

- **Eval:** the labeled evaluation (`eval/results/`, full pipeline). P@10 = share of the top 10 results that are
  relevant; violations = results that must never appear, counted at any rank.
- **Run:** a query run against the app during development and the final fixes on 2026-10-08.

Live app: https://semantic-car-search-cfwe.onrender.com

Counts are results, i.e. make + model + year groups.

## Headline numbers

| Set | Dense search only (baseline) | Full pipeline |
| --- | --- | --- |
| Main set (20 queries, used for tuning) | P@10 0.740, 42 violations | **P@10 1.000, 0 violations** |
| Held-out set (9 queries, never used for tuning) | P@10 0.856, 56 violations | **P@10 1.000, 2 violations** |

## 1. Search is semantic: it understands intent, not just keywords

| # | Query | Expected | Outcome | Source | Pass |
| --- | --- | --- | --- | --- | --- |
| 1.1 | `trucks` | Pickups of all makes | 126 pickup model-years, P@10 1.00, 0 violations | Eval | Yes |
| 1.2 | `pickup` | Same as "trucks" | 126, P@10 1.00, 0 violations | Eval | Yes |
| 1.3 | `pickup truck` | Same as "trucks" | 126, P@10 1.00, 0 violations | Eval (held-out) | Yes |
| 1.4 | `family car with lots of space` | SUVs and minivans, no sports cars | 35, P@10 1.00, 0 violations; top 10 all minivans | Eval | Yes |
| 1.5 | `vehicle for a big family` | Minivans, wagons, large SUVs; never coupes or convertibles | 125, P@10 1.00, **2 violations** (a Bentley Azure convertible at rank 50, a Cadillac Eldorado coupe at rank 63) | Eval (held-out) | Partial, see limitations |
| 1.6 | `something to haul lumber` | Pickups, though no truck word is used | Guesses pickup; top 10 all pickups | Run (README) | Yes |
| 1.7 | `cheap fuel efficient car` | Economical cars | 137, P@10 1.00, 0 violations | Eval | Yes |
| 1.8 | `exotic` | Exotic / high-end cars | 161, P@10 1.00, 0 violations | Eval | Yes |
| 1.9 | `off road 4x4` | Off-road capable vehicles | 424, P@10 1.00, 0 violations | Eval | Yes |
| 1.10 | `heavy duty truck` | Only pickups, heavy-duty ones near the top | 126, all pickups; heavy-duty models only partly at the top (top 4: Ram 1500, Toyota T100, Dodge RAM 250, Lincoln Mark LT) | Run | Partial, see 7.13 |

## 2. The failure case: "trucks" must never show non-trucks

| # | Query | Expected | Outcome | Source | Pass |
| --- | --- | --- | --- | --- | --- |
| 2.1 | `trucks` | No sedans, sports cars or SUVs at any rank | 0 violations across all 126 results | Eval | Yes |
| 2.2 | `ford trucks` | Ford pickups only | 16 (F-150, F-250, Ranger and others), 0 violations | Eval | Yes |
| 2.3 | `chevrolet pickup` | Chevrolet pickups only | 24, P@10 1.00, 0 violations | Eval (held-out) | Yes |
| 2.4 | `toyota suv` | Toyota SUVs only | 25, P@10 1.00, 0 violations (baseline had 33 violations) | Eval (held-out) | Yes |
| 2.5 | `ford not truck` | Fords with no pickups | No pickups returned (bug fixed during development) | Run | Yes |

## 3. Single-word queries work

| # | Query | Expected | Outcome | Source | Pass |
| --- | --- | --- | --- | --- | --- |
| 3.1 | `Civic` | Honda Civics only | 8, all Honda Civic, P@10 1.00 | Eval | Yes |
| 3.2 | `suv` | SUVs | 404, P@10 1.00, 0 violations | Eval | Yes |
| 3.3 | `convertible` | Convertibles | 304, P@10 1.00, 0 violations | Eval | Yes |
| 3.4 | `minivan` | Minivans | 109, P@10 1.00, 0 violations | Eval | Yes |
| 3.5 | `hatchback` | Hatchbacks | 276, P@10 1.00, 0 violations | Eval | Yes |
| 3.6 | `wagon` | Wagons | 174, P@10 1.00, 0 violations | Eval (held-out) | Yes |
| 3.7 | `electric` | Electric cars only | 34, P@10 1.00, 0 violations | Eval | Yes |
| 3.8 | `diesel` | Diesel cars only | 67, P@10 1.00, 0 violations | Eval (held-out) | Yes |
| 3.9 | `hybrid` | Hybrids | 139, P@10 1.00, 0 violations | Eval (held-out) | Yes |
| 3.10 | `hd` | Heavy-duty model names | Finds the 2006 GMC Sierra 1500HD | Run | Yes |

## 4. Ranking and ordering

| # | Query | Expected | Outcome | Source | Pass |
| --- | --- | --- | --- | --- | --- |
| 4.1 | `F-150` | Exact model only, not similar trucks | 7, all Ford F-150 | Run | Yes |
| 4.2 | `bmw m3` | BMW M3 only | 3: M3 2015, 2016, 2017 | Run | Yes |
| 4.3 | `sierra 1500` | GMC Sierra 1500 variants | 22, all Sierra 1500 | Run | Yes |
| 4.4 | `range rover` | Range Rover models | 31, all Land Rover Range Rover variants | Run | Yes |
| 4.5 | `trucks` | Top of the list mixes makes, not three years of one model | Top 4: Dodge Ram 1500, GMC Sierra 1500, GMC Sierra 1500HD, Dodge Ram 1500 | Run | Yes |
| 4.6 | `luxury sedan` | Luxury sedans first | 635, P@10 1.00, 0 violations | Eval | Yes |
| 4.7 | `luxury suv` | Luxury SUVs first | 444, P@10 1.00, 0 violations (baseline had 11 violations) | Eval (held-out) | Yes |
| 4.8 | `manual sports car` | Manual sports cars first | 166, P@10 1.00, 0 violations | Eval | Yes |
| 4.9 | `awd wagon` | AWD wagons first | 169, P@10 1.00, 0 violations | Eval | Yes |
| 4.10 | `two door coupe` | Coupes | 439, P@10 1.00, 0 violations | Eval (held-out) | Yes |

## 5. Price, make, fuel and exclusion filters

| # | Query | Expected | Outcome | Source | Pass |
| --- | --- | --- | --- | --- | --- |
| 5.1 | `under 30k` | Every car under $30k, newest first | 875 | Run | Yes |
| 5.2 | `not electric` | Every non-electric car | 2,422 | Run | Yes |
| 5.3 | `electric suv under 60k` | Electric SUVs under $60k | 3: the Toyota RAV4 EV years only, 0 violations | Eval | Yes |
| 5.4 | `suv not electric` | SUVs, none electric | 408, P@10 1.00, 0 violations (baseline had 3 violations) | Eval | Yes |
| 5.5 | `diesel pickup` | Diesel pickups only | 2 (the data has only 22 matching rows), 0 violations | Eval | Yes |
| 5.6 | `fast convertible` | Fast convertibles | 304, P@10 1.00, 0 violations | Eval | Yes |
| 5.7 | `family car not minivan` | Family cars excluding minivans | Guesses SUV instead of minivan | Run | Yes |

## 6. Edge cases and known limits

| # | Query | Expected | Outcome | Source | Pass |
| --- | --- | --- | --- | --- | --- |
| 6.1 | Empty search box | Latest cars | Latest list, one card per make + model + year | Run | Yes |
| 6.2 | `asdfgh` | Nothing, ideally | 1 (Ford Aspire) | Run | Known limitation |
| 6.3 | `zzzz`, `xkcd` | Nothing, ideally | 22 and 33 | Run | Known limitation |
| 6.4 | `red car` | Red cars | 221, ranked by meaning only (no colour data) | Run | Known limitation |
| 6.5 | `mini cooper` | MINI Coopers | 177 unrelated cars (no MINI in the data) | Run | Known limitation |
| 6.6 | A search filters rule out, e.g. `electric pickup under 10k` | Empty, with a message naming the filter | 0 results, with a message naming the price, fuel and body filters | Run | Yes |
| 6.7 | Very long query, special characters (`"%'`) | No error | Special characters: no error. 600 characters: `400 "q must be 1 to 200 characters"` | Run | Yes |
| 6.8 | Paging on `trucks` | 20 per page, last page works | 20 per page; page 6 has the last 6 of 126; page 7 is empty | Run | Yes |
| 6.9 | First search after the app has been idle | A few seconds slower while Neon wakes; a Render cold start if a keep-alive run was delayed or skipped | Not measured | — | — |

## 7. Failed and mishandled scenarios

Every case found where the app gives a wrong or weak answer, in one place. None are hidden in the tables above.
The same items, grouped by scenario: [LIMITATIONS.md](LIMITATIONS.md).

| # | Query | What goes wrong | Why | Status |
| --- | --- | --- | --- | --- |
| 7.1 | `vehicle for a big family` | 2 cars that must never appear: a Bentley Azure convertible (rank 50) and a Cadillac Eldorado coupe (rank 63) | "big" matches Size: Large, so they get partial credit and survive the cutoff | Not fixed, to keep the held-out set honest |
| 7.2 | `asdfgh` | Returns 1 car instead of nothing | Its best similarity (0.255) is just above the 0.25 floor | Known limitation |
| 7.3 | `zzzz`, `xkcd` | Return 22 and 33 cars | Nonsense and real queries overlap in similarity; no floor separates them | Known limitation |
| 7.4 | `red car` | 221 cars, none chosen for colour | The dataset has no colour | Known limitation |
| 7.5 | `7 seater` | Cars ranked by meaning, not by seat count | The dataset has no seat counts | Known limitation |
| 7.6 | `mini cooper` | 177 unrelated cars | There are no MINI cars in the dataset | Known limitation |
| 7.7 | `over 400 hp`, `2015 or newer` | The number is not applied as a filter; falls back to meaning | Horsepower, MPG and model-year phrases are not parsed | Known limitation |
| 7.8 | `under 30` | Not treated as a price | A bare number below 5,000 is ignored as a price, so it becomes search text | Known limitation (assumption 9) |
| 7.9 | `electric SUV under 60k` | Only 3 results (Toyota RAV4 EV) | Filters are never relaxed, so strict queries give short lists | By design |
| 7.10 | `sedan` | Only the 2,000 nearest of 2,843 matching sedans are ranked | The fetch cap still applies to very large filtered sets | Known limitation |
| 7.11 | Any query | A 2002 Chrysler Concorde sedan is labelled 4WD | Label errors in the source dataset flow through | Known limitation |
| 7.12 | Vague queries | Less nuance than a large hosted model | The embedding model is small and runs in-process; category inference uses a conservative threshold | Known limitation |
| 7.13 | `heavy duty truck` | "heavy duty" is not parsed; it is left to meaning | Synonyms are hand written; only explicit body words such as "truck" become filters | By design |
| 7.14 | `500` | Also matches "Sierra 1500" and other 1500 models | A typed word may follow a digit, so that "hd" finds "1500HD" | Known limitation |
| 7.15 | Any query | Cutoff, inference margin and diversity penalties may not generalise | They were tuned on the same 20 queries they are measured on | Known limitation |
| 7.16 | Empty search box | The latest list does not change after re-ingesting | It is read from the database once per app start | Known limitation |
| 7.17 | First search after an idle period | A few seconds slower; a Render cold start is still possible; normal searches take 1–2 s | The keep-alive pings Render every 10 minutes so it normally doesn't sleep, but GitHub's scheduled runs can be delayed or skipped. Neon suspends when idle, since the ping doesn't touch the database. Embedding the query is slow at 0.1 CPU | Known limitation |
| 7.18 | Empty search box, `newest` | The latest cars are 2017 models, and prices are their original MSRP | The dataset covers model years 1990–2017 | Known limitation |
| 7.19 | `"%'` | Lists every car, newest first, as if the query were filters only | Punctuation is stripped, leaving no words to rank by | Known limitation |

## Why the limitations are accepted

- **Nonsense queries (6.2, 6.3, 7.2, 7.3):** their best similarity (0.26–0.39) overlaps real queries such as "exotic" (0.37)
  and "sporty" (0.34). Raising the similarity floor to 0.30 would drop 44 "exotic" and 32 "sporty" results and still
  not stop "zzzz".
- **Colour and model gaps (6.4, 6.5, 7.4–7.6):** the dataset has no colour or seat data and no MINI cars. Generating fake
  values was rejected, so these return the nearest cars by meaning.
- **"vehicle for a big family" (1.5, 7.1):** "big" matches Size: Large, which the two cars also match, so they get partial
  credit and survive the cutoff. Left unfixed so the held-out set stays honest.
