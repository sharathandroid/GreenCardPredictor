# GreenCardPredictor — Gap Analysis & Fixes (2026-09-25)

This document reviews the existing prediction logic against the actual statutory/regulatory
rules and the current (September 2026) real-world visa-restriction situation, and explains
every change made in this pass. It is meant to sit next to `PREDICTION_STRATEGY.md`.

## How this review was done

This analysis was first drafted without GitHub access, from the repository's synced knowledge
index (full text of every source file, `pom.xml`, the test suite, and `PREDICTION_STRATEGY.md`)
rather than a live checkout. Once GitHub access was granted, every file discussed below was
re-fetched live from `main` and reconciled against the drafted fixes before this branch/PR was
opened — the reconstruction turned out to be faithful, with one correction made during
reconciliation: the original PredictionService also modeled a "consular shutdown" simulation mode
(EB2 gets extra priority both in the EB2→EB3 vertical spillover and in the horizontal
redistribution weighting, plus a 70/30 pre-2015-backlog-weighted priority for redistributing
pooled visas). That mode isn't broken and wasn't in scope for this pass, so it's preserved as
originally designed rather than dropped — see the class-level comment in `PredictionService.java`
for exactly what was kept and why.

## 1. FB-to-EB spillover formula was structurally broken

**Old code** (`PredictionService.predict`):

```java
long i130Pending = excelDataService.getI130PendingCount();
fbToEbSpillover = Math.max(0, 226000 - i130Pending);
```

`i130Pending` is the **pending I-130 petition backlog** — a demand queue that has run
2.3–2.4 million deep for years (confirmed in your own `test_output*.txt` logs: `Total Pending:
2401268`). The statutory family-sponsored floor is 226,000. Subtracting a number in the
millions from 226,000 and flooring at zero means **this formula returns exactly 0 every single
time**, regardless of what's actually happening with family visas. It's comparing a stock of
unadjudicated petitions to an annual visa-issuance limit — wrong units, not just a wrong
constant. In practice the app was always running with `fbToEbSpillover = 0` unless a caller
set `manualFbSpillover` explicitly (your own tests only ever verified the *manual override*
path works, which is why this shipped without failing).

**What the real spillover rule is (INA 201(c)/(d)):** unused family-sponsored numbers roll to
the employment-based pool for the *next* fiscal year, computed from how many family-preference
visas were actually **issued/adjusted** in the prior fiscal year versus the family limit — not
from how many petitions are sitting in a queue.

**Fix:** `ExcelDataService.getFamilyVisasUsedPriorFiscalYear()` (new method) and
`PredictionService` now compare the family-sponsored limit against actual/estimated prior-FY
usage. **Update 2026-09-26:** this now returns a real parsed figure (433,071 — FY2025's domestic
I-485 "(Family)" approvals, from `quarterly_all_forms_fy2025_q4_v1.xlsx`) rather than a flat
placeholder. It's still not the fully authoritative number — it mixes uncapped immediate-relative
approvals in with capped family-preference approvals (USCIS's public report doesn't split them),
and it omits DOS's consular-issued family visas (Table VI), which `travel.state.gov` blocking
(HTTP 403 from this environment, confirmed on retry) prevented fetching. Both gaps are documented
on the method itself and in `DATA_SOURCES.md` #4. Falls back to the statutory floor (spillover = 0)
if the source file/row can't be parsed, same fail-safe as the original placeholder.

