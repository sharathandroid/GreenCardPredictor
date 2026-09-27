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
 *  7. Only the five countries actually named on the bulletin's per-country
 *     chart (India, China, Mexico, Philippines, Brazil) now get their own
 *     7% allocation in calculateDynamicSupply(); every other Country enum
 *     value previously also claimed a full 7% of its own, which meant the
 *     "unused" remainder from ~95 near-zero-demand countries inflated the
 *     horizontal-redistribution pool to several times the real global
 *     total -- concretely, this could make a priority date years behind
 *     the bulletin's own cutoff date look almost current. See
 *     GAPS_AND_FIXES.md #9 for how this was found.
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
    private final FamilyPreferenceSpilloverService familyPreferenceSpilloverService;

    public PredictionService(ExcelDataService excelDataService, VisaBulletinService visaBulletinService,
                              FamilyPreferenceSpilloverService familyPreferenceSpilloverService) {
        this.excelDataService = excelDataService;
        this.visaBulletinService = visaBulletinService;
        this.familyPreferenceSpilloverService = familyPreferenceSpilloverService;
    }

    // FIX (2026-09-27, GAPS_AND_FIXES.md #16): the real worldwide EB limit and
    // per-country cap now come from the bulletin itself (visaBulletinService,
    // backed by visa-bulletin.yml), not from these fixed statutory-floor
    // constants -- see predict()'s baseAnnualEbLimit/countryAnnualLimit.
    // BASE_ANNUAL_EB_LIMIT is kept only as the base for this app's own
    // informational spillover estimate (a comparison figure shown in
    // reasoningSteps, no longer used for the actual supply calculation).
    private static final double BASE_ANNUAL_EB_LIMIT = 140000;

    // The five countries actually named on the Visa Bulletin's per-country
    // chart (see PREDICTION_STRATEGY.md's "Apply 7% Country Cap" section).
    // Every other country falls under "All Chargeability Areas Except Those
    // Listed" (ROW) and draws only from ROW's shared residual allocation, not
    // an individual per-country cap of their own.
    private static final Set<Country> INDIVIDUALLY_CAPPED_COUNTRIES = EnumSet.of(
            Country.INDIA, Country.CHINA, Country.MEXICO, Country.PHILIPPINES, Country.BRAZIL);

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

        // FIX (2026-09-26): every branch below now builds a step-by-step
        // trace (PredictionResult.reasoningSteps) alongside the existing
        // single-paragraph `explanation`, so a caller can see exactly which
        // inputs and intermediate numbers drove the result instead of
        // having to reverse-engineer one sentence. Requested after a user
        // pointed out the app was giving "Unauthorized" with no visibility
        // into what was actually computed underneath it.
        List<String> steps = new ArrayList<>();
        int stepNum = 1;

        // Handle countries currently subject to Presidential Proclamation
        // 10998's travel ban (full, partial, or immigrant-visa-only
        // suspension -- see Country.java for the current, dated list and
        // citations). NOTE: this is a snapshot as of DATA_AS_OF and WILL
        // change again; this area of law has changed multiple times within
        // 2026 alone.
        if (isRestricted(country)) {
            steps.add(stepNum++ + ". Checked restriction status for " + country
                    + ": RESTRICTED under Presidential Proclamation 10998 (as of " + DATA_AS_OF + ").");
            // FIX (2026-09-26): Proclamation 10998's suspension is strongest
            // for consular issuance abroad; a June 2026 district court ruling
            // allowed some domestic adjustment-of-status (I-485) processing
            // to continue for affected nationals. This app's backlog data
            // isn't split by filing location, so a domestic filer still
            // can't get a numeric estimate here -- but they get an
            // explanation that reflects that possibility instead of a flat,
            // possibly-inaccurate "blocked" statement.
            if (applicant.isFilingDomestically()) {
                steps.add(stepNum++ + ". Applicant indicated domestic filing: a June 2026 district court "
                        + "ruling allows some domestic AOS processing to continue despite the ban, so this "
                        + "is not treated as a hard block -- but no backlog data exists split by filing "
                        + "location, so no numeric wait can be computed.");
                steps.add("Conclusion: restricted, domestic filing may be legally possible but not quantifiable with current data.");
                return PredictionResult.builder()
                        .restricted(true)
                        .reasoningSteps(steps)
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
            steps.add(stepNum++ + ". No domestic-filing override was set: treated as fully blocked while the ban is in effect.");
            steps.add("Conclusion: restricted, no wait-time prediction is meaningful.");
            return PredictionResult.builder()
                    .restricted(true)
                    .reasoningSteps(steps)
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
        steps.add(stepNum++ + ". Checked restriction status for " + country + ": not restricted.");

        // FB-to-EB spillover (INA 201(c)/(d)): unused family-sponsored
        // numbers from the prior fiscal year become available to the
        // employment-based pool. FIX: this must compare the family
        // limit against family visas ACTUALLY USED last FY, not against
        // the pending I-130 petition backlog (a demand queue running into
        // the millions, which made the old formula always clamp to zero).
        // FIX (2026-09-27, GAPS_AND_FIXES.md #16): the worldwide EB limit and
        // per-country cap are now taken directly from the current bulletin
        // (visa-bulletin.yml), not derived from a hardcoded 140,000 base.
        // DOS publishes both numbers outright each month (e.g. September
        // 2026: worldwide EB limit 186,317; per-country cap 29,136, 7% of
        // family+EB combined per INA 202(a)(2) plus EB-5 carryover under INA
        // 203(b)(5)(B)), computed from real USCIS data DOS already has --
        // including whatever FB-to-EB spillover actually occurred this
        // fiscal year. Re-deriving an estimate of that same number from a
        // fixed base plus a modeled spillover is strictly less accurate than
        // using DOS's own published total, so the modeled estimate below is
        // now informational only (shown in reasoningSteps for comparison)
        // and no longer feeds the supply calculation in the default path.
        double baseAnnualEbLimit = visaBulletinService.getWorldwideEbLimit();
        double countryAnnualLimit = visaBulletinService.getPerCountryCap();
        steps.add(String.format(stepNum++ + ". Worldwide EB annual limit = %.0f and per-country cap = %.0f "
                + "(published directly in the %s bulletin -- INA 202(a)(2)'s 7%% ceiling on family+EB combined, "
                + "not derived from a fixed base).",
                baseAnnualEbLimit, countryAnnualLimit, visaBulletinService.getBulletinMonth()));

        double fbToEbSpillover;
        if (applicant.getManualFbSpillover() != null) {
            // A manual override represents a hypothetical scenario (e.g.
            // "what if the consular pause pushes X more family numbers to
            // EB-1 than the published total already reflects") -- it's
            // additional to the real published worldwide limit above, not a
            // replacement for it.
            fbToEbSpillover = applicant.getManualFbSpillover();
            steps.add(stepNum++ + ". FB-to-EB spillover: manual override supplied = " + fbToEbSpillover
                    + " (treated as additional to the published worldwide limit above, entering only at EB-1).");
        } else {
            fbToEbSpillover = 0;
            steps.add(stepNum++ + ". FB-to-EB spillover: not modeled separately in the default path, since the "
                    + "published worldwide EB limit above already reflects whatever spillover actually occurred "
                    + "this fiscal year (DOS computes it from real USCIS data before publishing the bulletin).");

            // Kept for comparison only -- this is this app's own estimate of
            // the same figure DOS already publishes above, useful for
            // sanity-checking the model against a real bulletin but not fed
            // into the supply calculation.
            double rawFamilyVisasUsed = excelDataService.getFamilyVisasUsedPriorFiscalYear();
            double severity = Math.max(0.0, Math.min(1.0, applicant.getFamilyVisaPauseSeverity()));
            double familyVisasUsed = rawFamilyVisasUsed * (1.0 - severity);
            double baselineSpillover = Math.max(0, FAMILY_SPONSORED_FLOOR - familyVisasUsed);
            double restrictionAdjustedSpillover = familyPreferenceSpilloverService.calculateRestrictionAdjustedSpillover();
            double modeledSpilloverEstimate = baselineSpillover + restrictionAdjustedSpillover;
            steps.add(String.format(stepNum++ + ". (Informational only) this app's own modeled FB-to-EB spillover "
                    + "estimate, for comparison against the published total: family-preference usage %.0f%s vs. "
                    + "floor %.0f -> baseline %.0f, plus restriction-adjusted %.0f (INA 202(a)(5) redistribution) "
                    + "= %.0f. Modeled EB base (%.0f + %.0f = %.0f) vs. published %.0f.",
                    rawFamilyVisasUsed,
                    severity > 0 ? String.format(", reduced %.0f%% for estimated consular-pause severity -> %.0f", severity * 100, familyVisasUsed) : "",
                    FAMILY_SPONSORED_FLOOR, baselineSpillover, restrictionAdjustedSpillover, modeledSpilloverEstimate,
                    BASE_ANNUAL_EB_LIMIT, modeledSpilloverEstimate, BASE_ANNUAL_EB_LIMIT + modeledSpilloverEstimate, baseAnnualEbLimit));
        }

        Map<Country, Map<EbCategory, Double>> supplyMap =
                calculateDynamicSupply(baseAnnualEbLimit, countryAnnualLimit, fbToEbSpillover, applicant.isConsularShutdown());
        steps.add(stepNum++ + ". Ran the full supply model: base allocation (from the published worldwide limit "
                + "and per-country cap above), FB spillover injected at EB-1, EB1->EB2->EB3 waterfall with "
                + "horizontal (cross-country, priority-date-ordered) redistribution running within each category before any leftover "
                + "cascades to the next one down (incl. EB4/EB5 residual folded into EB1)"
                + (applicant.isConsularShutdown() ? " (consular-shutdown mode: EB2 prioritized)." : "."));

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
            steps.add(stepNum++ + ". Cross-chargeability (INA 202(b)) check: compared own country (" + country
                    + ") vs. spouse's country of birth (" + spouseCountry + ") -> "
                    + (spouseIsBetter ? "elected " + spouseCountry + " (more favorable)." : "kept " + country + " (spouse's country was not more favorable)."));
            if (spouseIsBetter) {
                chargeabilityCountry = spouseCountry;
                estimate = spouseEstimate;
                crossChargeabilityNote = String.format(
                    " [CROSS-CHARGEABILITY (INA 202(b)) ELECTED: charged to spouse's country of birth " +
                    "(%s) instead of %s -- yields a more favorable outcome.]", spouseCountry, country);
            }
        }
        steps.add(stepNum++ + ". " + chargeabilityCountry + " " + category + " resulting annual supply "
                + "(after redistribution): ~" + String.format("%.0f", supplyMap.get(chargeabilityCountry).get(category)) + ".");
        steps.add(stepNum++ + ". " + visaBulletinService.getBulletinMonth() + " bulletin Filing Cut-off for "
                + chargeabilityCountry + " " + category + ": " + estimate.filingCutOff() + ".");
        steps.add(stepNum++ + ". Backlog (I-485 inventory + I-140 approvals) between the Filing Cut-off and "
                + "priority date " + applicantPD + ": " + estimate.volumeAheadOfFiling() + " cases ahead of you.");

        // 1. Anchor to Official Visa Bulletin Dates
        String shutdownNote = applicant.isConsularShutdown() ? " [CONSULAR SHUTDOWN MODE ACTIVE: EB2 PRIORITIZED]" : "";

        if (estimate.unauthorized()) {
            // FIX (2026-09-26): Final Action Unauthorized does NOT mean
            // filing is unavailable -- see estimateWait()'s Javadoc. Filing
            // is still computed and reported normally; only final action
            // collapses to "Unauthorized this FY".
            long filingWaitMonths = estimate.filingWaitMonths();
            LocalDate filingDate = LocalDate.now().plusMonths(filingWaitMonths);
            String formattedFilingWait = formatToMonthYear(filingDate);
            steps.add(stepNum++ + ". Filing wait = " + estimate.volumeAheadOfFiling() + " / "
                    + String.format("%.0f", supplyMap.get(chargeabilityCountry).get(category)) + " * 12 = "
                    + filingWaitMonths + " months -> Filing available: " + formattedFilingWait + ".");
            steps.add(stepNum++ + ". Final Action Cut-off for " + chargeabilityCountry + " " + category
                    + " is marked UNAUTHORIZED on the " + visaBulletinService.getBulletinMonth() + " bulletin "
                    + "(annual ceiling already reached) -- no green-card approval possible until next fiscal "
                    + "year. This does NOT affect Filing eligibility, which is a separate chart (see step above).");

            return PredictionResult.builder()
                    .restricted(false)
                    .reasoningSteps(steps)
                    .filingDate(filingDate)
                    .filingWaitMonths(filingWaitMonths)
                    .formattedFilingWait(formattedFilingWait)
                    .finalActionWaitMonths(999)
                    .formattedFinalActionWait("N/A (Unauthorized this FY)")
                    .explanation(String.format(
                        "The %s Visa Bulletin marks %s %s Final Action as \"Unauthorized\": the annual " +
                        "limit for this category/country has already been reached for the current fiscal " +
                        "year. No further final action (green card approval) is possible until new numbers " +
                        "become available at the start of the next fiscal year (normally October 1). Filing " +
                        "(submitting the application, e.g. for interim benefits like work authorization) is " +
                        "a separate chart and is NOT blocked by this -- Filing Cut-off: %s. Inventory + " +
                        "I-140 Ahead of Filing PD: %d.%s",
                        visaBulletinService.getBulletinMonth(), chargeabilityCountry, category,
                        estimate.filingCutOff(), estimate.volumeAheadOfFiling(), crossChargeabilityNote))
                    .build();
        }

        long filingWaitMonths = estimate.filingWaitMonths();
        long finalWaitMonths = estimate.finalWaitMonths();
        steps.add(stepNum++ + ". " + visaBulletinService.getBulletinMonth() + " bulletin Final Action Cut-off for "
                + chargeabilityCountry + " " + category + ": " + estimate.finalActionCutOff() + ".");
        double annualSupplyForSteps = supplyMap.get(chargeabilityCountry).get(category);
        steps.add(stepNum++ + ". Filing wait = " + estimate.volumeAheadOfFiling() + " / "
                + String.format("%.0f", annualSupplyForSteps) + " * 12 = " + filingWaitMonths + " months; "
                + "Final Action wait = " + String.format("%.0f", annualSupplyForSteps) + "-scaled volume ahead of "
                + "Final Action Cut-off -> " + finalWaitMonths + " months.");

        LocalDate filingDate = LocalDate.now().plusMonths(filingWaitMonths);
        LocalDate finalActionDate = LocalDate.now().plusMonths(finalWaitMonths);

        if (filingDate.isAfter(finalActionDate)) {
            filingDate = finalActionDate;
            filingWaitMonths = finalWaitMonths;
            steps.add(stepNum++ + ". Filing date computed later than Final Action date (can't file after you'd "
                    + "already be approved) -- capped Filing to match Final Action.");
        }

        String formattedFinalActionWait = formatToMonthYear(finalActionDate);
        String formattedFilingWait = formatToMonthYear(filingDate);
        steps.add("Conclusion: Filing " + formattedFilingWait + ", Final Action " + formattedFinalActionWait + ".");

        return PredictionResult.builder()
                .finalActionDate(finalActionDate)
                .filingDate(filingDate)
                .filingWaitMonths(filingWaitMonths)
                .finalActionWaitMonths(finalWaitMonths)
                .formattedFilingWait(formattedFilingWait)
                .formattedFinalActionWait(formattedFinalActionWait)
                .reasoningSteps(steps)
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

    /**
     * FIX (2026-09-26): "Final Action Unauthorized" and "Filing" are two
     * separate charts on the real Visa Bulletin (Chart A vs. Chart B) --
     * DOS can, and currently does for India EB2, mark Final Action
     * Unauthorized (the annual ceiling is hit, no more green cards can be
     * approved this FY) while STILL publishing a real Filing cutoff date, so
     * people can submit their I-485/DS-260 and get interim benefits (EAD/AP)
     * while waiting for final numbers. This method used to short-circuit
     * BOTH filing and final action into "N/A (Unauthorized)" the moment
     * final action was unauthorized -- meaning even a 2011 priority date
     * (15 years senior to India EB2's actual 2015-01-15 filing cutoff) got
     * told it couldn't file at all, which is simply wrong. Filing is now
     * always computed from its own cutoff/volume, independent of final
     * action's authorization status; only final action collapses to
     * "Unauthorized" when that's actually true.
     */
    private WaitEstimate estimateWait(Country chargeabilityCountry, EbCategory category, LocalDate applicantPD,
                                       Map<Country, Map<EbCategory, Double>> supplyMap) {
        double annualSupply = supplyMap.get(chargeabilityCountry).get(category);
        LocalDate filingCutOff = visaBulletinService.getFilingCutOff(chargeabilityCountry, category);

        long volumeAheadOfFiling = excelDataService.getInventoryBetween(chargeabilityCountry, category, filingCutOff, applicantPD);
        volumeAheadOfFiling += excelDataService.getI140Between(chargeabilityCountry, category, filingCutOff, applicantPD);

        long filingWaitMonths = annualSupply > 0 ? (long) ((volumeAheadOfFiling / annualSupply) * 12) : 300;

        if (visaBulletinService.isFinalActionUnauthorized(chargeabilityCountry, category)) {
            return new WaitEstimate(filingWaitMonths, 999, filingCutOff, null, volumeAheadOfFiling, true);
        }

        LocalDate finalActionCutOff = visaBulletinService.getFinalActionCutOff(chargeabilityCountry, category);
        long volumeAheadOfFinalAction = excelDataService.getInventoryBetween(chargeabilityCountry, category, finalActionCutOff, applicantPD);
        volumeAheadOfFinalAction += excelDataService.getI140Between(chargeabilityCountry, category, finalActionCutOff, applicantPD);

        long finalWaitMonths = annualSupply > 0 ? (long) ((volumeAheadOfFinalAction / annualSupply) * 12) : 600;

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

    private Map<Country, Map<EbCategory, Double>> calculateDynamicSupply(double baseAnnualEbLimit, double countryAnnualLimit, double fbToEbSpillover, boolean consularShutdown) {
        Map<Country, Map<EbCategory, Double>> supplyMap = new HashMap<>();
        Map<Country, Map<EbCategory, Long>> demandMap = new HashMap<>();

        System.out.println("[CALCULATION] Starting Dynamic EB Supply Calculation...");
        if (consularShutdown) {
            System.out.println("[CALCULATION] CONSULAR SHUTDOWN MODE ACTIVE (Simulating FY2020-2022 EB2 prioritization)");
        }
        System.out.println("[CALCULATION] Base EB Annual Limit: " + String.format("%.0f", baseAnnualEbLimit)
                + " (FB spillover " + String.format("%.0f", fbToEbSpillover) + " enters separately, only at EB-1)");

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
        //
        // FIX (2026-09-26): this used to give EVERY Country enum value its
        // own individual 7% allocation (only ROW was special-cased), instead
        // of just the five countries actually named on the bulletin's
        // per-country chart. With ~100 enum values each nominally claiming
        // 7% of the total, the "unused" remainder from ~95 near-zero-demand
        // countries got pooled and redistributed on top of ROW's already-
        // large 65% share -- inflating the redistribution pool to several
        // times the real global total, which could make a heavily-
        // backlogged country's priority date look "current" when in
        // reality it was years behind the bulletin's own cutoff date. Now
        // only the five individually-capped countries get their own 7%;
        // every other country gets zero base allocation of its own and can
        // only receive supply via horizontal redistribution from the
        // shared ROW/big-five pool, matching how "All Chargeability Areas
        // Except Those Listed" actually works on the real Visa Bulletin.
        for (Country c : Country.values()) {
            Map<EbCategory, Double> catSupply = new HashMap<>();
            double countryLimit;
            if (c == Country.ROW) {
                // FIX (2026-09-27, GAPS_AND_FIXES.md #16): countryAnnualLimit
                // is now the real published per-country cap (7% of family+EB
                // COMBINED, e.g. 29,136), not 7% of baseAnnualEbLimit alone
                // (which would be ~13,042) -- so ROW's residual must be
                // computed against the actual per-country cap being used for
                // the named five, not by re-deriving a percentage against a
                // different, smaller base. Using the old "baseAnnualEbLimit *
                // (1 - 5*7%)" formula here would double-count: the five named
                // countries already get the larger, real countryAnnualLimit
                // each, and re-subtracting only 7% of the (smaller) EB base
                // for each of them left ROW claiming far more than what
                // remained, pushing the sum of all base allocations above
                // the published worldwide total.
                countryLimit = Math.max(0,
                        baseAnnualEbLimit - (INDIVIDUALLY_CAPPED_COUNTRIES.size() * countryAnnualLimit));
            } else if (INDIVIDUALLY_CAPPED_COUNTRIES.contains(c)) {
                countryLimit = countryAnnualLimit;
            } else {
                countryLimit = 0;
            }

            for (EbCategory cat : EbCategory.values()) {
                double allocation = countryLimit * CATEGORY_ALLOCATION.get(cat);
                catSupply.put(cat, allocation);
            }
            double eb4Eb5Residual = countryLimit * EB4_EB5_RESIDUAL_SHARE;
            catSupply.put(EbCategory.EB1, catSupply.get(EbCategory.EB1) + eb4Eb5Residual);

            supplyMap.put(c, catSupply);
        }

        // 3+4. FIX (2026-09-26): per-category horizontal (cross-country)
        // redistribution now runs BEFORE that category's surplus cascades
        // vertically to the next category, not after. The old order ran the
        // full EB1->EB2->EB3 vertical waterfall for every country FIRST,
        // which meant every country's EB1 and EB2 surplus had ALREADY been
        // pushed down to EB2/EB3 by the time horizontal redistribution ever
        // looked at EB1 or EB2 -- so EB1's and EB2's cross-country pool was
        // mathematically guaranteed to be exactly zero, every single time
        // (verified: zero "Redistributing ... EB1/EB2" log lines ever
        // printed, for any country, in any run). Real INA 203(b)/9 FAM
        // 502.1-1(e) mechanics run the other way: unused numbers in a
        // category are first offered to OTHER oversubscribed countries in
        // that SAME category, and only what's still unused after that falls
        // through to the next category down. That's what this does now:
        // for each category in order, pool this category's own unused
        // supply (from countries whose demand doesn't use their base
        // allocation) PLUS whatever fell through from the previous
        // category, redistribute it horizontally among this category's
        // oversubscribed countries (capped at each one's own remaining
        // demand -- see the water-filling note below), and let only the
        // genuine leftover (pool bigger than total oversubscribed demand in
        // this category) cascade down to the next category.
        System.out.println("[CALCULATION] Processing category waterfall (horizontal-within-category, then vertical cascade)...");
        // FIX (2026-09-26): FB-to-EB spillover (INA 201(d)) enters "at the
        // top of the Employment-Based hierarchy" -- i.e. as poolable EB-1
        // capacity -- not blended into every category's base allocation
        // upfront (which is what happened before, when it inflated
        // baseAnnualEbLimit before the 28.6% splits ran). Seeding EB-1's
        // carry-in with it means it's redistributed horizontally among
        // oversubscribed EB-1 countries first, exactly like any other
        // unused EB-1 capacity, and only genuine EB-1 leftover cascades to
        // EB-2 -- matching the statute's actual entry point.
        double carryInFromPriorCategory = fbToEbSpillover;
        for (EbCategory cat : EbCategory.values()) {
            double pooledUnused = carryInFromPriorCategory;
            for (Country c : Country.values()) {
                double supply = supplyMap.get(c).get(cat);
                long demand = demandMap.get(c).get(cat);
                if (supply > demand) {
                    pooledUnused += (supply - demand);
                    supplyMap.get(c).put(cat, (double) demand);
                }
            }

            if (pooledUnused <= 0) {
                carryInFromPriorCategory = 0;
                continue;
            }

            // FIX (2026-09-26): redistribution used to split the pool
            // across oversubscribed countries by a custom 70/30 weight
            // (pre-2015 backlog / remaining demand). Per INA 202(a)(5),
            // "otherwise unused" numbers are made available to oversubscribed
            // countries strictly in priority-date order, without regard to
            // country of birth -- not by a country-level weighted share. So
            // this now builds ONE global queue of (country, priority-date
            // year) buckets across every oversubscribed country, sorted
            // oldest-year-first, and fills it in that order regardless of
            // which country each bucket belongs to.
            Map<Country, Long> remainingDemandByCountry = new HashMap<>();
            for (Country c : Country.values()) {
                double supply = supplyMap.get(c).get(cat);
                long demand = demandMap.get(c).get(cat);
                long remaining = demand - (long) supply;
                if (remaining > 0) {
                    remainingDemandByCountry.put(c, remaining);
                }
            }

            if (remainingDemandByCountry.isEmpty()) {
                System.out.println(String.format("[CALCULATION] No oversubscribed demand for %s. Pool of %.2f falls through to the next category.", cat, pooledUnused));
                carryInFromPriorCategory = pooledUnused;
                continue;
            }

            long totalRemainingDemand = remainingDemandByCountry.values().stream().mapToLong(Long::longValue).sum();
            double visasDistributed = Math.min(pooledUnused, totalRemainingDemand);

            List<PriorityBucket> queue = buildPriorityQueue(cat, remainingDemandByCountry);
            Map<Country, Double> allocated = new HashMap<>();
            double poolLeft = visasDistributed;
            for (PriorityBucket bucket : queue) {
                if (poolLeft <= 1e-9) break;
                double give = Math.min(poolLeft, bucket.count());
                allocated.merge(bucket.country(), give, Double::sum);
                poolLeft -= give;
            }
            // Every bucket's count already sums to exactly that country's
            // remaining demand (see buildPriorityQueue), so this can never
            // hand a country more than it actually needs -- the invariant
            // the old water-filling loop had to work to enforce falls out
            // for free here.

            for (Map.Entry<Country, Double> entry : allocated.entrySet()) {
                supplyMap.get(entry.getKey()).put(cat, supplyMap.get(entry.getKey()).get(cat) + entry.getValue());
                System.out.println(String.format("[CALCULATION] Redistributing %.2f unused %s visas to %s (priority-date order)", entry.getValue(), cat, entry.getKey()));
            }

            // Whatever of the pool couldn't be placed (every oversubscribed
            // country in this category is now fully satisfied, but pool
            // still has some left) cascades down to the next category, per
            // INA 203(b)'s EB1->EB2->EB3 fall-through order.
            double genuineLeftover = poolLeft;

            // Consular-shutdown mode (kept from the original design, not
            // something this pass was asked to change, just re-homed twice
            // now to fit first the horizontal-then-vertical reorder and now
            // priority-date-ordered redistribution): EB2 is mostly filed
            // domestically (AOS), while EB3 skews toward consular processing
            // abroad, which is what a consular shutdown actually disrupts.
            // So in that mode, only a fifth of EB2's genuine leftover falls
            // through to EB3 -- the rest is held back and handed to this
            // category's oversubscribed countries as bonus supply
            // (deliberately beyond their own remaining demand, mirroring the
            // original design's intentional over-allocation for this one
            // specific simulated scenario, now split by each country's share
            // of total remaining demand instead of the removed weight
            // formula), rather than assuming a shutdown affects EB2 and EB3
            // equally.
            if (consularShutdown && cat == EbCategory.EB2 && genuineLeftover > 0) {
                double heldBack = genuineLeftover * 0.8;
                genuineLeftover -= heldBack;
                for (Map.Entry<Country, Long> entry : remainingDemandByCountry.entrySet()) {
                    double bonus = heldBack * ((double) entry.getValue() / totalRemainingDemand);
                    supplyMap.get(entry.getKey()).put(EbCategory.EB2, supplyMap.get(entry.getKey()).get(EbCategory.EB2) + bonus);
                }
                System.out.println(String.format("[CALCULATION] Consular Shutdown - Holding %.2f of EB2's leftover pool back for EB2 instead of passing it to EB3", heldBack));
            }

            carryInFromPriorCategory = genuineLeftover;
        }

        for (Country c : Country.values()) {
            for (EbCategory cat : EbCategory.values()) {
                System.out.println(String.format("[CALCULATION] FINAL SUPPLY for %s %s: %.2f", c, cat, supplyMap.get(c).get(cat)));
            }
        }

        System.out.println("[CALCULATION] Dynamic supply calculation complete.");
        return supplyMap;
    }

    /**
     * One (country, priority-date year, count) unit in a category's global
     * priority-date-ordered redistribution queue -- see buildPriorityQueue().
     */
    private record PriorityBucket(Country country, int year, long count) {
    }

    /**
     * FIX (2026-09-26): builds each oversubscribed country's remaining
     * demand for this category, broken down by priority-date year (I-485
     * inventory + I-140 approvals), merged and sorted oldest-year-first
     * across ALL oversubscribed countries -- so redistribution can fill
     * strictly in priority-date order (INA 202(a)(5)) instead of by a
     * country-level weighted share.
     *
     * Each country's own base allocation is assumed to have already cleared
     * its OLDEST pending cases first (how visa numbers actually get used
     * against a backlog), so this trims that many units off the front
     * (oldest years) of that country's yearly breakdown before adding the
     * rest to the queue -- the remaining buckets across all years for a
     * country always sum to exactly `remainingDemandByCountry.get(country)`,
     * which is what guarantees this queue can never hand a country more
     * than its own real remaining demand.
     */
    private List<PriorityBucket> buildPriorityQueue(EbCategory cat, Map<Country, Long> remainingDemandByCountry) {
        List<PriorityBucket> buckets = new ArrayList<>();
        for (Map.Entry<Country, Long> entry : remainingDemandByCountry.entrySet()) {
            Country c = entry.getKey();
            long remaining = entry.getValue();

            Map<Integer, Long> yearly = new TreeMap<>(excelDataService.getInventoryYearly(c, cat));
            excelDataService.getI140Yearly(c, cat).forEach((year, count) -> yearly.merge(year, count, Long::sum));

            long totalYearly = yearly.values().stream().mapToLong(Long::longValue).sum();
            long alreadyCoveredByBase = Math.max(0, totalYearly - remaining);

            for (Map.Entry<Integer, Long> yearEntry : yearly.entrySet()) { // TreeMap: ascending by year
                long count = yearEntry.getValue();
                if (alreadyCoveredByBase >= count) {
                    alreadyCoveredByBase -= count;
                    continue;
                }
                long remainingInYear = count - alreadyCoveredByBase;
                alreadyCoveredByBase = 0;
                if (remainingInYear > 0) {
                    buckets.add(new PriorityBucket(c, yearEntry.getKey(), remainingInYear));
                }
            }
        }
        buckets.sort(Comparator.comparingInt(PriorityBucket::year).thenComparing(b -> b.country().name()));
        return buckets;
    }
}
