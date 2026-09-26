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
 * NOTE ON DATA VINTAGE (updated 2026-09-25):
 * ------------------------------------------------------------------------
 * The three source workbooks below (Oct 2025 inventory, FY2025 Q3 I-140 and
 * I-130 data) are stale -- newer editions exist on uscis.gov (see
 * DATA_SOURCES.md for exact filenames/URLs: eb_inventory_february_2026.xlsx,
 * i140_fy2026_q2_v1.xlsx, quarterly_all_forms_fy2026_q3_v1.xlsx).
 *
 * IMPORTANT: this PR deliberately does NOT repoint these constants at those
 * newer filenames, because the actual .xlsx bytes for them are not in this
 * repo and this environment's outbound network access to uscis.gov is
 * blocked, so they couldn't be downloaded and committed here. Repointing
 * ClassPathResource at a filename that isn't actually in src/main/resources
 * would not fail loudly -- loadInventory()/loadI140Data()/loadI130Data()
 * each swallow the IOException and just log it -- so the app would silently
 * run with empty backlog data instead of failing the build. That's worse
 * than staying on stale-but-present data. Once you've downloaded the newer
 * files from the URLs in DATA_SOURCES.md and added them under
 * src/main/resources, update the three constants below to match and verify
 * each workbook's layout still matches what the loaders expect (also noted
 * in DATA_SOURCES.md).
 * ------------------------------------------------------------------------
 */
@Service
public class ExcelDataService {

    // NOTE: still pointing at the existing (stale) files checked into
    // src/main/resources -- see the class comment above for why these
    // weren't repointed at the newer filenames in this pass.
    private static final String INVENTORY_FILE = "eb_inventory_october_2025 (1).xlsx";
    private static final String I140_FILE = "i140_rec_by_class_country_fy2025_q3.xlsx";
    private static final String I130_FILE = "quarterly_all_forms_fy2025_q3.xlsx";

    private Map<String, Long> inventoryMap = new HashMap<>(); // Key: Country_Category, Value: Count
    private Map<String, Map<Integer, Long>> inventoryYearlyMap = new HashMap<>(); // Key: Country_Category, Value: Map<Year, Count>
    private Map<String, Long> i140ApprovedMap = new HashMap<>(); // Key: Country_Category, Value: Count
    private Map<String, Map<Integer, Long>> i140YearlyMap = new HashMap<>(); // Key: Country_Category, Value: Map<Year, Count>
    private long i130PendingCount = 0;

    @PostConstruct
    public void init() {
        loadInventory();
        loadI140Data();
        loadI130Data();
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

                                        // Column 13 is TOTAL
                                        if (j == 13) {
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
     * FIX (2026-09-25): family-to-EB spillover must be driven by how many
     * family-preference visas were actually ISSUED/USED in the prior fiscal
     * year, not by the pending I-130 petition backlog (which is a multi-
     * million-row demand queue, not a usage figure -- see
     * GAPS_AND_FIXES.md item #1 for why the old formula always evaluated to
     * zero). This app does not yet parse a workbook that reports actual
     * family-preference visa issuances/adjustments, so this returns a
     * documented placeholder pinned to the statutory floor rather than
     * silently reusing the wrong number. Wire this up to DOS's "Immigrant
     * Visas Issued" annual report (Table VI) or USCIS's family-based I-485
     * approval counts once one of those workbooks is added as a resource.
     */
    public long getFamilyVisasUsedPriorFiscalYear() {
        // TODO: replace with real prior-FY family-preference issuance/adjustment
        // count once a suitable source workbook is wired in (see DATA_SOURCES.md).
        // Returning the statutory floor here means spillover defaults to 0
        // rather than a fabricated positive number, until real data is supplied.
        return 226_000L;
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
