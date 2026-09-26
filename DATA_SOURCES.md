# Source Excel Files — Where To Get Current Editions

This sandbox's outbound network access to `uscis.gov` / `travel.state.gov` was blocked (proxy
returned `CONNECT tunnel failed, response 403` on direct download attempts, confirmed again on
2026-09-26 when re-tested from this same environment), so these files could not be fetched and
embedded automatically. The filenames/URLs below were confirmed to exist via web search as of
2026-09-25; download them yourself (a normal machine/CI runner should have no trouble reaching
these) and drop them into `src/main/resources/`, replacing the stale ones.

**Important — this PR does NOT repoint `ExcelDataService`'s filename constants at the new names
below.** Only the actual old files are present in `src/main/resources/` right now; pointing
`ClassPathResource` at a filename that isn't there wouldn't fail the build (the loader methods
swallow the `IOException` and just log it), it would silently leave every backlog/demand figure
at zero. So `ExcelDataService.java` in this PR still points at the existing, stale files, and the
filename swap below is left as a manual step for once you've actually downloaded the bytes.

## 1. EB I-485 Adjustment-of-Status Inventory

- **Old**: `eb_inventory_october_2025 (1).xlsx`
- **New (best confirmed available)**: `eb_inventory_february_2026.xlsx`
- Source page: https://www.uscis.gov/tools/reports-and-studies/immigration-and-citizenship-data
- Direct URL pattern: `https://www.uscis.gov/sites/default/files/document/data/eb_inventory_february_2026.xlsx`
- **Check before trusting the parser unchanged**: `ExcelDataService.loadInventory()` assumes
  headers on row 4 (index 3), country in column A, category in column B, and yearly counts from
  column E (index 4) onward. Confirm this layout hasn't shifted in the newer file. If USCIS has
  published a newer edition than February 2026 by the time you read this, prefer that one — check
  the reports-and-studies page above.

## 2. I-140 Approved Petitions by Class and Country

- **Old**: `i140_rec_by_class_country_fy2025_q3.xlsx`
- **New (best confirmed available)**: `i140_fy2026_q2_v1.xlsx`
- Direct URL pattern: `https://www.uscis.gov/sites/default/files/document/data/i140_fy2026_q2_v1.xlsx`
- **Important**: the naming convention changed between vintages (`..._rec_by_class_country_...`
  vs. `i140_fyXXXX_qN_v1.xlsx`). Before wiring this in, open it and confirm it still has one sheet
  per country with "... Preference" rows followed by an "Approved" sub-row, matching what
  `loadI140Data()` expects — the code updated the sheet-name suffix stripping (`" FY25"` /
  `" FY26"`) but has not been run against the actual bytes of this file in this session.
- A related file worth checking as an alternative/supplement:
  `eb_i140_i360_i526_performancedata_fy2025_q2.xlsx` (and presumably a `fy2026` counterpart by
  now) — this looks like a fuller EB performance-data workbook that might also carry the
  actual-visa-usage figures needed to fix `getFamilyVisasUsedPriorFiscalYear()`'s employment side,
  and possibly a family-side equivalent exists too. Worth a look on the reports-and-studies page.

## 3. Quarterly All Forms (I-130 family petition data)

- **Old**: `quarterly_all_forms_fy2025_q3.xlsx`
- **New (confirmed)**: `quarterly_all_forms_fy2026_q3_v1.xlsx`
- Direct URL: https://www.uscis.gov/sites/default/files/document/data/quarterly_all_forms_fy2026_q3_v1.xlsx
- This is FY2026 Q3 (Apr–Jun 2026) data, the most recent quarter that should be published as of
  September 2026. `loadI130Data()` only reads the "I-130" row's pending count (column G / index
  6) — if you want to fix `getFamilyVisasUsedPriorFiscalYear()` for real, check whether this same
  workbook also has an "Approved" or "Completed" column/row for I-130, which would be a much
  better proxy for actual family-preference usage than the pending count.

## 4. What's still missing for a fully correct FB-to-EB spillover figure

None of the three files above directly report "family-sponsored preference visas issued/adjusted
last fiscal year," which is what the corrected spillover formula (see `GAPS_AND_FIXES.md` #1)
actually needs. The authoritative source for that is the Department of State's **Visa Office
Annual Report**, Table VI ("Immigrant Visas Issued at Foreign Service Posts by Foreign State of
Chargeability and Preference Class"): https://travel.state.gov/content/travel/en/legal/visa-law0/visa-statistics/annual-reports.html
Combine that with USCIS's own family-based I-485 approval counts (domestic adjustments also count
against the family limit) for the complete picture.

## 5. Visa Bulletin (for `VisaBulletinService`)

- Current bulletin used in this fix: https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin/2026/visa-bulletin-for-september-2026.html
- Check monthly: https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin.html
- October 2026 (first bulletin of FY2027) should be published by early-to-mid September and is
  worth checking immediately — India EB-2 was "Unauthorized" in September and is expected to
  reopen with new numbers at the FY2027 reset.
