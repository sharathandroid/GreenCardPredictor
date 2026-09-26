# Green Card Prediction Strategy

This document outlines the logic and methodology used by the Green Card Predictor application to estimate I-485 filing and approval dates, including specific details on data sources and spillover mechanics.

## 1. Data Sources (Excel Documents)
The predictor extracts data from three primary USCIS Excel workbooks located in `src/main/resources`:

### A. I-485 Inventory Data
*   **File**: `eb_inventory_october_2025 (1).xlsx`
*   **Data Extracted**:
    *   **Sheets**: Processes all country-specific sheets (e.g., India, China, ROW).
    *   **Rows**: Identifies "Employment-Based" categories (EB1, EB2, EB3).
    *   **Columns**: Sums the counts from columns 4 through 14, representing pending applications across different priority date years.
*   **Purpose**: Represents the known queue of people who have already filed for Adjustment of Status.

### B. I-140 Approved Petitions
*   **File**: `i140_rec_by_class_country_fy2025_q3.xlsx`
*   **Data Extracted**:
    *   **Sheets**: Iterates through country sheets (India, China, etc.).
    *   **Rows**: Searches for "Preference" categories and looks for the "Approved" row immediately following them.
    *   **Columns**: Extracts both the "TOTAL" count from column 13 and the **yearly approved counts** (FY 2014-2025) from the respective columns.
*   **Purpose**: Represents the "hidden" backlog of individuals with approved immigrant petitions who are waiting for their priority date to become current to file an I-485. The yearly breakdown allows for precise calculations based on the applicant's priority date.

### C. Quarterly All Forms (Family-Based Data)
*   **File**: `quarterly_all_forms_fy2025_q3.xlsx`
*   **Data Extracted**:
    *   **Rows**: Specifically targets rows where the form type is **"I-130"** (Petition for Alien Relative).
    *   **Columns**: Extracts the **"Pending"** count from column 6 (3rd Quarter Pending).
*   **Purpose**: Used to calculate the unused Family-Based visas that spill over to the Employment-Based pool.

## 2. Backlog Calculation
The total backlog for a specific Country and EB Category is the sum of the visible and hidden queues:
`Total Backlog = [Pending I-485s from Inventory] + [Approved I-140s from Performance Data]`

## 3. Visa Supply & Spillover Logic
The predictor follows a multi-stage process to determine the final annual supply for each category, strictly adhering to statutory rules and spillover requirements.

### Phase 1: Initialize Base Supply (FB-to-EB Spillover)
1.  **Calculate FB Spillover**: 
    *   Statutory Family-Based (FB) limit is ~226,000.
    *   `FB-to-EB Spillover = 226,000 - Total Pending I-130s`.
    *   This represents the number of FB visas that are projected to go unused in the current fiscal year and are legally required to be added to the EB pool for the following year.
    *   **Manual Override**: For simulation and testing purposes, the `manualFbSpillover` field in the `Applicant` model can be used to set a specific spillover value (e.g., to see the impact of a 100,000 visa spillover).
2.  **Initialize EB Pool**: 
    *   `Total EB Limit = 140,000 (Base) + FB-to-EB Spillover`.
3.  **Apply 7% Country Cap**:
    *   Each specific country (India, China, Philippines, Mexico, Brazil) is allocated 7% of the `Total EB Limit`.
    *   **Rest of World (ROW)** is allocated the remainder (approx. 65% of the total).

### Phase 2: Vertical Spillover (Within Country)
Within each country's allocated 7% cap (or ROW's share), visas move vertically if not used:
1.  **EB-1 → EB-2**: Any allocated EB-1 visas that exceed the EB-1 backlog for that country spill down to the EB-2 pool.
2.  **EB-2 → EB-3**: Any EB-2 visas (original allocation + EB-1 spillover) that exceed the EB-2 backlog spill down to the EB-3 pool.

### Phase 3: Horizontal/Global Redistribution (Across Countries)
After vertical spillovers are processed within each country:
1.  **Pooling**: Any visas remaining unused in any category/country (typically from ROW or under-utilized countries) are "pooled" into a global redistribution bucket.
2.  **Redistribution**: These pooled visas are redistributed to **oversubscribed countries** (like India and China) whose demand exceeds their 7% cap.
3.  **Allocation**: The distribution is proportional to each country's remaining backlog for that specific category. This ensures that unused visas are allocated based on priority date irrespective of country once the initial caps are satisfied.

## 4. Prediction Calculation (Bulletin-Anchored - February 2026)
The predictor uses official **February 2026 Visa Bulletin Cut-off Dates** as the "Head of the Line" to calculate the volume of applicants ahead of a specific Priority Date.

1.  **Head of the Line**: Identifies the current *Dates for Filing* (Chart B) and *Final Action Dates* (Chart A) for the applicant's Country and Category.
2.  **Volume Identification**:
    *   **Inventory Gap**: Counts exactly how many people in the I-485 Inventory have Priority Dates between the official cut-off and the applicant's Priority Date, using the yearly breakdown from the inventory report.
    *   **I-140 Gap**: Counts exactly how many people have approved I-140 petitions with Priority Dates between the official cut-off and the applicant's Priority Date, using historical yearly approval data (FY 2014-2025).
    *   **Data-Driven Precision**: For categories like **India EB2/EB3** where I-485 filing windows have been closed for years, the model automatically accounts for the missing inventory by including the actual I-140 approvals for those years. This replaces old heuristics with actual historical data.
3.  **Wait Times**:
    *   **Filing Wait**: `((Inventory Gap + I-140 Gap) / Final Annual Supply) * 12 months`. This predicts when the official filing window (Chart B) will reach the applicant's Priority Date by clearing the combined inventory and I-140 backlog ahead of them.
    *   **Final Action Wait**: `((Inventory Gap + I-140 Gap) / Final Annual Supply) * 12 months`. This predicts when the Green Card (Chart A) will be issued. The gap is calculated starting from the respective Bulletin cut-off date.
4.  **Dates**:
    *   **Final Action Date**: Current Date + Final Action Wait.
    *   **I-485 Filing Date**: Current Date + Filing Wait.

## 5. Limitations
*   **Static Snapshots**: Calculations are based on the specific quarters represented in the Excel files.
*   **Dependents**: The model assumes the inventory counts include dependents, but I-140 counts may require a multiplier (typically 2.0-2.5) for even higher precision.
*   **Administrative Delays**: Does not account for temporary USCIS office closures or policy changes that might pause processing.
