package org.innovativebrains.greencardpredictor.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

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
 *   - I-130/I-485 "quarterly all forms": FY2026 Q3 (current pending counts)
 *     plus FY2025 Q4 (the last *completed* fiscal year, used for prior-FY
 *     family-visa usage -- see below).
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
    private static final String PRIOR_COMPLETED_FY_ALL_FORMS_FILE = "quarterly_all_forms_fy2025_q4_v1.xlsx";

    private Map<String, Long> inventoryMap = new HashMap<>(); // Key: Country_Category, Value: Count
    private Map<String, Map<Integer, Long>> inventoryYearlyMap = new HashMap<>(); // Key: Country_Category, Value: Map<Year, Count>
    private Map<String, Long> i140ApprovedMap = new HashMap<>(); // Key: Country_Category, Value: Count
    private Map<String, Map<Integer, Long>> i140YearlyMap = new HashMap<>(); // Key: Country_Category, Value: Map<Year, Count>
    private long i130PendingCount = 0;
    private long familyI485ApprovedPriorFY = 0;
    private boolean familyI485DataLoaded = false;

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
     * FIX (2026-09-26): parses USCIS's FY2025 Q4 "All Forms" report -- the
     * last *completed* fiscal year as of this writing -- for the domestic
     * I-485 "(Family)" category row. For a Q4/year-end report, that row's
     * "Fiscal Year - To Date" Approved column (index 9) is the full FY2025
     * total, not a partial-year figure.
     */
    private void loadFamilyVisaUsage() {
        try (InputStream is = new ClassPathResource(PRIOR_COMPLETED_FY_ALL_FORMS_FILE).getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Cell formCell = row.getCell(0);
                Cell titleCell = row.getCell(1);
                if (formCell == null || titleCell == null) continue;
                if (!"I-485".equals(formCell.toString().trim())) continue;
                if (!titleCell.toString().toUpperCase().contains("FAMILY")) continue;

                Cell fytdApprovedCell = row.getCell(9); // "Approved" under "Fiscal Year - To Date"
                if (fytdApprovedCell != null) {
                    try {
                        familyI485ApprovedPriorFY = (long) Double.parseDouble(fytdApprovedCell.toString());
                        familyI485DataLoaded = true;
                    } catch (NumberFormatException e) { }
                }
                break;
            }
            System.out.println("Loaded prior-completed-FY family I-485 approvals: " + familyI485ApprovedPriorFY);
        } catch (Exception e) {
            System.err.println("Error loading prior-FY family visa usage: " + e.getMessage());
        }
    }

    /**
     * FIX (2026-09-25, data wired in 2026-09-26): family-to-EB spillover must
     * be driven by how many family-preference visas were actually ISSUED/USED
     * in the prior fiscal year, not by the pending I-130 petition backlog
     * (which is a multi-million-row demand queue, not a usage figure -- see
     * GAPS_AND_FIXES.md item #1 for why the old formula always evaluated to
     * zero).
     *
     * This now returns FY2025's actual USCIS domestic I-485 "(Family)"
     * approval count (433,071, loaded by loadFamilyVisaUsage() above) instead
     * of a flat placeholder. Two known, documented limitations remain (see
     * DATA_SOURCES.md #4 for the full explanation and the sources that would
     * close them):
     *
     *  1. USCIS's public report doesn't split "(Family)" approvals between
     *     uncapped immediate relatives and capped family-preference (F1-F4)
     *     categories, so this figure OVERSTATES true preference-only usage --
     *     which biases the resulting spillover estimate toward 0 (never
     *     overpromises a wait time), not the other way around.
     *  2. It covers domestic adjustments only. It excludes immigrant visas
     *     issued abroad by consular posts (DOS Visa Office Annual Report
     *     Table VI), which also count against the family limit and typically
     *     account for a large share of usage. travel.state.gov returned
     *     HTTP 403 on every attempt from this environment (confirmed
     *     2026-09-26), so that half of the figure could not be incorporated.
     *
     * Falls back to the statutory floor (226,000, i.e. spillover = 0) if the
     * source file or row can't be parsed, same fail-safe as before.
     */
    public long getFamilyVisasUsedPriorFiscalYear() {
        return familyI485DataLoaded ? familyI485ApprovedPriorFY : 226_000L;
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
