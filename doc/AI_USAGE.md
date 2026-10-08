# AI usage log

AI assistants were used throughout: first to analyse the problem and write the requirements, then to implement, test and refine the application. Each entry gives the goal, the prompt, what came out of it, and whether it was kept.

Long prompts are reproduced verbatim in text blocks; short ones are quoted inline.

## Phase 1: Planning and requirements

**Tool / model:** Claude (claude.ai chat), Claude Opus 5.5 (`claude-opus-5-5`)
**Covers:** problem analysis, technology selection, architecture and the requirements specification (SRS)

### 1. Understanding the problem

- **Goal:** Work out what the assignment is asking for (RAG, model training, or something else) before writing any code, and get a starting architecture.
- **Prompt:**

```text
Aim: Fetching data based on semantic search.

Task: Build a small, self-contained *semantic search* application over a dataset of cars. The problem is intentionally open-ended: there is no detailed requirements document, so part of the task is making sensible assumptions and explaining them.

My idea: Create a search bar, frontend is any UI tech, backend I think can be Java, For database, Postgres should be good.
Application behavior:
A user types a search query into a search box, and the system returns the most relevant cars from a dataset, ranked by relevance.
- Search must be *semantic*, meaning it understands intent and meaning rather than only matching exact keywords.
- Example: if the user types *"trucks"*, the results should show all kinds of trucks (across makes and models), because the system understands the user wants trucks.
- *Failure case:* if a "trucks" search returns sports sedans or other non-truck vehicles, the search is considered to have failed.
- Single-word queries must work. There is no minimum number of words.
- The most important part of the assignment is the *ranking and ordering algorithm*: how results are retrieved, scored, and sorted. The search results page matters more than any other UI.


## Technical requirements
| Area | Requirement |
|---|---|
| Backend | *Java with Spring Boot* is strongly preferred |
| Frontend | Any framework or platform of choice |
| Database | Your choice (any database or search technology) |
| Cloud / hosting | Any provider, including free hosting tiers |
| Dataset | Use a public dataset, or design your own. Keep it a manageable size, but with *enough complexity* to demonstrate your skills |


## Architecture expectations
- Follow a standard, layered enterprise architecture: *controllers* handling endpoints, a *service layer* for business logic, and a separate *data/repository layer* for database access.
- Apply common design principles such as *SOLID*, but do not spend time on perfect object-orientation or a perfect data model.
- This is *not* a production system. You do not need to implement load balancing and similar infrastructure. A small, self-contained service is enough.


So before going forward with the implementation, help me first understand the expectations of this project, does it hint towards creating a RAG pipeline or training a model or something completely else because as soon as I heard the topic my head went semantic search. With that, Give me a starting rough structure of what the application architecture could look like. Ask me questions for clarity.
```

- **Outcome:** Framed the task as hybrid retrieval and ranking, not RAG (nothing is generated) and not model training (no labeled data; pretrained embeddings already encode "truck" vs "pickup"). Identified why pure vector search fails the "trucks" case (category leakage, no relevance cutoff) and proposed combining semantic similarity, full-text score, parsed query constraints, score fusion and a cutoff. Chose Postgres + pgvector so vectors, full-text and filters live in one store.
- **Decision:** Adopted; see the decision summary.

### 2. Constraints: $0 hosting, no-setup submission, embedding options

- **Goal:** Adapt the design to the submission constraints and compare embedding approaches.
- **Prompt:**

```text
What is scored with precision@10 or nDCG? 

Also this is a small scale Proof of Concept, not a full fledged system at the moment, just the functionality should work. 

For the submission I am expected to submit only a Github link, and I was told that they do not want any tedious setup process, there should just be a link that they can hit and the project should run. A hosted prototype link with no setup. This is hinting towards a cloud architecture deployed on the cloud, so that it is accessble anywhere anytime, however I need this to be a $0 architecture. This is the directory architecture recommendation. 


For the embeddings considering this new information, I want to you to give me some recommendations with the pros and cons. 

For the dataset, Kaggle Car Features and MSRP sounds good. 

For the third queston, Yes, parse price/fuel/body constraints
```