**Bonus, tied to "embassies closed":** the ongoing DOS worldwide immigrant-visa-interview pause
(started ~Aug 25, 2026, for consular retraining) hits **family-based cases disproportionately**
— ordinary employer-sponsored (EB) cases are largely unaffected by it. That is the real
mechanism by which "all embassies are closed" pushes numbers toward EB, and it's modeled now via
`Applicant.familyVisaPauseSeverity` (0–1), which reduces the family-usage figure before the
spillover subtraction. It is *not* modeled (and shouldn't be) as blocking EB issuance directly,
which the old restricted-country list was doing indiscriminately.

## 2. EB-4/EB-5's ~14.2% share was silently discarded

`CATEGORY_ALLOCATION` only assigns EB1/EB2/EB3 28.6% each (85.8% total). The remaining 14.2%
per country was computed nowhere and used nowhere — it simply vanished from the model instead
of participating in any spillover.

Per INA 203(b) and 9 FAM 502.1-1(D), the employment-based waterfall runs:
**unused EB-4 and unreserved EB-5 numbers → EB-1 → EB-2 → EB-3** (there is no statutory path back
out of EB-3, and the flow is one-directional — EB-3 leftovers do not cycle back up).

**Fix:** since this app doesn't model EB-4/EB-5 demand, `calculateDynamicSupply` now
conservatively treats each country's whole 14.2% share as "unused" and folds it into that
country's **EB-1** pool before the existing EB1→EB2→EB3 vertical waterfall runs. This is a
simplification (in reality EB-4/EB-5 do have some demand, mostly religious workers and
regional-center investors), but it is directionally correct and no longer destroys real supply.

## 3. The restricted-country list was pointing at a vacated policy

The old `Country.java` hardcoded 75 "restricted" countries modeled on the Department of State's
Jan 21, 2026 "public charge reassessment" pause on immigrant visa issuance. **That specific
policy was vacated by a federal judge on August 21, 2026** as exceeding DOS's statutory
authority ([Immigration Policy Tracking Project](https://immpolicytracking.org/policies/state-department-pauses-visa-applications-for-nationals-of-75-countries/)).
Continuing to tell users their green card is blocked under a court-vacated policy is itself a
bug, not a historical curiosity — it's actively wrong output.

The restriction that **is** still legally operative (as of 2026-09-25, subject to ongoing
litigation) is a different, 39-country list under **Presidential Proclamation 10998** (signed
Dec 16, 2025, effective Jan 1, 2026):

- **Full suspension** (immigrant + nonimmigrant): Afghanistan, Burma/Myanmar, Burkina Faso,
  Chad, Republic of the Congo, Equatorial Guinea, Eritrea, Haiti, Iran, Laos, Libya, Mali, Niger,
  Sierra Leone, Somalia, South Sudan, Sudan, Syria, Yemen, and Palestinian Authority travel
  document holders.
- **Partial suspension** (immigrant visas + B-1/B-2 + F/M/J suspended; other nonimmigrant
  categories like H-1B remain available): Angola, Antigua and Barbuda, Benin, Burundi, Côte
  d'Ivoire, Cuba, Dominica, Gabon, The Gambia, Malawi, Mauritania, Nigeria, Senegal, Tanzania,
  Togo, Tonga, Venezuela, Zambia, Zimbabwe.
- **Single-category**: Turkmenistan (immigrant visas only).

**Fix:** `Country.java`'s `RESTRICTED_COUNTRIES` set now reflects this 39-country list instead
of the vacated 75-country list, with the enum extended to include countries that weren't
represented at all before (Burkina Faso, Chad, Mali, Niger, Angola, Benin, Burundi, Gabon,
Malawi, Mauritania, Tonga, Zambia, Zimbabwe, Turkmenistan, Palestinian Authority — these used to
silently fall through `Country.fromString(...)` to `ROW`, which is also its own bug: any Excel
row for e.g. "Zambia" was being folded into the generic Rest-of-World bucket instead of tracked
separately). **Brazil** — previously restricted under the vacated 75-country order — is not on
the current travel-ban list and is now treated as a normal country with its own 7% cap, which
also fixes a pre-existing inconsistency where `PREDICTION_STRATEGY.md` described Brazil as one
of the "big 5" countries with its own cap while `Country.java` simultaneously zeroed out its
demand as "restricted."

**Caveat that matters more than the list itself:** this area of law has changed multiple times
within 2026 alone (announced Jan 14 → effective Jan 21 → partial injunction Jan 28 → vacated Aug
21 → worldwide pause imposed Aug 25, all just for the *other* policy). A hardcoded enum-level
list will go stale again. The durable fix is to externalize this table to a small config
resource (e.g. `restricted-countries.yml`) that can be updated without a rebuild — flagged here
rather than done, to keep this change reviewable, but worth doing next.

## 4. No representation for "Unauthorized" (annual limit already reached)

The September 2026 Visa Bulletin marks **India EB-2 Final Action as "U" (Unauthorized)** — the
per-country/category annual ceiling has already been hit ahead of the September 30 fiscal-year
end, common for high-demand categories late in the fiscal year. The old
`VisaBulletinService`/`PredictionService` had no concept of this state; an unmapped or
mis-handled entry would have defaulted to a wide-open `2010-01-01` cutoff, i.e. exactly the
opposite of reality.

**Fix:** `VisaBulletinService.isFinalActionUnauthorized(...)` plus a dedicated branch in
`PredictionService.predict(...)` that returns a clear, honest explanation instead of a
nonsensical wait-time number.

## 5. Visa Bulletin data was 7 months stale

`VisaBulletinService` was hardcoded to the **February 2026** bulletin. Today is September 25,
2026. Fixed to the actual **September 2026** bulletin (the most recent one published/indexed;
October 2026 — the first bulletin of FY2027 — was not yet available). Also worth flagging:
hardcoding one month's numbers into Java source is the recurring failure mode here — this file
will be stale again in a few weeks. Same recommendation as #3: externalize to a config resource
that's easy to refresh monthly, ideally scraped from `travel.state.gov` on a schedule.

## 6. Source Excel files were stale — FIXED 2026-09-26

`ExcelDataService` was pinned to `eb_inventory_october_2025 (1).xlsx`,
`i140_rec_by_class_country_fy2025_q3.xlsx`, and `quarterly_all_forms_fy2025_q3.xlsx`. The original
pass here left these in place because the sandbox that drafted it couldn't reach `uscis.gov`. A
later session confirmed this repo's actual working environment *can* reach `uscis.gov` (only
`travel.state.gov` is blocked) and did the real swap: downloaded and byte-verified the current
editions, fixed one hardcoded-column-index bug the newer I-140 file's extra year column exposed
(see `ExcelDataService.loadI140Data()`), and caught one wrong filename guess from the original
`DATA_SOURCES.md` draft before it could ship (a plausible-looking filename that turned out to be a
differently-shaped report). Full details, exact filenames, and what's still approximate are in
`DATA_SOURCES.md` #1–#4.

## 7. A latent string-matching bug in `Country.fromString`

While rewriting the restricted-country handling, found: the substring check order would have
matched `"NIGERIA"` against `contains("NIGER")` before ever reaching a `NIGERIA`-specific check,
because `"NIGERIA".contains("NIGER")` is `true`. This wasn't reachable in the old code (Niger
wasn't in the enum at all), but it becomes a real bug the moment Niger is added — which this fix
does, to support the new restricted-country list. Fixed by checking the more specific string
("NIGERIA") first. Worth double-checking the rest of the alias list for other substring
collisions as more countries get added.

## 8. Horizontal redistribution could over-allocate a country beyond its own demand — FIXED 2026-09-26

`calculateDynamicSupply`'s horizontal redistribution step split each category's pooled unused
visas (from restricted/low-demand countries) across oversubscribed countries strictly by a 70/30
weight (pre-2015 backlog / remaining demand), with **no cap** on how much any single country could
receive. Weight isn't the same thing as remaining demand, so a country with a large pre-2015
backlog but a since-shrunk remaining gap could still claim a big share — meaning its final supply
could exceed its own actual demand, silently wasting pool capacity that other still-needy countries
could have used, and producing genuinely non-monotonic results: increasing the total EB limit could
in some cases *decrease* a heavily-backlogged country's post-redistribution supply, because other
countries' over-shares shifted unpredictably as the numbers moved.

This was latent in the original design and not part of the four items above — it only surfaced as
a failing test (`testPredictWithManualFbSpillover`) once the real, much larger current backlog
figures (from the stale-data fix, item #6) replaced the smaller stale numbers that had never
exercised this path. **Fix:** the redistribution step now uses water-filling — each round, still-
needy countries get their weight-proportional share of what's left, capped at their own remaining
demand; anything a capped country couldn't use goes back into the pool for the next round — so no
country's final supply can ever exceed its own demand, and total supply is now monotonically
non-decreasing in the total EB limit. `testPredictWithManualFbSpillover`'s assertion was loosened
from "strictly decreases" to "never gets worse," since India's real EB3 demand turns out to already
be fully satisfied by the corrected pool even without extra spillover — see that test's updated
comment for why that's the correct outcome, not a regression.

## Data gaps closed 2026-09-26

The four gaps originally listed here are now addressed:

- **AOS vs. consular-processing distinction** — `Applicant.filingDomestically` now exists.
  When a restricted-country applicant sets it, `PredictionService` returns a distinct explanation
  acknowledging that domestic adjustment-of-status may still be possible (per the June 2026 ruling
  mentioned above), instead of the flat "issuance suspended" message. It still can't produce a
  numeric wait estimate for that path — the backlog data isn't split by filing location — so this
  changes the explanation, not the underlying supply/demand model. See `PredictionService.predict()`.
- **Cross-chargeability (INA 202(b))** — `Applicant.spouseCountryOfBirth` lets a beneficiary elect
  their spouse's country of birth instead of their own when that yields a more favorable outcome,
  per 9 FAM 503.2. `PredictionService` computes both countries' bulletin-anchored estimates and
  picks whichever is better; it never applies if it wouldn't help, or if the spouse's country is
  itself restricted. See `PredictionService.estimateWait()`/`WaitEstimate`.
- **Config-driven restricted-country list** — moved from a hardcoded `EnumSet.of(...)` in
  `Country.java` to `restricted-countries.yml` (parsed with SnakeYAML directly, since an enum can't
  be a Spring-managed bean). `Country` fails loudly at class-load time if that file is missing or
  names an unrecognized country, rather than silently under-restricting.
- **Config-driven bulletin dates** — moved from hardcoded constructor calls in
  `VisaBulletinService.java` to `visa-bulletin.yml`, bound via a real Spring Boot
  `@ConfigurationProperties` bean (`VisaBulletinProperties`). Refreshing the bulletin monthly is now
  a data-file edit, not a Java change/recompile.

## 9. Two more bugs found while fixing the above — FIXED 2026-09-26

Neither was on the original list; both were caught by manually sanity-checking a India/EB3/
2019-12-16 prediction against the fixes above and noticing the result (a ~2-month wait for a
priority date five years behind the bulletin's own cutoff) was obviously wrong.

- **Every country was getting its own individual 7% allocation**, not just the five actually named
  on the bulletin's per-country chart (India, China, Mexico, Philippines, Brazil).
  `calculateDynamicSupply` special-cased only `Country.ROW`; every OTHER of the ~100 enum values
  (nearly all of them near-zero real demand) also claimed a full 7% via the same `countryAnnualLimit`
  path ROW itself was meant to represent in aggregate. That let the "unused" remainder from ~95
  phantom allocations inflate the horizontal-redistribution pool far past the true global total.
  **Fix:** only `INDIVIDUALLY_CAPPED_COUNTRIES` (the five named countries) get `countryAnnualLimit`;
  everyone else gets 0 base allocation and can only receive supply via redistribution — matching how
  "All Chargeability Areas Except Those Listed" actually works on the real Visa Bulletin, and
  restoring the property that base allocations sum to exactly the total EB limit.
- **The family-visa-usage estimate (item #1) was made worse, not better, by a same-day refinement.**
  A same-day attempt to weight the raw 433,071 I-485 "(Family)" approval figure down using the
  immediate-relative/preference ratio observed in this workbook's I-130 rows (~8.6%) produced
  ~37,300 — which drove FB-to-EB spillover to ~188,700, nearly 2.4x the base 140,000 EB limit. The
  ratio was invalid: I-130 petition approvals and I-485 adjustment approvals in the same fiscal year
  are not the same population at the same pipeline stage (this year's approved preference I-130s
  mostly won't become I-485-eligible for years, so this year's I-485 preference approvals trace back
  to petitions approved years ago — there's no valid same-year ratio to borrow). **Fix:** reverted to
  the raw, unweighted 433,071 figure. See `ExcelDataService.loadFamilyVisaUsage()`'s Javadoc for the
  full account.

With both fixed, the same test case (India, EB3, priority date 2019-12-16) now returns Filing
January 2031 / Final Action October 2032 (annual supply ~9,807, essentially India's own 7% cap),
and the total distributed across all countries and categories sums to exactly the 140,000 base
limit (verified by summing every "FINAL SUPPLY" line in `calculateDynamicSupply`'s own debug output).

## 10. Real DOS Table VI data obtained — FIXED 2026-09-26

The family-preference-visa-usage gap above (originally #1, revisited in #9) is now closed with
real data. `travel.state.gov` remained unreachable from this environment the entire session (HTTP
403 on every direct attempt; every Wayback Machine snapshot since July 2024 captured a Cloudflare
block page instead of the real site) — but the project owner had independent access and provided
the DOS Bureau of Consular Affairs' Report of the Visa Office, Table VI ("Preference Visas Issued")
directly, as five PDFs (`Table VI_PartI.pdf` through `PartV.pdf`, kept at the repo root for
provenance).

Table VI Part I's family-preference grand total for FY2024 is **205,762** — real, DOS-authoritative,
split by preference category already (no immediate-relative contamination, unlike the domestic
I-485 figure this app used before), and specifically the *consular* issuance count that was always
the actually-missing half of this figure. `getFamilyVisasUsedPriorFiscalYear()` now returns this
value directly (see `family-visa-usage.yml` and `ExcelDataService.loadFamilyVisaUsage()`), replacing
the domestic-only, category-mixed 433,071 figure and its resource file entirely.

**Two caveats remain, both documented on the data file itself:**
1. **This is FY2024, not FY2026.** The events this project actually cares about (the Jan 2026
   travel-ban restrictions, the Aug–Sep 2026 consular closure) fall in FY2026, which hadn't closed
   by the time this was fixed — its Table VI won't be published for months. FY2024 is the most
   recent real anchor available, not a measurement of the disruption's actual effect.
2. **Still consular-only** — it excludes domestic USCIS family-preference I-485 adjustments (a
   different, smaller subset than what the old domestic figure counted), so it's a slight
   understatement of true total usage, biasing spillover estimates a bit high rather than low.

With this real figure (205,762, close to but under the 226,000 floor), FB-to-EB spillover computes
to a small, plausible **20,238** by default (206,000 → 226,000 gap) instead of either 0 (the old
domestic-mixed figure, which sat comfortably above the floor) or the invalid ~188,700 from the
reverted same-day ratio experiment (see #9). India/EB3/2019-12-16 — the running test case through
this whole investigation — now returns Filing March 2029 / Final Action February 2030 (annual
supply ~17,121), a real, defensible multi-year wait consistent with a priority date years behind
the bulletin's cutoff, using genuinely current statutory mechanics and the best real data available.

## 12. Filing was incorrectly blocked by Final Action's "Unauthorized" status — FIXED 2026-09-26

User-reported: India, EB2, priority date 2011-01-01 (four years senior to India EB2's real
September 2026 Filing cutoff of 2015-01-15) was returning `"N/A (Unauthorized this FY)"` for
**both** Filing and Final Action. Filing (Chart B) and Final Action (Chart A) are separate charts
on the real Visa Bulletin — DOS can mark Final Action Unauthorized (the annual ceiling is hit, no
more green cards can be approved this FY) while still publishing a real Filing cutoff, specifically
so people can submit their I-485/DS-260 and get interim benefits (EAD/AP) while waiting for final
numbers. That's exactly India EB2's real state on the current bulletin.

`estimateWait()` previously short-circuited to a sentinel the instant
`isFinalActionUnauthorized()` was true, before computing filing at all. **Fix:** filing is always
computed from its own cutoff/volume/supply, independent of final action's authorization status;
only final action collapses to "Unauthorized" when that's actually true. Verified against the
reported case: a 2011 priority date now correctly returns Filing "current" (already past the 2015
cutoff) with Final Action still Unauthorized.

## 13. EB-1 and EB-2 could never receive cross-country redistribution — FIXED 2026-09-26

Found by checking why India EB2's Filing wait computed to 743 months (62 years) — far worse than
EB3's, despite EB2 being comparably backlogged in reality. The server log showed zero
"Redistributing" lines had ever printed for EB1 or EB2, for any country, in any run — only EB3 ever
received cross-country redistribution.

Root cause: `calculateDynamicSupply` ran the full EB1→EB2→EB3 vertical waterfall for every country
FIRST, then horizontal (cross-country) redistribution second, per category. By the time horizontal
redistribution looked at EB1, every country's EB1 surplus had already been pushed down into EB2 by
the vertical step (that's what the vertical step does). Same for EB2 → EB3. So EB1's and EB2's
cross-country pool was mathematically guaranteed to be exactly zero, every time — 100% of unused
capacity worldwide got funneled into EB3 by construction, regardless of how oversubscribed a
country's EB1 or EB2 actually was. Real INA 203(b) / 9 FAM 502.1-1(e) mechanics run the other way:
unused numbers in a category are first offered to other oversubscribed countries in that *same*
category, and only what's still unused after that falls through to the next category down.

**Fix:** restructured the two separate passes (vertical loop, then horizontal loop) into a single
per-category loop in EB1→EB2→EB3 order — each category's own unused supply plus whatever fell
through from the previous category is redistributed horizontally first, and only the genuine
leftover cascades to the next category. (This is also the structural change that made item #14's
priority-date-ordered redistribution straightforward to build on top of.) India/EB2/2019-12-16's
Filing wait improved from 743 to ~520 months at the time — still extreme, but now reflecting
India's own genuinely large EB1 demand absorbing much of the shared pool first, not an algorithmic
bug that made EB1/EB2 redistribution structurally impossible.

## 14. Three more mechanics discrepancies vs. the actual INA text — FIXED 2026-09-26

Found by checking a detailed INA §201/202/203 spec the project owner supplied against the
running code. All three fixed:

1. **FB-to-EB spillover entry point.** Was blended into the total pool before the 28.6%
   category split (spreading it evenly across EB-1/EB-2/EB-3 at once). Now enters only at
   EB-1 (`calculateDynamicSupply`'s per-category carry-in is seeded with `fbToEbSpillover`
   instead of 0), matching INA 201(d)/203(b)'s actual entry point, and cascades down through
   the normal waterfall only if EB-1 doesn't use it all.
2. **Per-country cap base.** Was 7% of the spillover-inflated total (scaling the cap itself
   whenever spillover existed). Now a fixed 7% of the 140,000 statutory base (INA 202(a)(2)),
   consistent with spillover being an EB-1-only addition rather than a base-pool increase.
3. **Redistribution ordering.** Was a custom 70/30 (pre-2015 backlog / remaining demand)
   weighted split across oversubscribed countries. Now strict priority-date order (INA
   202(a)(5)): `PredictionService.buildPriorityQueue()` merges every oversubscribed
   country's remaining demand, broken down by priority-date year (`ExcelDataService`'s
   existing `getInventoryYearly()` plus the new `getI140Yearly()`), into one global
   year-sorted queue and fills it oldest-first regardless of country. This also means the
   "never exceed a country's own demand" invariant (fixed in #8) now holds by construction
   instead of needing the water-filling loop to enforce it.

Verified: total distributed supply still conserves exactly to the correct limit, all 13
tests pass. Results shifted in the expected direction — e.g. spillover now only helps a
country's EB-2 after its own EB-1 demand is satisfied, and the lower fixed cap reduces
baseline allocation for the five named countries versus the previous spillover-scaled figure.

## About GitHub repo access

This analysis was originally done without GitHub access (reconstructed from an indexed copy of
the repo), then reconciled line-for-line against the actual current files on `main` once access
was granted, before this branch/PR was opened. See the "How this review was done" note at the top
of this document for what that reconciliation changed.

## Tests

`PredictionServiceTest.java` has been rewritten in this PR. More tests needed changes than
originally expected once the fixes were reconciled against the live test file — see the class-
level comment at the top of that file for the full explanation. In short:

- `testSpilloverFromRestrictedCountries` (asserted explanation text that no longer exists) and
  `testAll75RestrictedCountriesAreRecognizedAndRestricted` (asserted the old, vacated 75-country
  list) were pinning down the bug, not a feature, and have been rewritten to match corrected
  behavior — as expected going in.
- `testPredictForIndiaEB2`, `testPredictWithManualFbSpillover`, and `testConsularShutdownMode` all
  used India EB2, which the real September 2026 bulletin marks "Unauthorized" (item #4 above) —
  this wasn't anticipated until the fixes were checked against real bulletin data. They're
  re-pointed at country/category combinations that exercise what they were actually testing
  (ordinary bulletin math, the manual FB-spillover override, and EB2 prioritization during a
  simulated consular shutdown) without hitting the Unauthorized short-circuit, and a new test
  (`testPredictForIndiaEB2Unauthorized`) covers the Unauthorized case directly.
- `testPredictForRestrictedCountry` used Russia, which is restricted under the old vacated policy
  but not under the current Proclamation 10998 list — re-pointed at Iran, which is on both.
- `testCountryFromStringAliasesAndEdgeCases` asserted `Country.fromString("Equatorial Guinea")`
  resolves to `ROW`; it now resolves to its own `EQUATORIAL_GUINEA` value (see item #3 and the
  test's own comment for why this was itself a bug, not just a naming change).

Everything else in the test file is unchanged and should still pass.
