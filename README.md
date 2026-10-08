# Semantic Car Search

Try: `trucks` · `electric SUV under 60k` · `family car with lots of space` · `suv not electric` · `Civic` ·
`under 30k` (append `/?q=...` to the URL to link straight to a search)

## 1. What it does

A single search box over about 11,200 cars (Kaggle "Car Features and MSRP") that ranks by meaning, not keyword
overlap. "trucks" returns pickups from many makes and nothing else at any rank. Queries are parsed into hard
filters (price, make, explicit body type or fuel) and soft preferences, then ranked by a blend of embedding
similarity, full-text match and how well each car fits the parsed preferences. Results are grouped into one card
per make + model + year, and every ranked card shows its score breakdown. Before anything is searched (or after
an empty search) the page lists the latest model-years, and the search box suggests makes, models and search
words as you type.

Java 21 · Spring Boot 3.5 · PostgreSQL + pgvector (Neon) · all-MiniLM-L6-v2 run in-process (ONNX) · React + Vite.

## 2. How to run

Prerequisites: Java 21, Maven 3.9, Node 20+, and Docker (for the local Postgres + pgvector database).

```bash
cp .env.example .env    # pick any DATABASE_PASSWORD; the other values match docker-compose.yml
docker compose up -d db # Postgres 16 + pgvector on localhost:5433, data kept in a Docker volume

# frontend: builds into backend/src/main/resources/static
cd frontend && npm ci && npm run build && cd ..

cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=ingest   # one time: schema, clean, embed, load (about 1 min)
mvn spring-boot:run                                      # http://localhost:8080
mvn test                                                 # unit tests

# evaluation (prints a table and writes eval/results/<timestamp>.md)
mvn spring-boot:run -Dspring-boot.run.profiles=eval
mvn spring-boot:run -Dspring-boot.run.profiles=eval "-Dspring-boot.run.arguments=--carsearch.eval.queries-path=../eval/heldout_queries.json"
mvn spring-boot:run -Dspring-boot.run.profiles=eval "-Dspring-boot.run.arguments=--carsearch.category-inference=false"
```

Frontend development: `npm run dev` in `frontend/` proxies `/api` to `localhost:8080`; `npm run lint` lints.

As a Docker container, sized for a 512 MB host:

```bash
docker build -t semantic-car-search .
docker run --rm -p 8080:8080 -m 512m --env-file .env semantic-car-search
```

### Deploy (Render + Neon, both free)

1. **Neon:** create a project in AWS US East 2 (Ohio), next to the Render service's Ohio region. Under **Connect**, turn
   connection pooling off and copy the connection string; `.env.example` shows how to turn it into the three
   `DATABASE_*` values. Flyway creates the schema, including the `vector` extension, on the first run.
2. **Load the data into Neon once**, from your machine (environment variables override `.env`):
   ```bash
   cd backend
   DATABASE_URL='jdbc:postgresql://<host>/<db>?sslmode=require' DATABASE_USER='<user>' DATABASE_PASSWORD='<password>' \
     mvn spring-boot:run -Dspring-boot.run.profiles=ingest
   ```
3. **Render:** New → Blueprint → this GitHub repo. `render.yaml` defines one free Docker web service; enter the
   three `DATABASE_*` values when asked. The health check is `/api/health`.
4. **Keep-alive:** in GitHub, Settings → Secrets and variables → Actions → Variables, add `APP_URL` = the Render
   URL. `.github/workflows/keepalive.yml` then pings `/api/health` every 10 minutes so the free service does not
   sleep. That endpoint never queries the database, so Neon can still suspend when nobody is searching.

## 3. Architecture

Three layers, kept plain (no custom exceptions, no mapping library):

