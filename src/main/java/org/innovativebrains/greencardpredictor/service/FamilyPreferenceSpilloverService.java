package org.innovativebrains.greencardpredictor.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIX (2026-09-26): models INA 202(a)(5)'s two-level family-preference
 * redistribution rule to compute how much of the family-preference capacity
 * that currently-restricted countries (Proclamation 10998) would normally
 * use is genuinely unabsorbable elsewhere in the family system -- as
 * opposed to naively assuming 100% of what they lose becomes available to
 * EB (which this app tried and rejected -- see GAPS_AND_FIXES.md), or
 * assuming other backlogged countries always fully absorb it (also
 * rejected once the real combined-cap math was checked).
 *
 * Level 1 (horizontal, within-category): unused numbers in a category go to
 * OTHER oversubscribed countries in that SAME category, but only up to each
 * country's real ceiling -- which is ONE COMBINED 15,820 (7% of 226,000)
 * cap shared across F1/F2A-subject/F2B/F3/F4 ("flexible numbers"), not five
 * separate per-category sub-caps. F2A's 75% exemption (INA 202(a)(4)(A))
 * isn't subject to any per-country cap at all.
 *
 * Level 2 (vertical, inter-category): whatever still can't be placed
 * cascades F1->F3, F2A/F2B->F4, F3->F4 (there's no path back to F1/F2, and
 * F4 is terminal within FB).
 *
 * Whatever survives BOTH levels is real, additional FB-to-EB spillover --
 * on top of, not instead of, the baseline shortfall already captured by
 * ExcelDataService.getFamilyVisasUsedPriorFiscalYear() (which reflects
 * FY2024's REAL aggregate usage, unaffected by a restriction that didn't
 * exist yet that year). See PredictionService.predict() for how the two
 * are combined.
 */
@Service
public class FamilyPreferenceSpilloverService {

    private static final String DATA_FILE = "family-preference-by-country.yml";

    // 7% of the 226,000 family-sponsored floor, covering the five CAPPED
    // categories combined (F1, F2A-subject, F2B, F3, F4) -- F2A-exempt is
    // separately uncapped and handled on its own.
    private static final double COMBINED_PER_COUNTRY_CAP = 15_820;

    // The four countries this app already treats as individually tracked on
    // the EB side, and which are also the traditionally family-preference-
    // backlogged countries in practice (multi-decade F3/F4 backlogs).
    // Rest-of-World's own absorption capacity isn't modeled -- a documented
    // simplification, conservative in the sense that it can only make this
    // method report MORE unabsorbed leftover than reality, never less.
    private static final List<String> NAMED_COUNTRIES = List.of("mexico", "philippines", "india", "china");

