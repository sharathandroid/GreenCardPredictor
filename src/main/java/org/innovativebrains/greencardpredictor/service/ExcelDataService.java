package org.innovativebrains.greencardpredictor.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * NOTE ON DATA VINTAGE (updated 2026-09-26):
 * ------------------------------------------------------------------------
 * The three source workbooks below were refreshed to the most recent editions
 * confirmed available on uscis.gov as of 2026-09-26 (this environment's
 * network access to uscis.gov works; travel.state.gov does not -- see the
 * family-visa-usage note on getFamilyVisasUsedPriorFiscalYear() below):
 *   - EB I-485 inventory: April 2026 (the newest monthly edition published;
 *     USCIS had not yet posted May/June/July/August/September by the time of
 *     this fix -- still ~5 months behind "today," just less than the ~11
 *     months of staleness this replaced).
 *   - I-140 receipts/approvals by class and country: FY2026 Q3.
 *   - I-130/I-485 "quarterly all forms": FY2026 Q3 (current pending counts).
 *     Family-preference visa usage (for FB-to-EB spillover) is sourced
 *     separately, from DOS's real Table VI data -- see
 *     getFamilyVisasUsedPriorFiscalYear() below and family-visa-usage.yml.
 *
 * A prior pass guessed the I-140 replacement filename as "i140_fy2026_q2_v1
 * .xlsx" without downloading it; that guess was wrong -- it's a genuinely
 * different report ("Receipts by Beneficiary State/Country of Birth", one
 * row per country, no multi-year TOTAL/Approved/Denied breakdown). The
 * correct continuation of the old "by class and country" report is
 * i140_rec_by_class_country_fy2026_q3_v1.xlsx, confirmed by fetching
 * USCIS's reports-and-studies index page directly. Lesson: a plausible
 * filename pattern is not a substitute for opening the file.
 *
 * Re-pointing these constants also surfaced a real bug: the new I-140 file's
 * yearly columns run 2014-2026 (13 years, one more than 2014-2025), which
 * shifted the "TOTAL" column from index 13 to 14. loadI140Data() no longer
 * hardcodes that index -- it looks up the "TOTAL" header cell each time --
 * specifically so a future year rollover doesn't silently misfile next
 * year's partial total as the all-time total again.
 * ------------------------------------------------------------------------
 */
@Service
public class ExcelDataService {

    private static final String INVENTORY_FILE = "eb_inventory_april_2026.xlsx";
    private static final String I140_FILE = "i140_rec_by_class_country_fy2026_q3_v1.xlsx";
    private static final String I130_FILE = "quarterly_all_forms_fy2026_q3_v1.xlsx";
    private static final String FAMILY_VISA_USAGE_FILE = "family-visa-usage.yml";

    private Map<String, Long> inventoryMap = new HashMap<>(); // Key: Country_Category, Value: Count
    private Map<String, Map<Integer, Long>> inventoryYearlyMap = new HashMap<>(); // Key: Country_Category, Value: Map<Year, Count>
    private Map<String, Long> i140ApprovedMap = new HashMap<>(); // Key: Country_Category, Value: Count
    private Map<String, Map<Integer, Long>> i140YearlyMap = new HashMap<>(); // Key: Country_Category, Value: Map<Year, Count>
    private long i130PendingCount = 0;
    private long familyPreferenceVisasIssuedPriorFY = 0;
    private boolean familyVisaUsageDataLoaded = false;

    @PostConstruct
    public void init() {
        loadInventory();
        loadI140Data();
        loadI130Data();
        loadFamilyVisaUsage();
    }

