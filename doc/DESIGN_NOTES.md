# Design notes

Each part is labelled **BUILT** (in the code now) or **PROPOSED** (not built). Timings are server-side `tookMs`
from the live app on 2026-10-08 (Render free tier, 0.1 CPU), warm, two runs each.

## 1. Query routing by cost

**BUILT.** `SearchService.search` already sends queries down paths of different cost:

| Path | When | What runs | Measured |
| --- | --- | --- | --- |
| Filters only | Parser finds no preferences and no leftover words ("ford", "under 30k") | One SQL query, newest first. No embedding, no ranking | 11–164 ms |
| Name search | Only leftover words ("Civic", "bmw m3") | Embed, dense + full-text retrieval, then the name-match gate keeps only cars whose make or model contains a typed word (`WeightedRanking.cutoff`) | ~0.7 s |
| Ranked with category guess | No body type or category named ("family car with lots of space") | Embed, then `CategoryInferrer` compares the query vector with 12 cached category vectors (no extra model call), then retrieve and rank | 0.5–0.7 s |

The jump from milliseconds to most of a second is the embedding model plus vector search. Skipping the model when it
adds nothing is the main saving that exists today.

**PROPOSED: an LLM parser as a last tier.** Call an LLM only when the cheaper tiers are not confident: the rule parser
found nothing, and `CategoryInferrer` made no guess (best similarity under its 0.50 minimum) or the top similarity is
low. The LLM would return the same `ParsedQuery` structure, as a new `QueryParser` implementation, so the pipeline
does not change. Most queries would never reach it. Its latency, cost and non-repeatable output are why rules come
first (README section 5).

## 2. Fallbacks

**BUILT.**
- Filters are never relaxed: body type, fuel, make, price and exclusions are SQL `WHERE` conditions.
- An empty result says why. `SearchService.explainEmpty` names the filter whose removal would bring back the most cars,
  or says no single removal helps.
- Nothing pads a short list. A strict query such as "electric suv under 60k" returns 3 results.

**PROPOSED.**
- **Related results** (designed in README section 11): when fewer than 10 results come back, show up to 10 more from
  candidates already fetched and scored, still within the filters and the 0.25 similarity floor.
- **Relax soft preferences, never hard filters:** retry with fewer soft preferences before returning nothing.
- **Vocabulary check for gibberish:** if no word is known to the lexicon, makes, models or full-text index, return
  "no match" instead of nearest neighbours. Today "asdfgh" returns 1 car, and "zzzz" 22 (LIMITATIONS 7.2, 7.3).

## 3. Hybrid search

**BUILT.** `WeightedRanking` blends `0.45·semantic + 0.20·lexical + 0.35·constraints` (0.70/0.30/0 with no
preferences), each scaled to [0, 1]. A blend keeps each signal's size, so a car that matches every preference can
outrank a slightly closer embedding, and every card can show its breakdown. The code comments say the weights were
chosen in the initial design and not tuned.

**PROPOSED: compare with Reciprocal Rank Fusion.** RRF combines ranks instead of scores, so it needs no scaling or
weights. It would be a second `RankingStrategy` scored with the existing evaluation (`eval/queries.json` and
`eval/heldout_queries.json`, P@10 and violations). Not run yet.

## 4. Scale and traffic

**BUILT.** No session state; the latest list is cached in memory per instance. Query length is capped at 200
characters and page size at 50. Nothing else is cached, and there is no rate limiting.

**PROPOSED,** cheapest first:
1. **Query embedding cache** keyed by the normalized query. Embedding is the slow part (README: about 1–2 s per search
   at 0.1 CPU), and popular queries repeat, so this is the cheapest win.
2. **Stateless instances behind a load balancer.**
3. **Embedding as its own service,** so the search API stays small and the model scales separately.
4. **Read replicas** for search traffic.
5. **Rate limiting** per client.
