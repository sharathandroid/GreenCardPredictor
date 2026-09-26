# Green Card Prediction Strategy

*Updated 2026-09-25. See `GAPS_AND_FIXES.md` for a full changelog of what was wrong before and
why each change below was made.*

## 1. Data Sources (Excel Documents)

The predictor extracts data from three USCIS/DOS workbooks located in `src/main/resources`.
Newer editions exist for all three (see `DATA_SOURCES.md` for exact URLs), but the code still
points at the files actually present in this repo — this session's sandbox could not reach
uscis.gov to download the newer bytes, and pointing the loader at a filename that isn't checked
in would silently zero out the data rather than fail the build. Swapping in the newer files is a
manual follow-up (download → add to `src/main/resources` → update the three filename constants
in `ExcelDataService`).

### A. I-485 Inventory Data
- **File in use**: `eb_inventory_october_2025 (1).xlsx`; newer edition available:
  `eb_inventory_february_2026.xlsx`
- Processes all country-specific sheets; sums "Employment-Based" category rows (EB1/EB2/EB3)
  across the priority-date-year columns.
- Represents the known queue of people who have already filed for Adjustment of Status.

### B. I-140 Approved Petitions
- **File in use**: `i140_rec_by_class_country_fy2025_q3.xlsx`; newer edition available:
  `i140_fy2026_q2_v1.xlsx`
- Iterates through country sheets, finds each "Preference" row's "Approved" sub-row, and reads
  both the TOTAL and yearly approved counts.
- Represents the "hidden" backlog of approved-petition holders waiting for their priority date
  to become current.

### C. Quarterly All Forms (Family-Based Data)
- **File in use**: `quarterly_all_forms_fy2025_q3.xlsx`; newer edition available:
  `quarterly_all_forms_fy2026_q3_v1.xlsx`
- Reads the "I-130" row's Pending count.
- **Changed use**: this pending count is a *demand* figure (how many family petitions are
  unresolved) and must not be compared directly against the 226,000 family-sponsored *supply*
  limit — that comparison was the root cause of the FB-to-EB spillover always computing zero.
  See section 3 below and `GAPS_AND_FIXES.md` #1.

## 2. Backlog Calculation

Unchanged: `Total Backlog = [Pending I-485s from Inventory] + [Approved I-140s from Performance Data]`

## 3. Visa Supply & Spillover Logic

### Phase 1: Initialize Base Supply (FB-to-EB Spillover)

1. **Calculate FB Spillover**:
   - Statutory family-sponsored floor is 226,000 (INA 201(c)(1)(B)).
   - `FB-to-EB Spillover = max(0, 226,000 - FamilyVisasActuallyUsedLastFY)`.
   - **This is the corrected formula.** The prior version subtracted the pending I-130 petition
     count (millions) instead of actual prior-year family visa usage, which made spillover
     always evaluate to zero regardless of real conditions.
   - `FamilyVisasActuallyUsedLastFY` also factors in `Applicant.familyVisaPauseSeverity` (0–1):
     the DOS worldwide immigrant-visa-interview pause running since ~Aug 25, 2026 primarily
     suppresses family-based visa issuance (employer-sponsored/EB cases are largely unaffected),
     so a higher severity means less family usage and more spillover to EB — this is the actual
     mechanism by which broad consular disruption reaches the EB predictor, rather than treating
     entire countries as EB-blocked.
   - **Manual Override**: `manualFbSpillover` on `Applicant` still works exactly as before, for
     simulation/testing.
2. **Initialize EB Pool**: `Total EB Limit = 140,000 (Base) + FB-to-EB Spillover`.
3. **Apply 7% Country Cap**: each country (including Brazil — see below) is allocated 7% of the
   Total EB Limit; **Rest of World (ROW)** gets the remainder.
   - *Brazil correction*: Brazil is one of the "big" countries with its own 7% cap, as this
     document always said — a previous version of `Country.java` contradicted that by also
     marking Brazil "restricted" (inherited from the now-vacated 75-country public-charge
     suspension, which did include Brazil). Brazil is not on the currently-operative 39-country
     travel-ban list, so that contradiction is now resolved in Brazil's favor: normal 7% cap,
     not restricted. See `GAPS_AND_FIXES.md` #3.
4. **EB-4/EB-5 residual**: each country's ~14.2% EB-4/EB-5 share (100% − 3×28.6%) is now folded
   into that country's **EB-1** allocation before the vertical waterfall runs, per INA 203(b)'s
   actual order (unused EB-4/unreserved EB-5 → EB-1 → EB-2 → EB-3). Previously this share was
   computed nowhere and simply discarded. See `GAPS_AND_FIXES.md` #2.

### Phase 2: Vertical Spillover (Within Country)

Unchanged mechanics, now operating on EB-1 pools that already include the EB-4/EB-5 residual:
1. **EB-1 → EB-2**: allocated EB-1 visas exceeding that country's EB-1 backlog spill to EB-2.
2. **EB-2 → EB-3**: EB-2 visas (original allocation + EB-1 spillover) exceeding the EB-2 backlog
   spill to EB-3.

### Phase 3: Horizontal/Global Redistribution (Across Countries)

Unchanged design: visas left unused in any category after the vertical waterfall (typically from
low-demand or currently-restricted countries) are pooled per category and redistributed to
oversubscribed countries in that category, proportional to each country's remaining backlog.

### Country restriction model (rewritten — see `GAPS_AND_FIXES.md` #3)

As of 2026-09-25, restriction is based on **Presidential Proclamation 10998** (eff. Jan 1, 2026,
the "travel ban," 39 countries across full/partial/single-category suspension), **not** the
Jan 2026 75-country "public charge" pause, which a federal judge vacated on August 21, 2026.
Both country identity *and* legal basis are dated snapshots — re-verify against
travel.state.gov before relying on this for anything consequential; this area of law has changed
several times within 2026 alone.

## 4. Prediction Calculation (Bulletin-Anchored — September 2026)

The predictor uses official **September 2026 Visa Bulletin** cut-off dates (was: stale February
2026 dates) as the "Head of the Line" to calculate the volume of applicants ahead of a specific
priority date.

1. **Head of the Line**: identifies the current *Dates for Filing* (Chart B) and *Final Action
   Dates* (Chart A) for the applicant's country and category.
2. **Unauthorized handling (new)**: India EB-2 Final Action is currently "U" (Unauthorized) on
   the September 2026 bulletin — the annual ceiling was reached ahead of the September 30 fiscal
   year end. The predictor now returns an explicit, honest explanation for this case instead of
   silently defaulting to a wide-open cutoff date (the old failure mode for any unmapped
   country/category). Numbers are expected to reopen with the FY2027 bulletin (October 2026).
3. **Volume Identification**: counts how many people in the I-485 Inventory and I-140 backlog
   have priority dates between the official cut-off and the applicant's priority date, using the
   yearly breakdown from the inventory/I-140 reports.

## Open items (tracked in `GAPS_AND_FIXES.md`, not resolved in this pass)

- Real prior-FY family-visa-usage data source for `getFamilyVisasUsedPriorFiscalYear()`.
- AOS-vs-consular-processing distinction for restricted countries.
- Cross-chargeability (INA 202(b)).
- Externalizing the restricted-country list and Visa Bulletin dates to config instead of Java
  source constants, so routine monthly/legal updates don't require a code change.
