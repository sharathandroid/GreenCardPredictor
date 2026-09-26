package org.innovativebrains.greencardpredictor.service;

import org.innovativebrains.greencardpredictor.model.Applicant;
import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.innovativebrains.greencardpredictor.model.PredictionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * See GAPS_AND_FIXES.md for the full write-up. Summary of what changed here
 * on 2026-09-25 versus the original version of this file:
 *
 *  1. FB-to-EB spillover no longer compares the multi-million-row I-130
 *     PENDING petition backlog against the 226,000 family limit (that
 *     always evaluated to zero -- wrong units, demand vs. usage). It now
 *     compares actual/estimated prior-FY family visa USAGE against the
 *     limit, and lets a "worldwide consular pause severity" input reduce
 *     that usage figure, which is the real causal path by which the
 *     current (Aug-Sept 2026) consular pause pushes numbers toward EB.
 *  2. The ~14.2% of each country's annual limit that belongs to EB-4/EB-5
 *     was previously computed and then silently dropped (never added to
 *     any country's supply, never spilled anywhere). Per INA 203(b) and
 *     9 FAM 502.1-1, unused EB-4 and unreserved EB-5 numbers fall FIRST to
 *     EB-1, then the EB-1 remainder to EB-2, then to EB-3 (there is no
 *     statutory pathway back out of EB-3). This service doesn't model
 *     EB-4/EB-5 demand, so it conservatively treats their whole share as
 *     "unused" and folds it into each country's EB-1 pool before running
 *     the existing EB-1->EB-2->EB-3 waterfall.
 *  3. The restricted-country list and its legal citation are current as of
 *     2026-09-25 (see Country.java) rather than the vacated Jan 2026
 *     75-country order. The explanation text says so explicitly and is
 *     dated, instead of asserting a permanent-sounding fact in a fast-
 *     moving legal area.
 *  4. Visa-bulletin "Unauthorized" (no numbers available this fiscal year)
 *     is now a distinct, explained outcome instead of being silently
 *     treated as if the category were wide open.
 *
 * Added 2026-09-26 (previously flagged in GAPS_AND_FIXES.md as open gaps):
 *  5. INA 202(b) cross-chargeability: Applicant.spouseCountryOfBirth lets a
 *     beneficiary elect their spouse's country of birth instead of their own
 *     when that yields a more favorable outcome (see estimateWait() and its
 *     use in predict()). Never applied if it wouldn't help, or if the
 *     spouse's country is itself restricted.
 *  6. AOS-vs-consular-processing distinction for restricted countries:
 *     Applicant.filingDomestically changes the restricted-country
 *     explanation to acknowledge that domestic adjustment-of-status may
 *     still be possible (per a June 2026 district court ruling), instead of
 *     flatly stating issuance is blocked. Still can't produce a numeric
 *     estimate for that path -- the backlog data isn't split by filing
 *     location -- so this changes the explanation, not the underlying
 *     supply/demand model.
 *
 * NOT changed: the pre-existing consular-shutdown simulation (EB2 holdback
 * in the EB2->EB3 vertical spillover, EB2 weight boost in the horizontal
 * redistribution) and the 70/30 pre-2015-backlog-weighted priority used to
 * split redistributed visas among oversubscribed countries. Those weren't
 * reported as broken and weren't in scope for this pass, so they're kept
 * as originally designed (only re-plumbed to sit correctly alongside the
 * EB-4/EB-5 residual fold-in above); the horizontal-redistribution cap was
 * adjusted to use each country's actual remaining demand rather than the
 * weighted priority score, since the score isn't itself a visa count and
 * shouldn't have been used to cap how many visas get distributed.
 */
@Service
public class PredictionService {

    public static final LocalDate DATA_AS_OF = LocalDate.of(2026, 9, 25);

    private final ExcelDataService excelDataService;
    private final VisaBulletinService visaBulletinService;

    public PredictionService(ExcelDataService excelDataService, VisaBulletinService visaBulletinService) {
        this.excelDataService = excelDataService;
        this.visaBulletinService = visaBulletinService;
    }

    // Total Employment-Based (EB) annual limit is approx 140,000 (INA 201(d)).
    private static final double BASE_ANNUAL_EB_LIMIT = 140000;

    // Per-country cap is 7% of the total EB limit (INA 202(a)(2)).
    private static final double COUNTRY_CAP_PERCENTAGE = 0.07;

    // Statutory floor for the worldwide family-sponsored limit (INA 201(c)(1)(B)).
    private static final double FAMILY_SPONSORED_FLOOR = 226000;

    // Allocation percentages for each tracked category:
    // EB1: 28.6%, EB2: 28.6%, EB3: 28.6%. The remaining 14.2% is EB4+EB5's
    // statutory share; this app does not model EB4/EB5 demand directly, so
    // that residual is folded into EB1 in calculateDynamicSupply() (see
    // class-level note #2 above) rather than being dropped.
    private static final Map<EbCategory, Double> CATEGORY_ALLOCATION = new HashMap<>();
    static {
        CATEGORY_ALLOCATION.put(EbCategory.EB1, 0.286);
        CATEGORY_ALLOCATION.put(EbCategory.EB2, 0.286);
        CATEGORY_ALLOCATION.put(EbCategory.EB3, 0.286);
    }
    private static final double EB4_EB5_RESIDUAL_SHARE =
            1.0 - CATEGORY_ALLOCATION.values().stream().mapToDouble(Double::doubleValue).sum();

    public PredictionResult predict(Applicant applicant) {
        Country country = applicant.getCountry();
        EbCategory category = applicant.getCategory();

        // Handle countries currently subject to Presidential Proclamation
        // 10998's travel ban (full, partial, or immigrant-visa-only
        // suspension -- see Country.java for the current, dated list and
        // citations). NOTE: this is a snapshot as of DATA_AS_OF and WILL
        // change again; this area of law has changed multiple times within
        // 2026 alone.
        if (isRestricted(country)) {
            // FIX (2026-09-26): Proclamation 10998's suspension is strongest
            // for consular issuance abroad; a June 2026 district court ruling
            // allowed some domestic adjustment-of-status (I-485) processing
            // to continue for affected nationals. This app's backlog data
            // isn't split by filing location, so a domestic filer still
            // can't get a numeric estimate here -- but they get an
            // explanation that reflects that possibility instead of a flat,
            // possibly-inaccurate "blocked" statement.
            if (applicant.isFilingDomestically()) {
                return PredictionResult.builder()
                        .restricted(true)
                        .explanation(String.format(
                            "As of %s, immigrant-visa issuance for %s is suspended under Presidential " +
                            "Proclamation 10998 (eff. Jan 1, 2026), which is strongest for consular " +
                            "processing abroad. A June 2026 district court ruling allowed some domestic " +
                            "adjustment-of-status (Form I-485) processing to continue for affected " +
                            "nationals, so a domestic filing may still be possible -- but this app's " +
                            "backlog data isn't split by filing location, so it cannot produce a numeric " +
                            "wait estimate for that path. Consult an immigration attorney and re-check " +
                            "travel.state.gov/uscis.gov; this status is litigation-sensitive.",
                            DATA_AS_OF, country))
                        .formattedFilingWait("N/A (Restricted -- domestic AOS may be possible, not modeled)")
                        .formattedFinalActionWait("N/A (Restricted -- domestic AOS may be possible, not modeled)")
                        .finalActionWaitMonths(999)
                        .filingWaitMonths(999)
                        .build();
            }
            return PredictionResult.builder()
                    .restricted(true)
                    .explanation(String.format(
                        "As of %s, immigrant-visa issuance for %s is suspended under Presidential " +
                        "Proclamation 10998 (eff. Jan 1, 2026). This status is litigation-sensitive and " +
                        "changes on the order of weeks to months -- re-check travel.state.gov before " +
                        "relying on this. Predicting a wait time is not meaningful while issuance is suspended.",
                        DATA_AS_OF, country))
                    .formattedFilingWait("N/A (Restricted)")
                    .formattedFinalActionWait("N/A (Restricted)")
                    .finalActionWaitMonths(999)
                    .filingWaitMonths(999)
                    .build();
        }

        // FB-to-EB spillover (INA 201(c)/(d)): unused family-sponsored
        // numbers from the prior fiscal year become available to the
        // employment-based pool. FIX: this must compare the family
        // limit against family visas ACTUALLY USED last FY, not against
        // the pending I-130 petition backlog (a demand queue running into
        // the millions, which made the old formula always clamp to zero).
        double fbToEbSpillover;
        if (applicant.getManualFbSpillover() != null) {
            fbToEbSpillover = applicant.getManualFbSpillover();
        } else {
            double familyVisasUsed = excelDataService.getFamilyVisasUsedPriorFiscalYear();
            // The ongoing worldwide consular-interview pause (DOS, ~Aug 25,
            // 2026 onward; primarily affects family-based cases -- see
            // GAPS_AND_FIXES.md) further suppresses family visa usage. A
            // caller can estimate how much of the fiscal year it will have
            // suppressed via familyVisaPauseSeverity (0 = no effect).
            double severity = Math.max(0.0, Math.min(1.0, applicant.getFamilyVisaPauseSeverity()));
            familyVisasUsed = familyVisasUsed * (1.0 - severity);
            fbToEbSpillover = Math.max(0, FAMILY_SPONSORED_FLOOR - familyVisasUsed);
        }

        double totalAnnualEbLimit = BASE_ANNUAL_EB_LIMIT + fbToEbSpillover;
        double countryAnnualLimit = totalAnnualEbLimit * COUNTRY_CAP_PERCENTAGE;

        Map<Country, Map<EbCategory, Double>> supplyMap =
                calculateDynamicSupply(totalAnnualEbLimit, countryAnnualLimit, applicant.isConsularShutdown());

        LocalDate applicantPD = applicant.getPriorityDate();

        // FIX (2026-09-26): INA 202(b) cross-chargeability -- a beneficiary
        // married to someone born in a different country may elect to be
        // charged to the spouse's country of birth instead of their own,
        // per 9 FAM 503.2, when that produces a more favorable (earlier)
        // outcome. It's elective, so this only switches when the spouse's
        // country is actually better; it never makes the result worse, and
        // it doesn't apply if the spouse's country is itself restricted
        // (cross-chargeability affects visa-number accounting, not the
        // nationality-based travel-ban analysis above).
        Country chargeabilityCountry = country;
        String crossChargeabilityNote = "";
        Country spouseCountry = applicant.getSpouseCountryOfBirth();
        WaitEstimate estimate = estimateWait(country, category, applicantPD, supplyMap);
        if (spouseCountry != null && spouseCountry != country && !isRestricted(spouseCountry)) {
            WaitEstimate spouseEstimate = estimateWait(spouseCountry, category, applicantPD, supplyMap);
            boolean spouseIsBetter = !spouseEstimate.unauthorized()
                    && (estimate.unauthorized() || spouseEstimate.finalWaitMonths() < estimate.finalWaitMonths());
            if (spouseIsBetter) {
                chargeabilityCountry = spouseCountry;
                estimate = spouseEstimate;
                crossChargeabilityNote = String.format(
                    " [CROSS-CHARGEABILITY (INA 202(b)) ELECTED: charged to spouse's country of birth " +
                    "(%s) instead of %s -- yields a more favorable outcome.]", spouseCountry, country);
            }
        }

        // 1. Anchor to Official Visa Bulletin Dates
        if (estimate.unauthorized()) {
            return PredictionResult.builder()
                    .restricted(false)
                    .explanation(String.format(
                        "The %s Visa Bulletin marks %s %s Final Action as \"Unauthorized\": the annual " +
                        "limit for this category/country has already been reached for the current fiscal " +
                        "year. No further final action is possible until new numbers become available at " +
                        "the start of the next fiscal year (normally October 1).%s",
                        visaBulletinService.getBulletinMonth(), chargeabilityCountry, category, crossChargeabilityNote))
                    .formattedFilingWait("N/A (Unauthorized this FY)")
                    .formattedFinalActionWait("N/A (Unauthorized this FY)")
                    .finalActionWaitMonths(999)
                    .filingWaitMonths(999)
                    .build();
        }

        long filingWaitMonths = estimate.filingWaitMonths();
        long finalWaitMonths = estimate.finalWaitMonths();

        LocalDate filingDate = LocalDate.now().plusMonths(filingWaitMonths);
        LocalDate finalActionDate = LocalDate.now().plusMonths(finalWaitMonths);

        if (filingDate.isAfter(finalActionDate)) {
            filingDate = finalActionDate;
            filingWaitMonths = finalWaitMonths;
        }

        String formattedFinalActionWait = formatToMonthYear(finalActionDate);
        String formattedFilingWait = formatToMonthYear(filingDate);

        String shutdownNote = applicant.isConsularShutdown() ? " [CONSULAR SHUTDOWN MODE ACTIVE: EB2 PRIORITIZED]" : "";

        return PredictionResult.builder()
                .finalActionDate(finalActionDate)
                .filingDate(filingDate)
                .filingWaitMonths(filingWaitMonths)
                .finalActionWaitMonths(finalWaitMonths)
                .formattedFilingWait(formattedFilingWait)
                .formattedFinalActionWait(formattedFinalActionWait)
                .explanation(String.format(
                    "Bulletin-Anchored Prediction (%s bulletin, chargeable to %s). Filing Cut-off: %s, Final Action Cut-off: %s. " +
                    "Annual supply: ~%.0f. Inventory + I-140 Ahead of Filing PD: %d. Priority Date: %s.%s%s",
                    visaBulletinService.getBulletinMonth(), chargeabilityCountry, estimate.filingCutOff(), estimate.finalActionCutOff(),
                    supplyMap.get(chargeabilityCountry).get(category), estimate.volumeAheadOfFiling(), applicantPD,
                    shutdownNote, crossChargeabilityNote))
                .build();
    }

    /**
     * One country/category's bulletin-anchored wait estimate. Used both for
     * the applicant's own country and, when INA 202(b) cross-chargeability
     * is in play, their spouse's country of birth, so the two can be
     * compared and the more favorable one elected.
     */
    private record WaitEstimate(long filingWaitMonths, long finalWaitMonths, LocalDate filingCutOff,
                                 LocalDate finalActionCutOff, long volumeAheadOfFiling, boolean unauthorized) {
    }

    private WaitEstimate estimateWait(Country chargeabilityCountry, EbCategory category, LocalDate applicantPD,
                                       Map<Country, Map<EbCategory, Double>> supplyMap) {
        if (visaBulletinService.isFinalActionUnauthorized(chargeabilityCountry, category)) {
            return new WaitEstimate(999, 999, null, null, 0, true);
        }

        double annualSupply = supplyMap.get(chargeabilityCountry).get(category);
        LocalDate filingCutOff = visaBulletinService.getFilingCutOff(chargeabilityCountry, category);
        LocalDate finalActionCutOff = visaBulletinService.getFinalActionCutOff(chargeabilityCountry, category);

        long volumeAheadOfFiling = excelDataService.getInventoryBetween(chargeabilityCountry, category, filingCutOff, applicantPD);
        volumeAheadOfFiling += excelDataService.getI140Between(chargeabilityCountry, category, filingCutOff, applicantPD);

        long volumeAheadOfFinalAction = excelDataService.getInventoryBetween(chargeabilityCountry, category, finalActionCutOff, applicantPD);
        volumeAheadOfFinalAction += excelDataService.getI140Between(chargeabilityCountry, category, finalActionCutOff, applicantPD);

        long finalWaitMonths;
        long filingWaitMonths;
        if (annualSupply > 0) {
            filingWaitMonths = (long) ((volumeAheadOfFiling / annualSupply) * 12);
            finalWaitMonths = (long) ((volumeAheadOfFinalAction / annualSupply) * 12);
        } else {
            finalWaitMonths = 600;
            filingWaitMonths = 300;
        }

        return new WaitEstimate(filingWaitMonths, finalWaitMonths, filingCutOff, finalActionCutOff, volumeAheadOfFiling, false);
    }

    private boolean isRestricted(Country country) {
        return country != null && country.isRestricted();
    }

    private String formatToMonthYear(LocalDate date) {
        String month = date.getMonth().name().toLowerCase();
        month = month.substring(0, 1).toUpperCase() + month.substring(1);
        return month + " " + date.getYear();
    }

    private Map<Country, Map<EbCategory, Double>> calculateDynamicSupply(double totalAnnualEbLimit, double countryAnnualLimit, boolean consularShutdown) {
        Map<Country, Map<EbCategory, Double>> supplyMap = new HashMap<>();
        Map<Country, Map<EbCategory, Long>> demandMap = new HashMap<>();

        System.out.println("[CALCULATION] Starting Dynamic EB Supply Calculation...");
        if (consularShutdown) {
            System.out.println("[CALCULATION] CONSULAR SHUTDOWN MODE ACTIVE (Simulating FY2020-2022 EB2 prioritization)");
        }
        System.out.println("[CALCULATION] Total EB Annual Limit (including FB spillover): " + String.format("%.0f", totalAnnualEbLimit));

        // 1. Initialize Demand (Backlog). Restricted countries have zero
        // demand for allocation purposes since no visas can currently be
        // issued to their nationals.
        for (Country c : Country.values()) {
            demandMap.put(c, new HashMap<>());
            boolean restricted = isRestricted(c);
            for (EbCategory cat : EbCategory.values()) {
                long backlog = excelDataService.getBacklog(c, cat);
                demandMap.get(c).put(cat, restricted ? 0L : backlog);
            }
        }

        // 2. Initialize Base Supply (7% rule), folding each country's
        // EB4/EB5 residual share into its EB1 pool before the waterfall
        // runs (see class-level note #2: INA 203(b) sends unused EB4/
        // unreserved EB5 numbers to EB1 first, not the other way around).
        for (Country c : Country.values()) {
            Map<EbCategory, Double> catSupply = new HashMap<>();
            double countryLimit = (c == Country.ROW) ? totalAnnualEbLimit * (1 - (5 * COUNTRY_CAP_PERCENTAGE)) : countryAnnualLimit;

            for (EbCategory cat : EbCategory.values()) {
                double allocation = countryLimit * CATEGORY_ALLOCATION.get(cat);
                catSupply.put(cat, allocation);
            }
            double eb4Eb5Residual = countryLimit * EB4_EB5_RESIDUAL_SHARE;
            catSupply.put(EbCategory.EB1, catSupply.get(EbCategory.EB1) + eb4Eb5Residual);

            supplyMap.put(c, catSupply);
        }

        // 3. Vertical Spillover (EB1 -> EB2 -> EB3) - Country by Country.
        // In consular-shutdown mode we simulate 2020-2022 conditions where
        // AOS-heavy EB2 absorbed most of the spillover ahead of EB3.
        for (Country c : Country.values()) {
            double eb1Supply = supplyMap.get(c).get(EbCategory.EB1);
            long eb1Demand = demandMap.get(c).get(EbCategory.EB1);
            double eb1Unused = Math.max(0, eb1Supply - eb1Demand);
            if (eb1Unused > 0) {
                supplyMap.get(c).put(EbCategory.EB1, (double) Math.min(eb1Supply, eb1Demand));
                double eb2Supply = supplyMap.get(c).get(EbCategory.EB2) + eb1Unused;
                supplyMap.get(c).put(EbCategory.EB2, eb2Supply);
                System.out.println(String.format("[CALCULATION] %s: Vertical Spillover EB1 -> EB2: %.2f", c, eb1Unused));
            }

            double eb2Supply = supplyMap.get(c).get(EbCategory.EB2);
            long eb2Demand = demandMap.get(c).get(EbCategory.EB2);
            double eb2Unused = Math.max(0, eb2Supply - eb2Demand);
            if (eb2Unused > 0) {
                // Consular-shutdown mode (kept from the original design, not
                // something this pass was asked to change): EB2 is mostly
                // filed domestically (AOS), while EB3 skews toward consular
                // processing abroad, which is what a consular shutdown
                // actually disrupts. So in that mode we hold back most of
                // EB2's surplus in EB2 instead of letting it all spill to
                // EB3, rather than assuming a shutdown affects both equally.
                double passedToEb3 = eb2Unused;
                if (consularShutdown && !isRestricted(c)) {
                    double heldInEb2 = eb2Unused * 0.8;
                    passedToEb3 = eb2Unused - heldInEb2;
                    System.out.println(String.format("[CALCULATION] %s: Consular Shutdown - Holding %.2f surplus visas in EB2", c, heldInEb2));
                    supplyMap.get(c).put(EbCategory.EB2, eb2Demand + heldInEb2);
                } else {
                    supplyMap.get(c).put(EbCategory.EB2, (double) Math.min(eb2Supply, eb2Demand));
                }
                double eb3Supply = supplyMap.get(c).get(EbCategory.EB3) + passedToEb3;
                supplyMap.get(c).put(EbCategory.EB3, eb3Supply);
                System.out.println(String.format("[CALCULATION] %s: Vertical Spillover EB2 -> EB3: %.2f", c, passedToEb3));
            }
        }

        // 4. Horizontal/Global Redistribution: numbers left unused in any
        // category after the vertical waterfall (typically from
        // low-demand or restricted countries) are pooled and redistributed
        // to oversubscribed countries in that same category, proportional
        // to each oversubscribed country's remaining backlog.
        System.out.println("[CALCULATION] Processing Horizontal/Global Redistribution...");
        for (EbCategory cat : EbCategory.values()) {
            double pooledUnused = 0;
            for (Country c : Country.values()) {
                double supply = supplyMap.get(c).get(cat);
                long demand = demandMap.get(c).get(cat);
                if (supply > demand) {
                    pooledUnused += (supply - demand);
                    supplyMap.get(c).put(cat, (double) demand);
                }
            }

            if (pooledUnused <= 0) continue;

            // Distribution weight (kept from the original design, not
            // something this pass was asked to change): countries are
            // prioritized 70% by their pre-2015 backlog and 30% by total
            // remaining demand, so the oldest queues get first claim on
            // redistributed visas rather than splitting purely pro rata by
            // current backlog size. In consular-shutdown mode, EB2 gets an
            // extra 50% weight boost since it absorbs most of the era's
            // spillover (see the EB2->EB3 holdback above).
            Map<Country, Long> oversubscribed = new HashMap<>();
            Map<Country, Long> remainingDemandByCountry = new HashMap<>();
            long totalWeight = 0;
            for (Country c : Country.values()) {
                double supply = supplyMap.get(c).get(cat);
                long demand = demandMap.get(c).get(cat);
                long remaining = demand - (long) supply;
                if (remaining > 0) {
                    long oldBacklog = excelDataService.getOldestBacklog(c, cat, 2015);
                    long weight = (long) (oldBacklog * 0.7 + remaining * 0.3);
                    if (weight == 0) weight = 1;
                    if (consularShutdown && cat == EbCategory.EB2) {
                        weight = (long) (weight * 1.5);
                    }
                    oversubscribed.put(c, weight);
                    remainingDemandByCountry.put(c, remaining);
                    totalWeight += weight;
                }
            }

            if (totalWeight <= 0) {
                System.out.println(String.format("[CALCULATION] No oversubscribed demand for %s. Pool of %.2f goes unused.", cat, pooledUnused));
                continue;
            }

            long totalRemainingDemand = remainingDemandByCountry.values().stream().mapToLong(Long::longValue).sum();
            double visasDistributed = Math.min(pooledUnused, totalRemainingDemand);

            // FIX (2026-09-26): a straight one-pass weight-proportional split
            // (visasDistributed * shareOfWeight, added with no cap) could hand
            // a country MORE redistributed visas than its own remaining
            // demand -- weight is a 70/30 blend of old-backlog and remaining
            // demand, not equal to remaining demand itself, so a country with
            // a huge pre-2015 backlog but a now-small remaining gap could
            // still claim a large share. That's not just wasteful (supply
            // exceeding real demand for that country/category), it also made
            // the model non-monotonic: a LARGER total EB limit could lower a
            // heavily-oversubscribed country's effective supply after
            // redistribution, because other countries' pre-cap over-shares
            // shifted as the numbers moved -- this surfaced as a failing test
            // once real backlog data (see DATA_SOURCES.md) replaced the
            // stale, much-smaller figures that never triggered the edge case.
            //
            // Water-filling instead: give each still-needy country its
            // weight-proportional share of what's LEFT each round, capped at
            // that country's own remaining demand; anything a capped country
            // couldn't use goes back into the pool and is re-split among the
            // countries still under their cap, repeating until the pool is
            // gone or nobody remaining can use more. This guarantees no
            // country's final supply ever exceeds its own demand, and makes
            // supply monotonically non-decreasing in totalAnnualEbLimit.
            Map<Country, Long> remainingCapacity = new HashMap<>(remainingDemandByCountry);
            Map<Country, Long> activeWeights = new HashMap<>(oversubscribed);
            Map<Country, Double> allocated = new HashMap<>();
            double poolLeft = visasDistributed;

            for (int round = 0; round < 50 && poolLeft > 1e-6 && !activeWeights.isEmpty(); round++) {
                long activeTotalWeight = activeWeights.values().stream().mapToLong(Long::longValue).sum();
                if (activeTotalWeight <= 0) break;

                double distributedThisRound = 0;
                List<Country> filled = new ArrayList<>();
                for (Map.Entry<Country, Long> entry : activeWeights.entrySet()) {
                    Country c = entry.getKey();
                    double share = poolLeft * ((double) entry.getValue() / activeTotalWeight);
                    double cap = remainingCapacity.get(c);
                    double give = Math.min(share, cap);
                    allocated.merge(c, give, Double::sum);
                    distributedThisRound += give;
                    double newCap = cap - give;
                    remainingCapacity.put(c, (long) newCap);
                    if (newCap <= 0) filled.add(c);
                }
                poolLeft -= distributedThisRound;
                activeWeights.keySet().removeAll(filled);
                // If nobody hit their cap this round, every share was fully
                // used and the pool is (about to be) exhausted -- no need to
                // keep looping.
                if (filled.isEmpty()) break;
            }

            for (Map.Entry<Country, Double> entry : allocated.entrySet()) {
                supplyMap.get(entry.getKey()).put(cat, supplyMap.get(entry.getKey()).get(cat) + entry.getValue());
                System.out.println(String.format("[CALCULATION] Redistributing %.2f unused %s visas to %s", entry.getValue(), cat, entry.getKey()));
            }
        }

        for (Country c : Country.values()) {
            for (EbCategory cat : EbCategory.values()) {
                System.out.println(String.format("[CALCULATION] FINAL SUPPLY for %s %s: %.2f", c, cat, supplyMap.get(c).get(cat)));
            }
        }

        System.out.println("[CALCULATION] Dynamic supply calculation complete.");
        return supplyMap;
    }
}
