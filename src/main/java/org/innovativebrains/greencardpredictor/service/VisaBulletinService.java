package org.innovativebrains.greencardpredictor.service;

import org.innovativebrains.greencardpredictor.config.VisaBulletinProperties;
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
 * FIX (2026-09-26): the dates themselves now live in visa-bulletin.yml,
 * bound via Spring Boot's @ConfigurationProperties (see
 * VisaBulletinProperties), instead of being hardcoded in this Java source
 * file. This was flagged in GAPS_AND_FIXES.md #5 as the recurring failure
 * mode (a hardcoded month going stale every month); it's now a data-file
 * update, not a Java change or recompile. (An earlier version of this fix
 * used a hand-rolled java.util.Properties parser instead of Spring's own
 * config-properties binding -- replaced with this once the project's actual
 * Spring Boot conventions were confirmed.)
 *
 * NEW (2026-09-25): "Unauthorized" (no visa numbers available at all this
 * fiscal year -- shown as "U" on the real bulletin) is now modeled
 * explicitly. The old code had no way to represent this and would have
 * silently treated an unmapped country/category as "2010-01-01" (i.e. wide
 * open), which is the opposite of correct for e.g. India EB-2 in September
 * 2026, which is Unauthorized (annual/per-country ceiling already reached
 * ahead of the September 30 fiscal year end). This is expected to reopen
 * with new FY2027 numbers on/after October 1, 2026.
 */
@Service
public class VisaBulletinService {

    private static final LocalDate DEFAULT_CUTOFF = LocalDate.of(2010, 1, 1);

    // Fallbacks only for a visa-bulletin.yml that predates the
    // worldwide-eb-limit/per-country-cap keys (FIX 2026-09-27, see
    // GAPS_AND_FIXES.md #16). Real bulletins always publish both numbers
    // directly, so these should never be hit once the yml is kept current.
    private static final long DEFAULT_WORLDWIDE_EB_LIMIT = 140_000L;
    private static final long DEFAULT_PER_COUNTRY_CAP = 9_800L;

    private final String bulletinMonth;
    private final long worldwideEbLimit;
    private final long perCountryCap;
    private final Map<String, LocalDate> finalActionDates = new HashMap<>();
    private final Map<String, LocalDate> filingDates = new HashMap<>();
    private final Set<String> unauthorizedFinalAction = new HashSet<>();

    public VisaBulletinService(VisaBulletinProperties properties) {
        this.bulletinMonth = properties.getMonth();
        this.worldwideEbLimit = properties.getWorldwideEbLimit() != null
                ? properties.getWorldwideEbLimit() : DEFAULT_WORLDWIDE_EB_LIMIT;
        this.perCountryCap = properties.getPerCountryCap() != null
                ? properties.getPerCountryCap() : DEFAULT_PER_COUNTRY_CAP;

        properties.getFinalAction().forEach((countryKey, byCategory) ->
            byCategory.forEach((categoryKey, value) -> {
                Country country = parseCountry(countryKey);
                EbCategory category = parseCategory(categoryKey);
                if ("UNAUTHORIZED".equals(value.trim())) {
                    setUnauthorizedFinalAction(country, category);
                } else {
                    setFinalActionDate(country, category, parseDate(value, "final-action." + countryKey + "." + categoryKey));
                }
            }));

        properties.getFiling().forEach((countryKey, byCategory) ->
            byCategory.forEach((categoryKey, value) -> {
                Country country = parseCountry(countryKey);
                EbCategory category = parseCategory(categoryKey);
                setFilingDate(country, category, parseDate(value, "filing." + countryKey + "." + categoryKey));
            }));
    }

    public String getBulletinMonth() {
        return bulletinMonth;
    }

    /**
     * The real published worldwide EB annual limit for the current bulletin
     * (e.g. 186,317 for FY2026), taken directly from visa-bulletin.yml. See
     * GAPS_AND_FIXES.md #16 for why this replaced a hardcoded 140,000 base.
     */
    public long getWorldwideEbLimit() {
        return worldwideEbLimit;
    }

    /**
     * The real published per-country EB ceiling for the current bulletin
     * (7% of family + employment combined, per INA 202(a)(2), plus any
     * EB-5 carryover per INA 203(b)(5)(B)) -- e.g. 29,136 for FY2026. Taken
     * directly from visa-bulletin.yml rather than derived as 7% of the EB
     * limit alone. See GAPS_AND_FIXES.md #16.
     */
    public long getPerCountryCap() {
        return perCountryCap;
    }

    private static Country parseCountry(String key) {
        try {
            return Country.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("visa-bulletin.yml names an unrecognized country: " + key, e);
        }
    }

    private static EbCategory parseCategory(String key) {
        try {
            return EbCategory.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("visa-bulletin.yml names an unrecognized EB category: " + key, e);
        }
    }

    private static LocalDate parseDate(String value, String key) {
        String trimmed = value.trim();
        if ("CURRENT".equals(trimmed)) return LocalDate.now();
        try {
            return LocalDate.parse(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException(
                "visa-bulletin.yml has an unparseable date for " + key + ": \"" + trimmed + "\"", e);
        }
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
        return finalActionDates.getOrDefault(c.name() + "_" + cat.name(), DEFAULT_CUTOFF);
    }

    public LocalDate getFilingCutOff(Country c, EbCategory cat) {
        return filingDates.getOrDefault(c.name() + "_" + cat.name(), DEFAULT_CUTOFF);
    }
}
