# Assumptions

The brief was open-ended on purpose, so these are the calls I made where it was unclear, and why. The detailed,
numbered list (thresholds, price rules, parsing rules) is in [README section 6](../README.md#6-assumptions).

## Where the brief was unclear

| What was unclear | What I assumed | Why |
| --- | --- | --- |
| What "semantic" should mean | Understand intent, not just keywords. Rules handle what must be exact (body type, price, make) and the embedding handles the meaning and the ranking | Pure vector search put sedans in "trucks"; pure keywords miss "family car" |
| What "trucks" covers | Pickups, from every make | That is what people mean by trucks, and the brief asks for all kinds across makes |
| How strict "no sedans for trucks" is | An explicit body or fuel word is a hard filter that is never relaxed, at any rank | The brief calls a sedan in "trucks" a failure, so lowering its score is not enough |
| What one result is | One card per make + model + year, showing the best trim, trim count and price range | Listing every trim would bury different models under one |
| How many results to show | As many as are relevant. No fixed count and no padding of short lists | A short correct list is better than filler |
| Vague queries ("family car", "heavy duty") | Guess a category softly as a boost, never as a filter | Vague words don't strictly mean one body type, so a filter would wrongly drop cars |
| Queries that are only filters ("under 30k", "ford") | List every match, newest first, without ranking by meaning | There are no words to rank by, so every match is equally relevant |
| When filters rule everything out | Return nothing, with a message naming the filter that removed the most cars | Better than showing cars that break what the user asked for |
| Gibberish ("asdfgh") | Not handled specially, so it can still return a few cars | No similarity threshold separates nonsense from real vague words like "exotic"; see [LIMITATIONS](LIMITATIONS.md) |
| Single words and codes | Must work, including model names ("Civic") and codes like "hd" | The headline example is a single word |
| "No tedious setup" | A hosted link that works for $0 (Render + Neon free plans) | Reviewers should only need to click a link |
| Dataset | Kaggle "Car Features and MSRP" used as it is: cleaned, but nothing generated | It has the fields needed for filters and labels; fake data would make results look better than they are |

## Decisions made while building

- **Fetch cap sized for the free tier.** Ranking stops at the 500 nearest cars for open-ended queries and 2,000 when a
  filter already narrows the set. I kept a cap because Render's free tier has 512 MB and 0.1 CPU; at 2,000, peak
  memory stayed about the same and big filtered searches got 0.3–0.7 s slower, which is acceptable.
- **Names match whole words.** "Civic" or "m3" must be a whole word of the make or model, so "red" doesn't match
  "Five Hundred". A word right after a number also counts, so "hd" finds "Sierra 1500HD".
- **"Heavy duty" is not a filter.** Only explicit body words like "truck" or "pickup" become filters; phrases like
  "heavy duty" are left to meaning so heavy-duty vans aren't wrongly excluded.
- **No generated data.** The dataset has no colour or seat counts. I didn't add random colours to make "red car"
  work, because that would be fake data.
- **Measured, not guessed.** Every threshold was checked against a labeled evaluation, plus a held-out set that was
  never used for tuning (see [TEST_SCENARIOS](TEST_SCENARIOS.md)).