    private void loadInventory() {
        try (InputStream is = new ClassPathResource(INVENTORY_FILE).getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            for (int s = 1; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                Row headerRow = sheet.getRow(3); // Year headers are usually at row 4
                if (headerRow == null) {
                    headerRow = sheet.getRow(0); // Try first row
                }

                int startRow = (headerRow != null && headerRow.getRowNum() == 3) ? 4 : 1;
                for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    Cell countryCell = row.getCell(0);
                    Cell categoryCell = row.getCell(1);
                    if (countryCell == null || categoryCell == null) continue;

                    Country country = Country.fromString(countryCell.toString());
                    EbCategory category = EbCategory.fromString(categoryCell.toString());

                    if (category == null) continue;

                    String key = country.name() + "_" + category.name();
                    Map<Integer, Long> yearlyData = inventoryYearlyMap.getOrDefault(key, new HashMap<>());

                    long rowTotal = 0;
                    // Sum up values from column index 4 to LAST COLUMN
                    for (int j = 4; j < row.getLastCellNum(); j++) {
                        Cell countCell = row.getCell(j);
                        if (countCell != null) {
                            String cellVal = countCell.toString();
                            if (!cellVal.equals("-") && !cellVal.equals("D")) {
                                try {
                                    long count = (long) Double.parseDouble(cellVal);
                                    rowTotal += count;

                                    // Extract Year from header
                                    if (headerRow != null && headerRow.getCell(j) != null) {
                                        String yearStr = headerRow.getCell(j).toString();
                                        if (yearStr.contains(".")) yearStr = yearStr.substring(0, yearStr.indexOf("."));

                                        // Handle "Priority Date Year - 2024"
                                        if (yearStr.contains("-")) {
                                            yearStr = yearStr.substring(yearStr.lastIndexOf("-") + 1).trim();
                                        }

                                        try {
                                            int year = Integer.parseInt(yearStr);
                                            yearlyData.put(year, yearlyData.getOrDefault(year, 0L) + count);
                                        } catch (NumberFormatException e) {
                                            if (yearStr.toLowerCase().contains("prior")) {
                                                // Map "Prior Years" to a fixed early year
                                                yearlyData.put(2000, yearlyData.getOrDefault(2000, 0L) + count);
                                            }
                                        }
                                    }
                                } catch (NumberFormatException e) { }
                            }
                        }
                    }

                    inventoryMap.put(key, inventoryMap.getOrDefault(key, 0L) + rowTotal);
                    inventoryYearlyMap.put(key, yearlyData);
                }
            }
            System.out.println("Processed Inventory Excel. Categories found: " + inventoryMap.keySet());
        } catch (Exception e) {
            System.err.println("Error loading inventory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadI140Data() {
        try (InputStream is = new ClassPathResource(I140_FILE).getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            for (int s = 1; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                Country country = Country.fromString(sheet.getSheetName().replace(" FY25", "").replace(" FY26", ""));

                Row headerRow = sheet.getRow(3); // Years are at row 3 (0-indexed)

                // FIX (2026-09-26): the TOTAL column's index shifts whenever the
                // report adds another year (it moved from 13 to 14 between the
                // FY2025 Q3 and FY2026 Q3 editions). Look it up by header text
                // instead of hardcoding a position that silently goes stale.
                int totalCol = -1;
                if (headerRow != null) {
                    for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                        Cell headerCell = headerRow.getCell(c);
                        if (headerCell != null && "TOTAL".equalsIgnoreCase(headerCell.toString().trim())) {
                            totalCol = c;
                            break;
                        }
                    }
                }

                for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    Cell firstCell = row.getCell(0);
                    if (firstCell != null && firstCell.toString().contains("Preference")) {
                        String categoryStr = firstCell.toString();
                        EbCategory category = EbCategory.fromString(categoryStr);

                        if (category == null) continue;

                        String key = country.name() + "_" + category.name();
                        Map<Integer, Long> yearlyData = i140YearlyMap.getOrDefault(key, new HashMap<>());

                        // "Approved" row is usually next or few rows down
                        for (int k = i + 1; k < i + 5 && k <= sheet.getLastRowNum(); k++) {
                            Row subRow = sheet.getRow(k);
                            if (subRow != null && subSubRowMatches(subRow, "Approved")) {
                                for (int j = 1; j < subRow.getLastCellNum(); j++) {
                                    Cell dataCell = subRow.getCell(j);
                                    if (dataCell == null) continue;

                                    try {
                                        long count = (long) Double.parseDouble(dataCell.toString());

                                        if (j == totalCol) {
                                            i140ApprovedMap.put(key, count);
                                        } else if (headerRow != null && headerRow.getCell(j) != null) {
                                            String yearStr = headerRow.getCell(j).toString();
                                            if (yearStr.contains(".")) yearStr = yearStr.substring(0, yearStr.indexOf("."));
                                            try {
                                                int year = Integer.parseInt(yearStr);
                                                yearlyData.put(year, count);
                                            } catch (NumberFormatException e) {}
                                        }
                                    } catch (NumberFormatException e) { }
                                }
                                break;
                            }
                        }
                        i140YearlyMap.put(key, yearlyData);
                    }
                }
            }
            System.out.println("Loaded I-140 Approved Excel. Yearly Data for India EB2: " + i140YearlyMap.get("INDIA_EB2"));
        } catch (Exception e) {
            System.err.println("Error loading I-140 data: " + e.getMessage());
        }
    }

    private boolean subSubRowMatches(Row row, String text) {
        Cell cell = row.getCell(0);
        return cell != null && cell.toString().trim().equalsIgnoreCase(text);
    }

    private void loadI130Data() {
        try (InputStream is = new ClassPathResource(I130_FILE).getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            i130PendingCount = 0;
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    Cell firstCell = row.getCell(0);
                    if (firstCell != null && firstCell.toString().trim().equals("I-130")) {
                        Cell pendingCell = row.getCell(6); // 3rd Quarter Pending column
                        if (pendingCell != null) {
                            try {
                                i130PendingCount += (long) Double.parseDouble(pendingCell.toString());
                            } catch (NumberFormatException e) { }
                        }
                    }
                }
            }
            System.out.println("Loaded I-130 Data. Total Pending: " + i130PendingCount);
        } catch (Exception e) {
            System.err.println("Error loading I-130 data: " + e.getMessage());
        }
    }

    public long getI130PendingCount() {
        return i130PendingCount;
    }

    /**
     * FIX (2026-09-26): parses family-visa-usage.yml, which holds the real
     * DOS Table VI ("Preference Visas Issued") family-preference grand total
     * -- provided directly by the project owner after travel.state.gov
     * proved unreachable all session (see that file's own header comment for
     * the full provenance and caveats, especially that it's FY2024, not
     * FY2026). This replaces two earlier, weaker approaches tried in this
     * same session:
     *
     *  1. An I-130-approval-ratio-weighted estimate (~37,300) that was
     *     invalid -- I-130 petition approvals and I-485 adjustment approvals
     *     in the same fiscal year aren't the same population at the same
     *     pipeline stage, so there's no valid same-year ratio to borrow.
     *     Produced a spillover (~188,700) nearly 2.4x the entire base EB
     *     limit and obviously wrong predictions.
     *  2. The raw, unweighted domestic I-485 "(Family)" approval figure
     *     (433,071, FY2025) -- real data, but mixes in uncapped immediate
     *     relatives (not subject to the 226,000 cap) with capped
     *     family-preference approvals, and covers domestic adjustments only.
     *
     * Table VI Part I's family-preference grand total (205,762) is a clean
     * improvement on both: it's the real DOS figure, split by preference
     * category already (no immediate-relative contamination), for actual
     * consular issuances specifically -- the CONSULAR component that was
     * always the fully-missing half of this figure. It still doesn't cover
     * domestic USCIS I-485 family-preference-category adjustments (a
     * different, smaller subset of what the old domestic figure counted,
     * which this app doesn't have split out either), so it still
     * UNDERSTATES true total usage somewhat -- but only by however much that
     * additional domestic contribution really is, not by conflating entire
     * unrelated categories or years the way the two prior attempts did.
     */
    private void loadFamilyVisaUsage() {
        try (InputStream is = new ClassPathResource(FAMILY_VISA_USAGE_FILE).getInputStream()) {
            Map<String, Object> root = new Yaml().load(is);
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) root.get("family-visa-usage");
            Object value = section.get("family-preference-consular-issued");
            familyPreferenceVisasIssuedPriorFY = ((Number) value).longValue();
            familyVisaUsageDataLoaded = true;
            System.out.println("Loaded family-preference visa usage (DOS Table VI, FY"
                    + section.get("fiscal-year") + " consular issuances): " + familyPreferenceVisasIssuedPriorFY);
        } catch (Exception e) {
            System.err.println("Error loading family visa usage: " + e.getMessage());
        }
    }

    /**
     * FIX (2026-09-25, real data wired in 2026-09-26): family-to-EB
     * spillover must be driven by how many family-preference visas were
     * actually ISSUED/USED in the prior fiscal year, not by the pending
     * I-130 petition backlog (which is a multi-million-row demand queue, not
     * a usage figure -- see GAPS_AND_FIXES.md item #1 for why the old
     * formula always evaluated to zero).
     *
     * This now returns DOS's real, authoritative family-preference-only
     * consular issuance count (205,762) from Table VI of the Report of the
     * Visa Office -- see loadFamilyVisaUsage()'s Javadoc for how this
     * replaced two weaker, session-local attempts, and family-visa-usage.yml
     * for the full source provenance and remaining caveats (notably: this is
     * FY2024, not FY2026, and it still excludes domestic USCIS
     * family-preference I-485 adjustments, so it's a real but incomplete --
     * and likely conservative-toward-understating -- figure).
     *
     * Falls back to the statutory floor (226,000, i.e. spillover = 0) if the
     * source file can't be parsed, same fail-safe as before.
     */
    public long getFamilyVisasUsedPriorFiscalYear() {
        return familyVisaUsageDataLoaded ? familyPreferenceVisasIssuedPriorFY : 226_000L;
    }

    public long getInventoryCount(Country country, EbCategory category) {
        String key = country.name() + "_" + category.name();
        return inventoryMap.getOrDefault(key, 0L);
    }

    public long getI140Between(Country country, EbCategory category, LocalDate startDate, LocalDate endDate) {
        String key = country.name() + "_" + category.name();
        Map<Integer, Long> yearlyData = i140YearlyMap.get(key);
        if (yearlyData == null) return 0;

        int startYear = startDate.getYear();
        int endYear = endDate.getYear();

        long total = 0;
        for (int year = startYear; year <= endYear; year++) {
            long count = yearlyData.getOrDefault(year, 0L);
            if (year == startYear && year == endYear) {
                double fraction = (endDate.getDayOfYear() - startDate.getDayOfYear()) / 365.25;
                total += (long) (count * Math.max(0, fraction));
            } else if (year == startYear) {
                double fraction = (365.25 - startDate.getDayOfYear()) / 365.25;
                total += (long) (count * Math.max(0, fraction));
            } else if (year == endYear) {
                double fraction = endDate.getDayOfYear() / 365.25;
                total += (long) (count * Math.max(0, fraction));
            } else {
                total += count;
            }
        }
        return total;
    }

    public long getI140ForYear(Country country, EbCategory category, int year) {
        String key = country.name() + "_" + category.name();
        Map<Integer, Long> yearlyData = i140YearlyMap.get(key);
        return (yearlyData != null) ? yearlyData.getOrDefault(year, 0L) : 0;
    }

    public long getI140Count(Country country, EbCategory category) {
        String key = country.name() + "_" + category.name();
        return i140ApprovedMap.getOrDefault(key, 0L);
    }

    public long getOldestBacklog(Country country, EbCategory category, int beforeYear) {
        String key = country.name() + "_" + category.name();
        long totalOld = 0;

        Map<Integer, Long> invYearly = inventoryYearlyMap.get(key);
        if (invYearly != null) {
            for (Map.Entry<Integer, Long> entry : invYearly.entrySet()) {
                if (entry.getKey() < beforeYear) {
                    totalOld += entry.getValue();
                }
            }
        }

        Map<Integer, Long> i140Yearly = i140YearlyMap.get(key);
        if (i140Yearly != null) {
            for (Map.Entry<Integer, Long> entry : i140Yearly.entrySet()) {
                if (entry.getKey() < beforeYear) {
                    totalOld += entry.getValue();
                }
            }
        }

        return totalOld;
    }

    public long getInventoryBetween(Country country, EbCategory category, LocalDate startDate, LocalDate endDate) {
        String key = country.name() + "_" + category.name();
        Map<Integer, Long> yearlyData = inventoryYearlyMap.get(key);
        if (yearlyData == null) return 0;

        int startYear = startDate.getYear();
        int endYear = endDate.getYear();

        long total = 0;
        for (int year = startYear; year <= endYear; year++) {
            long count = yearlyData.getOrDefault(year, 0L);
            if (year == startYear && year == endYear) {
                double fraction = (endDate.getDayOfYear() - startDate.getDayOfYear()) / 365.25;
                total += (long) (count * Math.max(0, fraction));
            } else if (year == startYear) {
                double fraction = (365.25 - startDate.getDayOfYear()) / 365.25;
                total += (long) (count * Math.max(0, fraction));
            } else if (year == endYear) {
                double fraction = endDate.getDayOfYear() / 365.25;
                total += (long) (count * Math.max(0, fraction));
            } else {
                total += count;
            }
        }
        return total;
    }

    public long getBacklog(Country country, EbCategory category) {
        String key = country.name() + "_" + category.name();
        return inventoryMap.getOrDefault(key, 0L) + i140ApprovedMap.getOrDefault(key, 0L);
    }

    public Map<Integer, Long> getInventoryYearly(Country country, EbCategory category) {
        String key = country.name() + "_" + category.name();
        return inventoryYearlyMap.getOrDefault(key, new HashMap<>());
    }
}