- **controller/** `ApiController`: `/api/search`, `/api/latest`, `/api/suggest` and `/api/health`, input checks
  that return `400 {"error": ...}`.
- **controller/dto/** the API's response shapes (`SearchResponse`, `ResultDto`, `CarDto`, `ScoreDto`, `FilterDto`,
  `ErrorResponse`, `HealthResponse`), built from domain records by small static `from(...)` methods, so internal
  fields never reach the JSON and the domain can change without breaking the frontend.
- **service/** `SearchService` (runs the pipeline and fetches candidates), `LatestService` (the list shown before
  any search), `CategoryInferrer`, `Lexicon` (vocabulary), and three interfaces with one implementation each:
  `QueryParser` → `RuleBasedQueryParser`, `EmbeddingService` → `OnnxEmbeddingService` (in-process model),
  `RankingStrategy` → `WeightedRanking`.
- **repository/** `CarRepository`: all SQL, including the WHERE built from the parsed filters.
- **domain/** plain records (`Car`, `ParsedQuery`, `CarGroup`, ...) used inside the backend.
- **ingest/** and **eval/**: one-off jobs, run by Spring profile.

A request flows: `ApiController` → `SearchService` parses the query, embeds it, optionally infers a category,
fetches dense and full-text candidates from `CarRepository`, ranks them with `WeightedRanking`, and returns one page,
which the controller turns into a `SearchResponse`. Search tuning numbers are constants at the top of
`WeightedRanking`, `CategoryInferrer` and `SearchService`, each with a comment on where it came from.

## 4. Pipeline

**Parse.** `QueryParser` lowercases the text and applies rules: price phrases ("under 30k", "between 20k
and 35k", "around 30k") become price filters; a make ("ford", "chevy") becomes a make filter; lexicon words become
soft preferences (body type, fuel, drivetrain, transmission, size, category such as "luxury" or "fast", and
"cheap"/"fuel efficient"); "not/no/non/without/except" before a word turns it into an exclusion. What is left
(usually a model name like "Civic") is the residual text for full-text search.

**Gate.** An explicit body type or fuel word ("truck", "electric") becomes a SQL filter, so contradicting rows are
never retrieved. If the query names no body type or category, `CategoryInferrer` compares the query embedding with
short category descriptions and may add a soft, half-weight, never-gated preference (switchable with
`carsearch.category-inference` in `application.yml`). A category the user excluded ("not truck") is never guessed;
the others still can be ("family car not minivan" → SUV).

**Filters only.** If nothing is left to rank by (no preferences and no leftover words, e.g. "under 30k",
"not electric", "ford"), every car that passes the filters is equally relevant, so the response lists all of them,
newest first, without scores, and skips the embedding model. Ranking by the embedding of "under 30k" would be
meaningless, and the similarity floor would drop nearly every car.

**Retrieve.** Two sources under the same `WHERE`: dense (500 nearest neighbours by cosine distance, HNSW index) and
lexical (200 best `ts_rank_cd` matches on the residual text). Candidates are merged by car id.

**Blend.** `final = α·semantic + β·lexical + γ·constraints`, each signal scaled to [0, 1] across the candidates.
α/β/γ = 0.45/0.20/0.35 when the query has preferences, 0.70/0.30/0 when it has none; with no residual text β is
added to α. The constraints signal adds +weight for each matched preference and −weight for each contradicted one
(body 3, fuel 2, category 2, drivetrain 1.5, size 1, transmission 1, "cheap"/"fuel efficient" 1.5).

**Cutoff.** Keep a row only if its score is at least 60% of the top score and its cosine similarity is at least 0.25.
The similarity floor is waived when the row contains the typed words or matches every explicit preference. For a pure
name search ("Civic", no preferences), if some cars contain the typed words, only those are kept. If nothing
survives, the response is empty with a message naming the filter that removed everything.

**Group.** One card per make + model + year; the card shows the best trim, the number of trims and the price range.

**Diversity.** Re-rank greedily: each group of a make already placed higher costs 0.015, each group of the same make
+ model costs another 0.03, capped at 0.10. Without it, three model-years of one truck filled the top of "trucks".
The penalty is part of the final score, so results stay sorted by score.

## 5. Design decisions and trade-offs

- **Render + Neon for hosting.** Both have free plans that do not expire, and Neon supports pgvector, keeping
  vectors, full-text search and SQL filters in one store. The trade-offs: Render's free service sleeps when idle
  (hence the keep-alive ping), and Neon's compute suspends when idle, so the first search after a quiet period
  waits a few seconds for it to wake.
- **Pretrained embedding model run in-process.** No API key, no quota, no cost and no third-party failure; it
  fits a small server. The trade-off is less nuance on vague queries than a large hosted model.
- **Rules instead of an LLM for query parsing.** Rules are deterministic, testable and explainable for every
  query; an LLM would add latency, cost and non-repeatable results. The trade-off is that unknown phrasings fall
  back to embeddings.
- **Hard filters for explicit body type and make.** Showing a sedan for "truck" or a Chevrolet for "ford trucks"
  is a failure at any rank, so those words remove rows instead of only lowering their score.
- **Hybrid retrieval.** Embeddings understand that "truck" means pickup; full-text search reliably finds exact
  names like "Civic"; neither alone handles both.
- **Return nothing rather than filler.** A short relevant list (or an explanation) is better than padding the
  page with weak matches.

## 6. Assumptions

1. **Dataset:** the public Kaggle "Car Features and MSRP" file (11,914 rows, 16 columns), because it is messy enough to show real cleaning (715 duplicates, missing values, placeholder prices).
2. **One word is enough:** there is no minimum query length, because "trucks" is the headline example.
3. **An explicit body type or fuel word is a hard filter:** showing a sedan for "truck" is a failure at any rank.
4. **A named make is a hard filter:** "ford trucks" must never show another make.
5. **Price is unknown for model years 2000 and earlier:** the MSRP median for those years is $2,000–$2,855 versus $22,768–$36,720 afterwards, so it is not a new-car price.
6. **"cheap" means preferring MSRP of at most $25,000:** a rough cut near the low end of post-2000 prices; it boosts, it never filters.
7. **"fuel efficient" means preferring at least 30 highway mpg:** a common rule of thumb; it boosts, it never filters.
8. **"under 30k" means at most $30,000, and "around 30k" means ±15%:** a simple, predictable reading.
9. **A bare number is a price only if it is at least 5,000:** so "400 hp" or "2015" are not read as prices.
10. **Negation applies to the next word only:** "suv not electric" excludes electric, nothing else.
11. **"off road" means SUV or pickup:** that is what people mean, and it avoids trusting drivetrain labels alone.
12. **One card per make, model and year:** listing every trim would bury different models.
13. **Weak matches are dropped instead of padded:** fewer relevant results beat filler.
14. **A small embedding model run in-process:** no key or cost and it fits a small server, at the price of less nuance.
15. **Price, horsepower and MPG are not embedded:** small models treat "$25,000" and "$95,000" almost the same, so numeric intent is handled by filters.
16. **Suggestions are prefix matches only:** makes, models and lexicon words that start with what was typed, at most 8; no typo correction, which keeps them instant and predictable.
17. **An empty search shows the latest cars:** newest model-year first, then the dataset's popularity score, so the page is never blank. Popularity is one number per make in this dataset, so within a year the most popular make comes first.
18. **A query that is only filters lists every match, newest first:** "under 30k" has no words to rank by, so all 875 matching model-years are equally relevant.
19. **The link must open without setup:** so the app is hosted (Render + Neon, free plans) with a keep-alive ping, and the home page's latest list is cached in memory so a visit does not wake the database.

## 7. SOLID mapping

This is a prototype, so the code favours being easy to read over textbook structure. What holds and what does not:

- **Single responsibility: mostly.** Parsing (`QueryParser`), embedding (`EmbeddingService`), SQL
  (`CarRepository`) and ranking (`WeightedRanking`) are separate classes. `WeightedRanking` does four ranking
  steps (blend, cutoff, group, diversity), and `SearchService` both runs the pipeline and fetches candidates.
- **Dependency injection: yes.** Every class gets its collaborators through the constructor, so each can be
  replaced in one place.
- **Dependency inversion and open/closed: yes, at the three points most likely to change.** `SearchService`
  depends on the `QueryParser`, `EmbeddingService` and `RankingStrategy` interfaces, not their implementations.
  An LLM-based parser, a hosted embedding API or a different ranking (e.g. Reciprocal Rank Fusion) is a new class
  implementing the interface; the pipeline does not change.
- **Interface segregation: yes, by keeping them small.** Each interface has only the methods its callers use
  (`QueryParser` and `RankingStrategy` have one each). Other classes have no interface, because they have one
  implementation and no expected replacement.

## 8. Evaluation

Labeled queries live in `eval/queries.json` (20 queries) and `eval/heldout_queries.json` (9 queries). Labels are
rules over dataset fields (for example "trucks": relevant = body type pickup, never = anything else), so they are
reproducible. **P@10** = share of the top 10 groups that are relevant. **Violations** = groups that must never
appear, counted at any rank. R1 = dense retrieval only (no parsing, no lexical search, no gate). R3 = the full
pipeline as served.

**Main set (20 queries).** This set was also used to choose the thresholds, so it flatters the system.

| Run | Mean P@10 | Violations |
| --- | --- | --- |
| R1 dense only | 0.740 | 42 |
| R3 full pipeline | **1.000** | **0** |

**Held-out set (9 queries, never used for tuning).**

| Run | Mean P@10 | Violations |
| --- | --- | --- |
| R1 dense only | 0.856 | 56 |
| R3 full pipeline | **1.000** | **2** |

(R1 here is an unfiltered approximate nearest-neighbour search, so it moves slightly when the HNSW index is
rebuilt: after the last re-ingest, "toyota suv" went from 17 to 33 R1 violations. R3 did not change.)

Both held-out violations are from "vehicle for a big family": a 2007 Bentley Azure convertible at rank 50 and a 2000
Cadillac Eldorado coupe at rank 63 (of 124). "big" maps to Size: Large, which these cars match, so they get partial
constraint credit and survive the cutoff. Not fixed, to keep the held-out set honest.

**Category inference on vs off** (main set; flag `carsearch.category-inference`):

| | Inference on | Inference off |
| --- | --- | --- |
| Mean P@10 / violations (R3) | 1.000 / 0 | 0.975 / 57 |
| "family car with lots of space" | infers SUV or minivan; top 10 all minivans; 0 violations | P@10 0.50; 57 violations |
| "something to haul lumber" (not labeled) | infers pickup; top 10 all pickups | top 10: 7 pickups, 2 vans, 1 minivan |

Full per-query tables: [eval/results/](eval/results/).

## 9. Known limitations

- **Dataset label errors flow through:** for example, a 2002 Chrysler Concorde sedan is labeled 4WD.
- **Small embedding model:** limited nuance on vague queries, which is why category inference uses a conservative threshold.
- **No horsepower, MPG or model-year parsing:** "over 400 hp" or "2015 or newer" fall back to embeddings.
- **Hand-tuned thresholds:** the cutoff, inference margin and diversity penalties were chosen on the same 20 queries they are measured on (see the comments next to the constants in `WeightedRanking` and `CategoryInferrer`).
- **Strict gate means short lists:** "electric SUV under 60k" returns only the Toyota RAV4 EV years.
- **"under 30" is not a price:** a bare number below 5,000 is ignored as a price (assumption 9), so it becomes
  search text instead.
- **Words the data cannot answer still return something:** there is no colour, seat-count or review data, so
  "red car" or "7 seater" return the nearest cars by meaning rather than saying "no match".
- **The latest list is cached:** it is read from the database once per app start, so restart the app after
  re-ingesting.

## 10. Scaling to production

- **Stateless app instances behind a load balancer.** The app holds no session state, so it scales horizontally.
- **Split embedding into its own service.** The in-process model is the main memory cost; a separate service lets the search API stay small and the model scale on its own.
- **Cache** query embeddings and popular query results (for example in Redis).
- **Database:** managed Postgres with read replicas for search traffic; at much larger scale, move vector search to a dedicated engine (OpenSearch or a vector database) and keep Postgres as the system of record.
- **Ingestion pipeline:** an asynchronous job with versioned embeddings, so a model upgrade can be re-embedded and swapped in without downtime.
- **Observability:** metrics and tracing for latency per stage, plus alerts on zero-result and low-score queries.
- **Quality loop:** run the evaluation (including the held-out set) in CI, and use click data to tune weights, eventually with a learned ranking model.
- **Safety:** rate limiting, input limits (query length and page size are already capped) and monitoring of the free-text field.
