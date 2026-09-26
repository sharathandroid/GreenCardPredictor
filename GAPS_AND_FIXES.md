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

## Data gaps to close next (not fixed in this pass)

- **Real family-preference visa usage** for `getFamilyVisasUsedPriorFiscalYear()` — the correct
  source is DOS's Visa Office Annual Report Table VI ("Immigrant Visas Issued at Foreign Service
  Posts") combined with USCIS family-based I-485 approvals, not currently parsed by this app.
- **AOS vs. consular-processing distinction** — Proclamation 10998's suspension is strongest for
  consular issuance abroad; a June 5, 2026 district court ruling allowed some domestic USCIS
  adjustment-of-status processing to continue for affected nationals. `Applicant` has no field
  for "filing domestically vs. through a consulate," so the model currently can't distinguish
  these, and treats a restricted country as fully blocked either way. That's conservative (never
  overpromises a wait time) but may understate what's actually still possible via AOS.
- **Cross-chargeability (INA 202(b))** — e.g. a spouse born in a different, less-backlogged
  country can sometimes be charged to that country instead. Not modeled at all.
- **Config-driven restricted-country list and bulletin dates** instead of source-code constants
  (flagged in #3 and #5 above).

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
