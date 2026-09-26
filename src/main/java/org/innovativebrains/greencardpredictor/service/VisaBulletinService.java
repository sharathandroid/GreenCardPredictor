package org.innovativebrains.greencardpredictor.service;

import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * FIX (2026-09-25): dates were hardcoded to the February 2026 Visa Bulletin,
 * seven months stale as of today. Replaced with the actual September 2026
 * Visa Bulletin (source: travel.state.gov, "Visa Bulletin For September
 * 2026"), which is the most recent bulletin published at the time this fix
 * was made -- October 2026's bulletin (the first of FY2027) was not yet
 * published/indexed.
 *
 * IMPORTANT GAP THIS DOES NOT FULLY CLOSE: hardcoding a single month's
 * dates into Java source is itself the recurring problem -- every month
 * this file goes stale again. Ideally these dates should live in a small
 * config file (e.g. an application.yml block or a tiny JSON resource) that
 * gets refreshed monthly without a code change/redeploy. Left as noted in
 * GAPS_AND_FIXES.md rather than solved here, to avoid over-scoping this fix.
 *
 * NEW: "Unauthorized" (no visa numbers available at all this fiscal year --
 * shown as "U" on the real bulletin) is now modeled explicitly. The old
 * code had no way to represent this and would have silently treated an
 * unmapped country/category as "2010-01-01" (i.e. wide open), which is the
 * opposite of correct for e.g. India EB-2 in September 2026, which is
 * Unauthorized (annual/per-country ceiling already reached ahead of the
 * September 30 fiscal year end). This is expected to reopen with new
 * FY2027 numbers on/after October 1, 2026.
 */
@Service
public class VisaBulletinService {

    public static final String BULLETIN_MONTH = "September 2026";

    private final Map<String, LocalDate> finalActionDates = new HashMap<>();
    private final Map<String, LocalDate> filingDates = new HashMap<>();
    private final Set<String> unauthorizedFinalAction = new HashSet<>();

    public VisaBulletinService() {
        // ---- Final Action Dates (Chart A) -- September 2026 ----
        setFinalActionDate(Country.INDIA, EbCategory.EB1, LocalDate.of(2022, 10, 15));
        setUnauthorizedFinalAction(Country.INDIA, EbCategory.EB2); // "U" on the bulletin
        setFinalActionDate(Country.INDIA, EbCategory.EB3, LocalDate.of(2014, 1, 1));

        setFinalActionDate(Country.CHINA, EbCategory.EB1, LocalDate.of(2023, 7, 1));
        setFinalActionDate(Country.CHINA, EbCategory.EB2, LocalDate.of(2021, 9, 1));
        setFinalActionDate(Country.CHINA, EbCategory.EB3, LocalDate.of(2022, 1, 1));

        setFinalActionDate(Country.MEXICO, EbCategory.EB1, LocalDate.now()); // Current
        setFinalActionDate(Country.MEXICO, EbCategory.EB2, LocalDate.now()); // Current
        setFinalActionDate(Country.MEXICO, EbCategory.EB3, LocalDate.of(2024, 9, 1));

        setFinalActionDate(Country.PHILIPPINES, EbCategory.EB1, LocalDate.now()); // Current
        setFinalActionDate(Country.PHILIPPINES, EbCategory.EB2, LocalDate.now()); // Current
        setFinalActionDate(Country.PHILIPPINES, EbCategory.EB3, LocalDate.of(2023, 8, 1));

        setFinalActionDate(Country.ROW, EbCategory.EB1, LocalDate.now()); // Current
        setFinalActionDate(Country.ROW, EbCategory.EB2, LocalDate.now()); // Current
        setFinalActionDate(Country.ROW, EbCategory.EB3, LocalDate.of(2024, 9, 1));

        // ---- Dates for Filing Applications (Chart B) -- September 2026 ----
        setFilingDate(Country.INDIA, EbCategory.EB1, LocalDate.of(2023, 12, 1));
        setFilingDate(Country.INDIA, EbCategory.EB2, LocalDate.of(2015, 1, 15));
        setFilingDate(Country.INDIA, EbCategory.EB3, LocalDate.of(2015, 1, 15));

        setFilingDate(Country.CHINA, EbCategory.EB1, LocalDate.of(2023, 12, 1));
        setFilingDate(Country.CHINA, EbCategory.EB2, LocalDate.of(2022, 1, 1));
        setFilingDate(Country.CHINA, EbCategory.EB3, LocalDate.of(2022, 1, 8));

        setFilingDate(Country.MEXICO, EbCategory.EB1, LocalDate.now()); // Current
        setFilingDate(Country.MEXICO, EbCategory.EB2, LocalDate.now()); // Current
        setFilingDate(Country.MEXICO, EbCategory.EB3, LocalDate.now()); // Current

        setFilingDate(Country.PHILIPPINES, EbCategory.EB1, LocalDate.now()); // Current
        setFilingDate(Country.PHILIPPINES, EbCategory.EB2, LocalDate.now()); // Current
        setFilingDate(Country.PHILIPPINES, EbCategory.EB3, LocalDate.of(2024, 1, 1));

        setFilingDate(Country.ROW, EbCategory.EB1, LocalDate.now()); // Current
        setFilingDate(Country.ROW, EbCategory.EB2, LocalDate.now()); // Current
        setFilingDate(Country.ROW, EbCategory.EB3, LocalDate.now()); // Current
    }

    private void setFinalActionDate(Country c, EbCategory cat, LocalDate date) {
        finalActionDates.put(c.name() + "_" + cat.name(), date);
    }

    private void setUnauthorizedFinalAction(Country c, EbCategory cat) {
        unauthorizedFinalAction.add(c.name() + "_" + cat.name());
    }

    private void setFilingDate(Country c, EbCategory cat, LocalDate date) {
        filingDates.put(c.name() + "_" + cat.name(), date);
    }

    /**
     * True when the current bulletin marks this country/category "U"
     * (Unauthorized) -- meaning the annual limit for that category/country
     * has already been reached for this fiscal year and NO further final
     * action can occur until new numbers become available (normally at the
     * start of the next fiscal year, October 1).
     */
    public boolean isFinalActionUnauthorized(Country c, EbCategory cat) {
        return unauthorizedFinalAction.contains(c.name() + "_" + cat.name());
    }

    public LocalDate getFinalActionCutOff(Country c, EbCategory cat) {
        return finalActionDates.getOrDefault(c.name() + "_" + cat.name(), LocalDate.of(2010, 1, 1));
    }

    public LocalDate getFilingCutOff(Country c, EbCategory cat) {
        return filingDates.getOrDefault(c.name() + "_" + cat.name(), LocalDate.of(2010, 1, 1));
    }
}