- **Outcome:** Defined the evaluation approach (labeled query set scored with Precision@10 and nDCG@10, labels derived from dataset fields). Designed a $0 deployment: a Render free web service serving both the React build and the API from one Docker image, Neon free Postgres with pgvector (Render's free Postgres expires after 30 days), and a cron keep-alive to avoid Render's spin-down. Compared embedding options (local ONNX model in the JVM, Gemini free tier API, other free APIs) on cost, quotas, reliability, memory and key handling, and selected a local ONNX model with precomputed car embeddings.
- **Decision:** Adopted; see the decision summary.

### 3. Writing the SRS

- **Goal:** Lock decisions into a single requirements document so context is not lost across sessions.
- **Prompt:**

```text
Alright, time for the detailed SRS, make sure to have all the details, the application architecture, data models, behavior, etc so the context does not get lost across chats. 

For the decisions, I agree with the current decisions and lets go with render free for now and oracle cloud as the secondary fallback. Add it in the SRS

The local embedding service is a good option. Ask me questions on decisions for the SRS. 

Separately, is there any AI usage.md file that gets generated that I can access for this chat? What is that and where can I find it. What is is supposed to have?
```

- **Outcome:** A 17-section SRS covering scope, assumptions, decision log, functional requirements, the full ranking algorithm (candidate generation, weighted scoring, category gate, relevance cutoff, tie-breaking, grouping), query parsing rules, schema and ingestion, layered architecture with diagram, API spec, frontend states, deployment with free-tier limits, non-functional requirements, evaluation plan, repo structure and production scaling plan. While budgeting free tiers, a keep-alive design flaw was caught: pinging the database every 10 minutes would exceed Neon's free compute hours, so the health endpoint was changed to not touch the database.
- **Decision:** Adopted.

### 4. Resolving open decisions

- **Goal:** Answer the open design questions raised in the SRS.
- **Prompt:**

```text
Q: When a query names a body type or fuel explicitly ("trucks", "electric"), what happens to cars that don't match?
A: Remove them (gate)

Q: How should result cards be grouped?
A: Make + model + year

Q: When is the submission due?
A: Within 24 hours
```

- **Outcome:** Explicit body type and fuel terms became a hard gate, which directly enforces the assignment's failure case. Results are grouped by make + model + year. The 24-hour deadline led to a delivery plan with must-ship, should-ship and deferred tiers, and a decision to deploy a skeleton early to test the 512 MB memory limit before building features.
- **Decision:** Adopted.

### Decision summary

| Area | Decision | Reason |
|---|---|---|
| Problem framing | Hybrid retrieval + rule-based ranking; no RAG, no training | Output is a ranked list; pretrained embeddings suffice |
| Backend | Java 21, Spring Boot 3, layered (controller, service, repository) | Assignment preference |
| Database | PostgreSQL + pgvector on Neon free plan | Vectors, full-text and filters in one store; permanent free tier |
| Embeddings | Local ONNX all-MiniLM-L6-v2 (384 dims), car embeddings precomputed | $0, no API key in a public repo, no quotas |
| Dataset | Kaggle Car Features and MSRP | Vehicle Style and Market Category give labels for parsing and evaluation |
| Query parsing | Rule-based: price, fuel, body type, make | Deterministic and explainable |
| Ranking | Weighted sum of semantic, lexical and constraint scores; body/fuel filters; four gates (fetch cap, name match, similarity floor, relative cutoff) | Prevents non-trucks in "trucks" results |
| Grouping | Make + model + year | One card per model-year instead of per trim |
| Frontend | React + Vite, served from the Spring Boot jar | One deploy, one URL |
| Hosting | Render free (primary), Oracle Cloud Always Free (fallback) | $0; fallback if 512 MB is not enough |
| Keep-alive | GitHub Actions cron every 10 min, no DB access | Avoids Render spin-down without using Neon compute hours |
| Evaluation | Labeled queries, Precision@10 and "never" violations (nDCG@10 was planned, then dropped) | Measurable evidence that ranking works; P@10 and violations answer the assignment's failure case directly |

## Phase 2: Implementation and refinement

**Tool / model:** Claude Code (VS Code extension), Claude Opus 5.5 (`claude-opus-5-5`)
**Date:** 2026-10-08

### 5. Show the latest cars when the search box is empty

- **Goal:** Stop the page being blank before the first search.
- **Prompt:** "When nothing is in the search bar we will show top latest cars? Right now when we launch the app nothing appears below unless you search."
- **Outcome:** Added `GET /api/latest` (one row per make + model + year, newest year first, then popularity), grouped in SQL, and made the frontend load it when the query is empty. A follow-up prompt asked whether clearing the box and clicking Search would show it again; the Search button was disabled for an empty box, so it was enabled, and the list was cached in memory (`LatestService`) so the home page does not query the database on every visit.
- **Decision:** Kept. Checked the endpoint, paging and errors against the local database.

### 7. Fix price-only and exclusion-only queries

- **Goal:** Make queries such as "under 30k" return every matching car.
- **Prompt:** "Yes" (to fixing the first bug).
- **Outcome:** When a query is only filters, with nothing left to rank by, `SearchService` now lists every matching car newest first, without scores, instead of ranking by the embedding of "under 30k". "under 30k" went from 1 result to 875 and "not electric" from 14 to 2,422.
- **Decision:** Kept. Ran the evaluation before and after: identical on both query sets.

### 8. Never guess a category the user excluded

- **Goal:** Stop category inference from boosting a body type the user excluded.
- **Prompt:** "Fix bug 2."
- **Outcome:** The first version turned off guessing for the whole field when anything in it was excluded, which made "family car not minivan" return cargo vans. It was changed to skip only the excluded values ("family car not minivan" now guesses SUV). Added unit tests.
- **Decision:** Kept the second version. The evaluation was identical before and after.

### 9. Trace the search flow for a fallback

- **Goal:** Find out what decides the remaining results when only a few strong matches come back.
- **Prompt:** "I'd like to add a fallback mechanism… if a semantic search query produces only 4 strong matches, what determines the remaining results? Please trace the current search flow before suggesting any changes."
- **Outcome:** Traced the pipeline with temporary instrumentation (reverted afterwards). Found five places where cars are dropped (SQL filters, a 500-candidate fetch limit, the name-match rule, a similarity floor and the 60% relative cut-off) and no fallback. Found that the fetch limit silently truncates large result sets ("trucks" showed 48 of 126 pickup model-years) and that the name-match rule matches parts of words ("red" in "Five Hundred", "mini" in "Minivan").
- **Decision:** Findings kept for later fixes; no code changed.

### 10. Related results from the 60% cut-off

- **Goal:** Evaluate the cars removed only by the 60% cut-off as a "Related results" source.
- **Prompt:** "Design how we should implement the 'Related results' section… the best candidates removed only by the relative 60% cutoff… Do not implement yet."
- **Outcome:** Designed the API, frontend, de-duplication, count, ranking and evaluation impact, and measured it first. Candidates existed for only 6 of 33 queries, none of them short-result queries, and many were exactly what the evaluation marks as wrong (for example convertibles for "family car").
- **Decision:** Rejected on the evidence.

### 11. Related results from the name-match rule

- **Goal:** Evaluate the cars removed only by the name-match rule as a "Related results" source.
- **Prompt:** "Let's investigate the name match rule as the source for the fallback instead… temporarily measure the candidates removed only because of the name match rule… Do not modify the application permanently."
- **Outcome:** Measured 46 queries with temporary instrumentation (reverted). Only 5 had such candidates; for Civic, Mustang, Camry and other model names there were none. Of the 5, two gave good related cars (Ford F-250 for "F-150", other BMWs for "bmw m3") and three were noise. No candidate violated any filter.
- **Decision:** Rejected as too narrow.

### 12. A simple "Related results" fallback

- **Goal:** Show a few extra cars when a search returns fewer than 10 good results, without relaxing any filter.
- **Prompt:** "Let's keep it simple. If the primary search returns fewer than 10 good results, show the normal results first and then a separate 'Related Results' section with a few additional cars that still satisfy the user's explicit filters… Don't implement it yet."
- **Outcome:** Proposed reusing the already-fetched, already-scored candidates: drop anything already shown, keep the existing similarity floor, and return up to 10 by the existing score, as a new `related` list in the API and a section under the main table. Measured examples: Civic → Honda Accord and Element; Mustang → Ford GT, Shelby GT350, Dodge Viper; bmw m3 → other BMWs; nothing extra for "diesel pickup" because no other cars meet its filters.
- **Decision:** Design accepted but not built before submission; fixing the two gate bugs found in entry 9 took priority.

### 13. Final check of the semantic logic and the four gates

- **Goal:** Confirm the code still matches what was planned, and get a clear explanation of the pipeline and the caps on results before the review.
- **Prompt:**

```text
Can you do a quick check so I can make sure everything's set up the way we want?

First, take me through the semantic search logic once, end to end. Then walk me through the 4 gates we use to cap the search results: what each one checks, when it kicks in, and how they work together to decide how many results we return.

If anything looks off or doesn't match what we planned, flag it.
```

- **Outcome:** Confirmed the code matched the last commit. Explained the pipeline (parse, filters, embed, fetch, score, gates, group) and the four gates: fetch cap (500), name match, similarity floor (0.25) and relative cutoff (60%). Flagged that the fetch cap cut "trucks" to 48 of 126 pickup model-years, that the name-match rule matched parts of words, that the evaluation reported P@10 but not the planned nDCG@10, and that "gate" was used for two different things in the code and docs.
- **Decision:** Fix the two gate bugs. Drop nDCG@10 rather than add it back. Use "gate" only for the four caps and call the body/fuel step "filters".

### 14. Fix the fetch cap and the name match

- **Goal:** Fix both gate bugs without changing the semantic logic, while staying safe for Render's free tier.
- **Prompt:**

```text
ok lets fix the two gate issues but keep the semantic logic simple, no new features or new scoring or restructuring

gate 1 - trucks should show all the trucks not stop at 48. we still need a cap since we're deploying on render free tier, so when filters have already narrowed things down (like body type) raise it to around 2000 and keep 500 for open ended queries. check memory and response time before and after so we know its still fine for render

gate 2 - switch to whole word matching so red doesnt match five hundred and mini doesnt match lumina minivan

run the eval before and after and make sure nothing else gets worse

also check why asdfgh still returns 1 result. if its a small fix a nonsense query should just return nothing, if not just tell me and we'll put it in known limitations

once thats done update the readme so it explains the 4 gates and the fetch cap, and add anything we're not fixing to known limitations
```

- **Outcome:** The fetch cap is now 2,000 when a filter narrows the set and stays 500 for open-ended queries; "trucks" and "pickup" show all 126 pickup model-years. Name matching uses whole words. Precision and violations were identical before and after on all three evaluation sets. In a container with Render's limits (512 MB, 0.1 CPU), peak memory stayed at about 357 MiB and the largest filtered searches were 0.3–0.7 s slower. For "asdfgh", similarity scores showed nonsense and real queries overlap (asdfgh 0.255, zzzz 0.385, "exotic" 0.367, "sporty" 0.338), so no floor separates them. The README gained a four-gates table and new Known limitations entries. Side effect found: "hd" no longer matched "Sierra 1500HD".
- **Decision:** Kept. Kept a cap for Render instead of removing it. "asdfgh" left as a known limitation rather than tuning the floor.

### 15. Restore "hd" matching

- **Goal:** Fix the "hd" regression from entry 14 without bringing back partial-word matches.
- **Prompt:**

```text
looks good. for hd can you also match a word stuck to a number so hd still finds sierra 1500hd but red still doesnt match five hundred. if thats more than a small change skip it and keep it in known limitations

rerun the tests and the eval after if you change it

ignore the ai usage entry, i'll handle that

then commit everything as one commit and push
```

- **Outcome:** A typed word may now start at a word boundary or right after a digit, so "hd" matches "1500HD" while "red" in "Hundred" does not. 41 tests pass, the evaluation is unchanged, and Civic, F-150, bmw m3, sierra 1500 and trucks give the same results. Committed and pushed as one commit.
- **Decision:** Kept.

### 16. Load the data into Neon

- **Goal:** Get the production database ready for Render without exposing the password.
- **Prompt:**

```text
i put the neon connection string in neon.env.local (its gitignored). dont print the password anywhere

turn it into the jdbc url, user and password the way .env.example says. first check if neon already has the data, car count and embedding count should both be 11199. if they do skip the ingest, if not run the ingest against neon with those values passed inline, dont change .env

then tell me the 3 values to paste into render, with the password hidden, i'll copy it from the file myself
```

- **Outcome:** TODO: fill in what it found (data already present, or ingest run) and the counts.
- **Decision:** The Neon password was reset before deploying. Render was then set up from the repo's `render.yaml` Blueprint, with the three database values entered as secrets in the Render dashboard.

### 17. Bring the README in line with what was built

- **Goal:** Check the README against the code, the evaluation results and git history, and fix anything out of date without changing code.
- **Prompt:**

```text
the readme might be out of date with what we actually built. go through it section by section and check it against the current code, the eval results and git history. update anything that doesnt match

make sure it covers:

the live link at the top: https://semantic-car-search-cfwe.onrender.com
deployment is done on render + neon, not planned. keep the deploy steps but make them match what we actually did
the 4 gates and the fetch cap (500 open ended, 2000 when filters narrow it), whole word name matching and the hd after-a-number rule
“gate” only means the 4 caps, body/fuel/price/make/exclusions are “filters” everywhere
eval numbers match eval/results exactly (the big family query shows 125 groups now, readme says 124)
add a link to TEST_SCENARIOS.md in the evaluation section
remove or mark anything we planned but didnt build, like ndcg, the oracle cloud fallback and related results. put related results and real colour data under future work
assumptions and known limitations still match the code

dont change any code. dont invent anything, if you’re not sure about something ask me. keep the existing structure and style, dont make it longer than it needs to be
```

- **Outcome:** Most of the README already matched. Added the live link, a line saying the deploy steps are the ones used for the live app, a link to TEST_SCENARIOS.md, and a Future work section (related results, real colour data). Corrected 124 to 125 groups for "vehicle for a big family". Reworded the name-match and similarity-floor gates, because the code keeps any car that contains a typed word (a full-text match too, not only the make or model name). nDCG and the Oracle Cloud fallback were not in the README. Flagged that the gate 4 example ("409 of 500 candidates removed") was not backed by any file, and that TEST_SCENARIOS.md was untracked.
- **Follow-up prompt:**

```text
keep it, it was measured during the gate check with temporary logging (91 of 500 kept, so 409 removed). its an open ended query so the fetch cap and gates didnt change for it. write it as “409 of 500 candidates removed, leaving 35 model-years” so it ties to the eval results
```

- **Outcome:** The gate 4 example now reads "409 of 500 candidates removed, leaving 35 model-years", matching the 35 groups in `eval/results/main.md`.
- **Decision:** Kept.

### 18. Move the known limitations to LIMITATIONS.md

- **Goal:** Make the limitations easier to read by grouping them by scenario in their own file, and fill in the ones that were missing.
- **Prompt:**

```text
can we move the known limitations out of the readme into their own file, LIMITATIONS.md? no code changes

group them by scenario so they’re easier to read, like query understanding, ranking and gates, stuff the data cant answer, nonsense queries, filters and short lists, performance and hosting, and data quality. keep everything thats already in the readme

a few things i think are missing, add them if they arent there:
mini cooper gives unrelated cars since theres no MINI in the data, the 2 big family violations, the first search after idle being slow (up to about a minute since render sleeps and neon suspends, normal ones are 1-2s), a number like 500 matching sierra 1500 because of the after-a-digit rule, and that synonyms are hand written so things like heavy duty are left to meaning on purpose

also check what years the data covers. if it stops at 2017 mention that the latest cars are 2017 models and prices are original MSRP

in the readme just keep section 9 with 2-3 lines on the biggest limitations and a link to the new file, keep the numbering the same

make sure it lines up with section 7 in TEST_SCENARIOS.md, same numbers and wording, and keep each point short

show me what you changed first, then commit the readme, LIMITATIONS.md and TEST_SCENARIOS.md together
```

- **Outcome:** Created LIMITATIONS.md with seven scenario groups, each item labelled with its TEST_SCENARIOS section 7 number and using the same wording. Kept every README item. Checked the data: it covers model years 1990–2017 and has no MINI cars. Checked the code: "500" does match "1500" through the after-a-digit rule, and "heavy duty" is not in the word list. Rows 7.1–7.11 stayed as they were; 7.12–7.18 were added for the new items and for README items that had no row (small model, hand-tuned thresholds, cached latest list, 2017 data). README section 9 became a three-line summary with a link. Committed the three files as one commit, without waiting for review of the changes. Flagged that the keep-alive ping should stop Render sleeping if `APP_URL` is set, and that row 6.9 was still "To check".
- **Follow-up prompt:**

```text
APP_URL is set now so the keep-alive is running. update 7.17 to say: the keep-alive pings render every 10 minutes so it normally doesnt sleep, but github’s scheduled runs can be delayed or skipped so a cold start is still possible. neon also suspends when idle since the ping doesnt touch the database, so the first search after a quiet period can be a few seconds slower

for 6.9 i havent measured it, change the outcome to “not measured” and the expected to match 7.17. dont leave it as to check
```

- **Outcome:** Updated 7.17 in both files. Row 6.9 now expects a few seconds' delay while Neon wakes, or a Render cold start if a keep-alive run was missed, with the outcome "Not measured". The README section 9 summary was changed to match, since it still said "up to about a minute".
- **Decision:** Kept.

### 20. Pre-submission review as a first-time reviewer

- **Goal:** Check the repo, the live app and the docs the way a reviewer opening the GitHub link would, without changing anything.
- **Prompt:**

```text
before i submit, do a final check as if you’re the reviewer opening the github link for the first time. dont change anything, just report

is everything committed and pushed, and does github match my local copy. is the repo public
search the whole git history for passwords, connection strings or api keys, even ones that were deleted later. make sure neon.env.local and .env were never committed
check every link in the readme, LIMITATIONS.md and TEST_SCENARIOS.md works, including the live app link
hit the live app: /api/health, then trucks, Civic, hd and under 30k, and check the counts match the readme and TEST_SCENARIOS
check the keep-alive workflow has run at least once in the actions tab
check every number in the readme, LIMITATIONS.md and TEST_SCENARIOS matches the others and eval/results
anything a reviewer would find confusing, missing or embarrassing

give me a short list sorted by how bad it is, worst first, so i know what to fix before 5
```

- **Outcome:** Nothing was changed. The repo was public, and no Neon credentials, `.env` or `neon.env.local` appeared anywhere in the history. The only database values in history were the local Docker placeholders in `.env.example`. The live app was up, and every count checked (trucks 126, Civic 8, hd 1, under 30k 875, and others) matched the README, TEST_SCENARIOS and `eval/results/`, as did every evaluation number across the docs. Problems, worst first:
  - GitHub was one commit behind and the move into `doc/` was uncommitted, so the GitHub README had no live link and no LIMITATIONS or TEST_SCENARIOS.
  - The keep-alive's only run had been skipped because `APP_URL` was set ten minutes later.
  - Four TEST_SCENARIOS rows were still "To check" (the AI ran them on the live app).
  - Minor items: a punctuation-only query lists every car, the repo's "About" link was empty, and `.gitignore` names a Claude spec file.
- **Decision:** Acted on in entry 21.

### 21. Fill in the checked rows, commit and push

- **Goal:** Record the live-app results in TEST_SCENARIOS, document the punctuation-only query, and get GitHub in line with the local copy.
- **Prompt:**

```text
commit everything pending and push. also fill in the 4 to check rows in TEST_SCENARIOS with what you found: 1.10 heavy duty truck 126 all pickups, heavy duty models only partly at the top so mark it partial. 6.6 0 results with the filter message, pass. 6.7 special characters no error and 600 chars gives a 400, pass. 6.8 paging 20 per page, page 6 has 6, page 7 empty, pass. and for 6.9 write not measured

add one line to LIMITATIONS that a query of only special characters like “%’ lists every car as if it were filters only. then commit and push and confirm github matches local
```

- **Outcome:** Filled in rows 1.10 (Partial), 6.6, 6.7 and 6.8 (Yes). Row 6.9 already said "Not measured", so it was left as it was. Removed the now-unused "To check" definition. The new limitation became 7.19, with a matching row in TEST_SCENARIOS section 7, because LIMITATIONS numbers follow that section. Its reason ("punctuation is stripped") was checked against the parser. The new `doc/ASSUMPTIONS.md` and the shortened README section 6 were included in the commit. A failed `git add` meant the first push sent only the existing commit `9ba492f`, and the new commit `6fda9a3` was pushed after it. After that, local and GitHub matched with nothing left uncommitted.
- **Decision:** Kept.

### 22. Design notes: routing, fallbacks, hybrid search and scaling

- **Goal:** A one-page note for the design review that separates what is built from what is only proposed.
- **Prompt:**

```text
write a short design note at doc/DESIGN_NOTES.md, no code changes. ground everything in the current code and label each part BUILT or PROPOSED, dont overclaim

query routing by cost: how queries already take different paths (filter only skips the model, name search, vague query with category guessing) and what each costs. then proposed: send low-confidence or unparsed queries to an LLM parser as a last tier, only when rules and embeddings arent confident, so the expensive path is rare
fallbacks: whats built (empty result names the filter, filters never relaxed) and proposed (related results as already designed, relaxing soft preferences but never hard filters, a vocabulary check so gibberish like asdfgh returns no match)
hybrid search: why weighted blend of semantic, lexical and constraints, and proposed: compare against reciprocal rank fusion using the existing eval
scale and traffic: embedding the query is the slow part (1-2s at 0.1 cpu), so a query embedding cache is the cheapest win. then stateless instances behind a load balancer, embedding as its own service, read replicas, rate limiting

keep it to about one page, link it from readme section 10. commit and push
```

- **Outcome:**
  - **Grounding:** read `SearchService`, `CategoryInferrer`, `WeightedRanking`, `CarRepository` and `ApiController` first. Timed each path on the live app (server-side `tookMs`, warm): filters only 11–164 ms, name search about 0.7 s, ranked with a category guess 0.5–0.7 s.
  - **Wording:** the note says the blend weights were chosen in the initial design and not tuned, as the code comment says. The Reciprocal Rank Fusion comparison is marked "not run yet". The only built scaling features listed are the ones that exist: no session state, the in-memory latest list, and the caps on query length and page size. It states that there is no query cache and no rate limiting.
  - **Result:** about 650 words, linked at the top of README section 10, committed and pushed as `c3a5610`.
- **Decision:** Kept.

## Human review notes

- **Kept a fetch cap for Render.** I didn't want to remove the cap entirely because the app runs on Render's free tier. I asked for 2,000 on filtered queries and 500 on open-ended ones, and for memory and response time to be measured in a Render-like container before accepting it.
- **Asked for the semantic logic to stay simple.** I limited the gate fixes to small, contained changes: no new scoring, no restructuring, and a "tell me if it isn't small" exit for the "asdfgh" and "hd" issues.
- **Dropped nDCG@10.** It was in the original plan, but I chose to report Precision@10 and "never" violations only.
- **Checked results before and after every change**, using the evaluation sets and live queries, and reviewed the side effects (the "hd" regression) before committing.
- **Rejected adding random car colours** so that "red car" would work. Generated colours would be fake data and make the results look more capable than they are; colour stays a known limitation and a future-work item that needs real listing data.
- **Rejected making "heavy duty" a truck filter.** Only explicit body words ("truck", "pickup") become hard filters; vague phrases are left to semantic search so cars like heavy-duty vans aren't wrongly excluded.
- **Kept credentials out of the AI chat.** The Neon connection string went into a gitignored local file, and the AI was told not to print the password.
- **Settled the terminology** so "gate" means only the four caps on results and "filters" means price, make, body, fuel and exclusions.
