# Source Excel Files — Where To Get Current Editions

**Update 2026-09-26: done.** A later session in this same repo confirmed this environment's
outbound access to `uscis.gov` actually works (only `travel.state.gov` returns HTTP 403, both via
`curl` and via an authenticated fetch tool, confirmed on retry) — the earlier "blocked" finding
below was specific to the sandbox that drafted the original fix, not a general limitation. All
three files were downloaded, their layouts verified cell-by-cell against what the parsers expect,
and `ExcelDataService.java`'s constants now point at them. See that file's class-level comment for
the exact filenames and a bug the re-pointing surfaced (a hardcoded "TOTAL is column 13" assumption
that silently went stale the moment the report grew an extra year column). The sections below are
kept as a record of what was checked and why, including one wrong filename guess.

## 1. EB I-485 Adjustment-of-Status Inventory — DONE

- **Old**: `eb_inventory_october_2025 (1).xlsx`
- **Now in use**: `eb_inventory_april_2026.xlsx` — confirmed the newest edition actually published
  (probed May/June/July/August/September 2026 filenames directly; all 404). Layout is identical to
  the old file (country in column A, category in column B, "Visa Status"/"Priority Date Month" in
  C/D, yearly counts from column E onward, header on row 4) — just the year range shifted forward
  by one (2016–2025 → 2017–2026), which the existing column-4-onward summing logic already handles
  correctly without changes.

## 2. I-140 Approved Petitions by Class and Country — DONE (after a wrong first guess)

- **Old**: `i140_rec_by_class_country_fy2025_q3.xlsx`
- **Guessed and wrong**: `i140_fy2026_q2_v1.xlsx` — downloaded and opened; it's a **different
  report** ("Receipts by Beneficiary State" / "Receipts by Beneficiary Country of Birth"), one row
  per country/state, single-quarter snapshot, no per-country sheets and no multi-year
  TOTAL/Approved/Denied breakdown. Would have silently broken `loadI140Data()`'s parsing (wrong
  sheet names, wrong row shape) had it been wired in without opening it first.
- **Now in use**: `i140_rec_by_class_country_fy2026_q3_v1.xlsx` — found by fetching USCIS's
  reports-and-studies index page directly rather than guessing the filename pattern. Confirmed
  same layout as the old file (one sheet per country, "...Preference (EBn)" header rows followed
  by "Total Petitions"/"Approved"/"Denied" sub-rows). One real difference: the yearly columns now
  run 2014–2026 instead of 2014–2025, which shifted the "TOTAL" column from index 13 to 14 —
  `loadI140Data()` now locates it by header text instead of a hardcoded index (see that method).

## 3. Quarterly All Forms (I-130/I-485 data) — DONE, and now used for two different purposes

- **Old**: `quarterly_all_forms_fy2025_q3.xlsx`
- **Now in use (current pending counts)**: `quarterly_all_forms_fy2026_q3_v1.xlsx` — same layout
  as the old file; `loadI130Data()`'s column-6 "Pending" read needed no changes. Note this file
  actually contains *two* "I-130" rows — "Immediate Relative" (uncapped) and "All Other Relative"
  (the capped family-sponsored preference categories) — and the existing code sums both into one
  `i130PendingCount`, which is unused elsewhere in the app (dead code left over from before the
  spillover fix switched to `getFamilyVisasUsedPriorFiscalYear()`); left as-is since nothing reads
  it, but worth knowing if it's ever wired back in.
- **Now in use (prior-FY family visa usage — see #4 below)**:
  `quarterly_all_forms_fy2025_q4_v1.xlsx`, added as a fourth resource specifically because it's the
  last *completed* fiscal year (FY2025, Oct 2024–Sep 2025) as of this writing, whereas the FY2026
  Q3 file only has 9 months of the current, still-open fiscal year.

## 4. FB-to-EB spillover figure — DONE, using real DOS Table VI data (2026-09-26)

`travel.state.gov` was unreachable from this environment for the entire session (HTTP 403 on every
direct attempt via `curl` and via an authenticated web-fetch tool, and every Wayback Machine
snapshot since July 2024 captured a Cloudflare block page instead of the real site). The project
owner had independent access and provided the actual source directly: the Department of State's
**Report of the Visa Office**, **Table VI** ("Preference Visas Issued" — visas issued at Foreign
Service posts abroad, i.e. consular issuance, split by preference category), as five PDFs
(`Table VI_PartI.pdf` through `PartV.pdf`, kept at the repo root).

`getFamilyVisasUsedPriorFiscalYear()` now returns Table VI Part I's real family-preference grand
total for FY2024: **205,762** — see `family-visa-usage.yml` and `ExcelDataService.loadFamilyVisaUsage()`.
This replaces an earlier, domestic-only figure (433,071, mixed with uncapped immediate relatives,
parsed from a USCIS quarterly workbook) entirely, rather than trying to combine the two — they were
different fiscal years and different, incompatible category breakdowns.

Two caveats remain, documented in the data file itself:

1. **It's FY2024, not FY2026.** FY2026 — the year actually containing the Jan 2026 travel-ban
   restrictions and the Aug–Sep 2026 consular closure — hadn't closed by the time this was fixed,
   so its Table VI isn't published yet. FY2024 is the most recent real anchor available, not a
   measurement of that disruption's actual effect.
2. **Still consular-only.** It excludes domestic USCIS family-preference I-485 adjustments (a
   different, smaller subset of what the old 433,071 figure counted), so it's a slight
   understatement of true total usage — biasing any spillover estimate a bit high, not low.

If a future edition of Table VI (FY2025, or ideally FY2026 once published) becomes available,
swapping the numbers in `family-visa-usage.yml` closes this out further with no code change needed.

## 5. Visa Bulletin (for `VisaBulletinService`)

- Current bulletin used in this fix: https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin/2026/visa-bulletin-for-september-2026.html
- Check monthly: https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin.html
- October 2026 (first bulletin of FY2027) should be published by early-to-mid September and is
  worth checking immediately — India EB-2 was "Unauthorized" in September and is expected to
  reopen with new numbers at the FY2027 reset.