    private Map<String, Map<String, Double>> data;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        try (InputStream is = new ClassPathResource(DATA_FILE).getInputStream()) {
            Map<String, Object> root = new Yaml().load(is);
            Map<String, Object> section = (Map<String, Object>) root.get("family-preference-fy2024");
            data = new LinkedHashMap<>();
            for (Map.Entry<String, Object> countryEntry : section.entrySet()) {
                Map<String, Object> rawCategories = (Map<String, Object>) countryEntry.getValue();
                Map<String, Double> categories = new LinkedHashMap<>();
                for (Map.Entry<String, Object> catEntry : rawCategories.entrySet()) {
                    categories.put(catEntry.getKey(), ((Number) catEntry.getValue()).doubleValue());
                }
                data.put(countryEntry.getKey(), categories);
            }
            System.out.println("[FB-SPILLOVER] Loaded family-preference-by-country.yml: " + data.keySet());
        } catch (Exception e) {
            System.err.println("Error loading " + DATA_FILE + ": " + e.getMessage());
            data = Map.of();
        }
    }

    public double calculateRestrictionAdjustedSpillover() {
        if (data == null || !data.containsKey("restricted")) return 0;
        Map<String, Double> restricted = data.get("restricted");

        Map<String, Double> headroom = new LinkedHashMap<>();
        for (String country : NAMED_COUNTRIES) {
            Map<String, Double> usage = data.get(country);
            if (usage == null) continue;
            double totalUsed = usage.getOrDefault("f1", 0.0) + usage.getOrDefault("f2a-subject", 0.0)
                    + usage.getOrDefault("f2b", 0.0) + usage.getOrDefault("f3", 0.0) + usage.getOrDefault("f4", 0.0);
            headroom.put(country, Math.max(0, COMBINED_PER_COUNTRY_CAP - totalUsed));
        }

        // Level 1 -- source categories, fixed processing order (a documented
        // simplification of DOS's real simultaneous flexible-numbers
        // process), each drawing from whatever shared headroom remains.
        double leftoverF1 = redistribute(restricted.getOrDefault("f1", 0.0), headroom);
        double leftoverF2aSubject = redistribute(restricted.getOrDefault("f2a-subject", 0.0), headroom);
        double leftoverF2b = redistribute(restricted.getOrDefault("f2b", 0.0), headroom);

        // Level 2, first hop: unused F1 cascades into F3, alongside F3's own freed capacity.
        double f3Pool = restricted.getOrDefault("f3", 0.0) + leftoverF1;
        double leftoverF3 = redistribute(f3Pool, headroom);

        // Level 2, second hop: unused F2A/F2B cascades into F4, alongside F4's
        // own freed capacity AND whatever F3 still couldn't place.
        double f4Pool = restricted.getOrDefault("f4", 0.0) + leftoverF2aSubject + leftoverF2b + leftoverF3;
        double leftoverF4 = redistribute(f4Pool, headroom);

        // F2A-exempt (INA 202(a)(4)(A)): uncapped, so not constrained by
        // `headroom` at all -- assumed fully absorbed by ongoing real demand
        // for spouses/children of LPRs (a heavily-demanded category), so it
        // contributes 0 to genuine leftover. A documented assumption, not
        // something this app has data to verify either way.
        double leftoverF2aExempt = 0;

        double genuineLeftover = leftoverF4 + leftoverF2aExempt;
        System.out.println(String.format(
            "[FB-SPILLOVER] Restriction-adjusted model: F1->F3 leftover=%.0f, F2A-subj/F2B->F4 leftover=%.0f/%.0f, "
            + "F3 final leftover (->F4)=%.0f, F4 final leftover=%.0f, F2A-exempt assumed fully absorbed=0. "
            + "Genuine additional FB-to-EB spillover = %.0f",
            leftoverF1, leftoverF2aSubject, leftoverF2b, leftoverF3, leftoverF4, genuineLeftover));
        return genuineLeftover;
    }

    /**
     * Water-fills `pool` across `headroom`'s remaining capacity,
     * proportionally by each country's current share of total remaining
     * headroom, capped per-country, mutating `headroom` in place. Returns
     * whatever of `pool` couldn't be placed (headroom exhausted first).
     */
    private double redistribute(double pool, Map<String, Double> headroom) {
        double remainingPool = pool;
        for (int round = 0; round < 20 && remainingPool > 1e-6; round++) {
            double totalHeadroom = headroom.values().stream().mapToDouble(Double::doubleValue).sum();
            if (totalHeadroom <= 0) break;

            double distributedThisRound = 0;
            Map<String, Double> given = new LinkedHashMap<>();
            for (Map.Entry<String, Double> entry : headroom.entrySet()) {
                double share = remainingPool * (entry.getValue() / totalHeadroom);
                double give = Math.min(share, entry.getValue());
                given.put(entry.getKey(), give);
                distributedThisRound += give;
            }
            for (Map.Entry<String, Double> entry : given.entrySet()) {
                headroom.merge(entry.getKey(), -entry.getValue(), Double::sum);
            }
            remainingPool -= distributedThisRound;
            if (distributedThisRound <= 1e-9) break;
        }
        return Math.max(0, remainingPool);
    }
}
