package org.innovativebrains.greencardpredictor.service;

import org.innovativebrains.greencardpredictor.model.Applicant;
import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.innovativebrains.greencardpredictor.model.PredictionResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Updated 2026-09-25/26 alongside the PredictionService/VisaBulletinService/Country fixes -- see
 * GAPS_AND_FIXES.md for the full write-up of what changed and why. Three categories of change
 * to this test file specifically:
 *
 *  1. testSpilloverFromRestrictedCountries and testAll75RestrictedCountriesAreRecognizedAndRestricted
 *     were pinning down the OLD, buggy behavior on purpose (the vacated 75-country restriction
 *     list, and a since-removed diagnostic field/explanation string) and have been rewritten to
 *     match the corrected behavior instead.
 *  2. testPredictForIndiaEB2, testPredictWithManualFbSpillover, and testConsularShutdownMode all
 *     exercised INDIA/EB2 specifically. The real September 2026 Visa Bulletin marks India EB2's
 *     Final Action as "Unauthorized" (the annual ceiling was already reached this fiscal year --
 *     see GAPS_AND_FIXES.md #4), which is now modeled and returns a distinct, early result. That's
 *     correct behavior, but it meant these three tests could no longer exercise what they were
 *     actually trying to test (ordinary bulletin-anchored math, the manual FB-spillover override,
 *     and consular-shutdown EB2 prioritization) via that specific country/category. They've been
 *     re-pointed at combinations that are NOT marked Unauthorized on the current bulletin, and a
 *     new dedicated test (testPredictForIndiaEB2Unauthorized) covers the Unauthorized case itself.
 *  3. testPredictForRestrictedCountry used Russia, which was restricted under the vacated 75-country
 *     policy but is NOT on the current Proclamation 10998 list -- re-pointed at Iran (which is),
 *     and the explanation substring check updated to match the new, dated explanation text.
 */
class PredictionServiceTest {
    private final ExcelDataService excelDataService = new ExcelDataService();
    private final VisaBulletinService visaBulletinService = new VisaBulletinService();
    private final PredictionService predictionService;

    public PredictionServiceTest() {
        excelDataService.init();
        this.predictionService = new PredictionService(excelDataService, visaBulletinService);
    }

    @Test
    void testPredictForIndiaEB3() {
        // Replaces the old testPredictForIndiaEB2 as the "ordinary bulletin-anchored
        // prediction" smoke test -- India EB2 itself is now Unauthorized on the current
        // bulletin (see testPredictForIndiaEB2Unauthorized below).
        Applicant applicant = new Applicant(Country.INDIA, EbCategory.EB3, LocalDate.of(2019, 12, 12));
        PredictionResult result = predictionService.predict(applicant);

        assertNotNull(result);
        System.out.println("[DEBUG_LOG] India EB3 Filing Prediction: " + result.getFilingWaitMonths() + " months wait (" + result.getFormattedFilingWait() + ").");
        System.out.println("[DEBUG_LOG] India EB3 Final Action Prediction: " + result.getFinalActionWaitMonths() + " months wait (" + result.getFormattedFinalActionWait() + ").");
        System.out.println("[DEBUG_LOG] Filing Date: " + result.getFilingDate());
        System.out.println("[DEBUG_LOG] Final Action Date: " + result.getFinalActionDate());
        System.out.println("[DEBUG_LOG] Explanation: " + result.getExplanation());

        assertTrue(result.getFilingWaitMonths() >= 0);
        assertTrue(result.getFinalActionWaitMonths() > result.getFilingWaitMonths(), "Final Action wait should be greater than Filing wait for India EB3");
        assertNotNull(result.getFinalActionDate());
        assertNotNull(result.getFilingDate());
        assertTrue(result.getExplanation().contains("Filing Cut-off"));
        assertTrue(result.getExplanation().contains("Bulletin-Anchored Prediction"));
    }

    @Test
    void testPredictForIndiaEB2Unauthorized() {
        // The September 2026 Visa Bulletin marks India EB2 Final Action as "U"
        // (Unauthorized): the annual per-country/category ceiling was already reached
        // ahead of the September 30 fiscal year end. See VisaBulletinService and
        // GAPS_AND_FIXES.md #4. This used to silently fall back to a wide-open
        // 2010-01-01 cutoff (the opposite of correct); now it's a distinct, explained
        // outcome.
        Applicant applicant = new Applicant(Country.INDIA, EbCategory.EB2, LocalDate.of(2019, 12, 12));
        PredictionResult result = predictionService.predict(applicant);

        assertNotNull(result);
        assertFalse(result.isRestricted(), "Unauthorized-this-FY is a different state from country-restricted");
        assertEquals("N/A (Unauthorized this FY)", result.getFormattedFilingWait());
        assertEquals("N/A (Unauthorized this FY)", result.getFormattedFinalActionWait());
        assertEquals(999, result.getFilingWaitMonths());
        assertEquals(999, result.getFinalActionWaitMonths());
        assertTrue(result.getExplanation().contains("Unauthorized"));
        System.out.println("[DEBUG_LOG] India EB2 (Unauthorized) Explanation: " + result.getExplanation());
    }

    @Test
    void testPredictWithManualFbSpillover() {
        // India EB2 -> India EB3: India EB2 is Unauthorized on the current bulletin (see
        // testPredictForIndiaEB2Unauthorized), which would short-circuit before this
        // test's manual-spillover comparison ever mattered. EB3 exercises the same
        // FB-to-EB spillover -> total EB limit -> per-category supply chain.
        LocalDate pd = LocalDate.of(2019, 12, 19);

        // Scenario 1: No spillover (Low supply)
        Applicant lowSpilloverApplicant = new Applicant(Country.INDIA, EbCategory.EB3, pd);
        lowSpilloverApplicant.setManualFbSpillover(0.0);
        PredictionResult lowResult = predictionService.predict(lowSpilloverApplicant);

        // Scenario 2: High spillover (High supply)
        Applicant highSpilloverApplicant = new Applicant(Country.INDIA, EbCategory.EB3, pd);
        highSpilloverApplicant.setManualFbSpillover(100000.0); // Extra 100k visas
        PredictionResult highResult = predictionService.predict(highSpilloverApplicant);

        System.out.println("[DEBUG_LOG] Low Spillover (0) Final Wait: " + lowResult.getFormattedFinalActionWait());
        System.out.println("[DEBUG_LOG] High Spillover (100k) Final Wait: " + highResult.getFormattedFinalActionWait());

        assertTrue(highResult.getFinalActionWaitMonths() < lowResult.getFinalActionWaitMonths(),
            "Wait time should decrease when family-based spillover increases");

        System.out.println("[DEBUG_LOG] Difference in months: " + (lowResult.getFinalActionWaitMonths() - highResult.getFinalActionWaitMonths()));
    }

    @Test
    void testPredictForChinaEB1() {
        Applicant applicant = new Applicant(Country.CHINA, EbCategory.EB1, LocalDate.of(2022, 1, 1));
        PredictionResult result = predictionService.predict(applicant);

        assertNotNull(result);
        assertTrue(result.getFinalActionWaitMonths() >= 0);
        System.out.println("[DEBUG_LOG] China EB1 Prediction: " + result.getFinalActionWaitMonths() + " months wait.");
        System.out.println("[DEBUG_LOG] Explanation: " + result.getExplanation());
    }

    @Test
    void testPredictForRestrictedCountry() {
        // Iran replaces Russia here: Russia was restricted under the Jan 2026 75-country
        // "public charge" pause, which was vacated by court order on Aug 21, 2026, and
        // Russia is NOT on the current Proclamation 10998 travel-ban list (see
        // GAPS_AND_FIXES.md #3) -- so Russia is no longer restricted, correctly. Iran is
        // on both the old and current lists, so it's a stable choice for this test.
        Applicant applicant = new Applicant(Country.IRAN, EbCategory.EB2, LocalDate.of(2022, 1, 1));
        PredictionResult result = predictionService.predict(applicant);

        assertNotNull(result);
        assertTrue(result.isRestricted(), "Iran should be marked as restricted");
        assertEquals("N/A (Restricted)", result.getFormattedFilingWait());
        assertTrue(result.getExplanation().contains("Presidential Proclamation 10998"));
        System.out.println("[DEBUG_LOG] Restricted Country Prediction Result: " + result.getExplanation());
    }

    @Test
    void testUnusedVisasFromRestrictedCountriesRedistribute() {
        // Replaces testSpilloverFromRestrictedCountries, which asserted a diagnostic
        // explanation substring ("significant supply spillover from banned countries")
        // and a getBannedCountrySpilloverToIndia() field that were both tied to the old,
        // India-specific redistribution bookkeeping and have been removed as part of the
        // fix (see GAPS_AND_FIXES.md, "How this review was done"). The underlying
        // mechanism this was meant to verify still exists: restricted countries have
        // their demand zeroed out in calculateDynamicSupply, so their share of the annual
        // EB limit is never "consumed" locally and gets pooled and redistributed to
        // oversubscribed countries in the same category (step 4 of
        // calculateDynamicSupply). calculateDynamicSupply is private, so -- like the
        // original test -- this verifies the effect end-to-end rather than in isolation:
        // a heavily backlogged, non-restricted country/category should see a real,
        // finite annual supply (not the "no supply" fallback), which requires that
        // redistribution to have actually happened given how few countries are
        // oversubscribed relative to the whole EB pool.
        Applicant applicant = new Applicant(Country.INDIA, EbCategory.EB3, LocalDate.of(2013, 12, 2));
        PredictionResult result = predictionService.predict(applicant);

        assertNotNull(result);
        assertFalse(result.isRestricted());
        assertTrue(result.getExplanation().contains("Bulletin-Anchored Prediction"));
        assertTrue(result.getFinalActionWaitMonths() < 600, "A real (redistributed) supply should avoid the zero-supply fallback wait");
        assertTrue(result.getFilingWaitMonths() < 300, "A real (redistributed) supply should avoid the zero-supply fallback wait");
        System.out.println("[DEBUG_LOG] India EB3 Explanation with redistribution active: " + result.getExplanation());
    }

    @Test
    void testConsularShutdownMode() {
        // China EB2 replaces India EB2 here: India EB2 is Unauthorized on the current
        // bulletin (see testPredictForIndiaEB2Unauthorized), which returns early before
        // the consular-shutdown-specific supply logic ever runs -- correct real-world
        // behavior, but it means India EB2 can no longer exercise this mode. China EB2 is
        // not Unauthorized and (with a priority date past its current cutoffs) has a real
        // backlog, so the EB2-prioritization effect is actually exercised.
        LocalDate pd = LocalDate.of(2023, 6, 1);

        // Scenario 1: Standard Mode
        Applicant standardApplicant = new Applicant(Country.CHINA, EbCategory.EB2, pd);
        PredictionResult standardResult = predictionService.predict(standardApplicant);

        // Scenario 2: Consular Shutdown Mode
        Applicant shutdownApplicant = new Applicant(Country.CHINA, EbCategory.EB2, pd);
        shutdownApplicant.setConsularShutdown(true);
        PredictionResult shutdownResult = predictionService.predict(shutdownApplicant);

        System.out.println("[DEBUG_LOG] Standard China EB2 Wait: " + standardResult.getFormattedFinalActionWait());
        System.out.println("[DEBUG_LOG] Consular Shutdown China EB2 Wait: " + shutdownResult.getFormattedFinalActionWait());
        System.out.println("[DEBUG_LOG] Standard Explanation: " + standardResult.getExplanation());
        System.out.println("[DEBUG_LOG] Shutdown Explanation: " + shutdownResult.getExplanation());

        assertTrue(shutdownResult.getFinalActionWaitMonths() <= standardResult.getFinalActionWaitMonths(),
            "Wait time should decrease or stay same when EB2 is prioritized during consular shutdown");
        assertTrue(shutdownResult.getExplanation().contains("CONSULAR SHUTDOWN MODE ACTIVE"));
    }

    @Test
    void testProclamation10998CountriesAreRestricted() {
        // Replaces testAll75RestrictedCountriesAreRecognizedAndRestricted, which pinned
        // down the vacated Jan 2026 75-country "public charge" list. The currently
        // operative restriction is Presidential Proclamation 10998 (eff. Jan 1, 2026):
        // 19 full-suspension countries + Palestinian Authority travel document holders,
        // 19 partial-suspension countries (immigrant visas included), and Turkmenistan
        // (immigrant visas only) -- 40 restricted entries total. See Country.java and
        // GAPS_AND_FIXES.md #3.
        Country[] restricted = {
            // Full suspension (19 + Palestinian Authority)
            Country.AFGHANISTAN, Country.MYANMAR, Country.BURKINA_FASO, Country.CHAD,
            Country.REPUBLIC_OF_CONGO, Country.EQUATORIAL_GUINEA, Country.ERITREA, Country.HAITI,
            Country.IRAN, Country.LAOS, Country.LIBYA, Country.MALI, Country.NIGER,
            Country.SIERRA_LEONE, Country.SOMALIA, Country.SOUTH_SUDAN, Country.SUDAN,
            Country.SYRIA, Country.YEMEN, Country.PALESTINIAN_AUTHORITY,
            // Partial suspension (19) -- immigrant visas included
            Country.ANGOLA, Country.ANTIGUA_AND_BARBUDA, Country.BENIN, Country.BURUNDI,
            Country.IVORY_COAST, Country.CUBA, Country.DOMINICA, Country.GABON, Country.GAMBIA,
            Country.MALAWI, Country.MAURITANIA, Country.NIGERIA, Country.SENEGAL, Country.TANZANIA,
            Country.TOGO, Country.TONGA, Country.VENEZUELA, Country.ZAMBIA, Country.ZIMBABWE,
            // Single-category
            Country.TURKMENISTAN
        };

        assertEquals(40, restricted.length, "Must contain exactly 40 restricted entries (39 countries + Palestinian Authority)");

        for (Country country : restricted) {
            assertTrue(country.isRestricted(), country + " should be marked as restricted under Proclamation 10998");

            Applicant applicant = new Applicant(country, EbCategory.EB2, LocalDate.of(2022, 1, 1));
            PredictionResult result = predictionService.predict(applicant);
            assertTrue(result.isRestricted(), country + " prediction should be restricted");
            assertEquals("N/A (Restricted)", result.getFormattedFilingWait());
            assertEquals(999, result.getFilingWaitMonths());
            assertEquals(999, result.getFinalActionWaitMonths());
            assertTrue(result.getExplanation().contains("Presidential Proclamation 10998"),
                country + " explanation should cite the current legal basis");
        }
    }

    @Test
    void testCountriesRemovedFromVacatedPublicChargeListAreNoLongerRestricted() {
        // These were restricted under the Jan 2026 75-country "public charge" pause,
        // vacated by court order on Aug 21, 2026. None are on the current Proclamation
        // 10998 travel-ban list, so treating them as restricted today would mean citing a
        // dead policy -- exactly the bug fixed in GAPS_AND_FIXES.md #3. Brazil is called
        // out specifically there as a country PREDICTION_STRATEGY.md always described as
        // a normal "big 5" 7%-cap country, which the old Country.java contradicted.
        Country[] noLongerRestricted = {
            Country.RUSSIA, Country.BRAZIL, Country.PAKISTAN, Country.EGYPT, Country.COLOMBIA,
            Country.URUGUAY, Country.THAILAND, Country.JORDAN, Country.KUWAIT
        };
        for (Country country : noLongerRestricted) {
            assertFalse(country.isRestricted(),
                country + " should NOT be restricted (was only under the vacated Jan 2026 policy, not on the current travel-ban list)");
        }
    }

    @Test
    void testCountryFromStringAliasesAndEdgeCases() {
        assertEquals(Country.DEMOCRATIC_REPUBLIC_OF_CONGO, Country.fromString("Democratic Republic of the Congo"));
        assertEquals(Country.DEMOCRATIC_REPUBLIC_OF_CONGO, Country.fromString("DRC"));
        assertEquals(Country.REPUBLIC_OF_CONGO, Country.fromString("Republic of the Congo"));
        assertEquals(Country.REPUBLIC_OF_CONGO, Country.fromString("Republic of Congo"));
        assertEquals(Country.SOUTH_SUDAN, Country.fromString("South Sudan"));
        assertEquals(Country.SUDAN, Country.fromString("Sudan"));
        assertEquals(Country.MYANMAR, Country.fromString("Burma"));
        assertEquals(Country.IVORY_COAST, Country.fromString("Cote d'Ivoire"));
        assertEquals(Country.CAPE_VERDE, Country.fromString("Cabo Verde"));
        assertEquals(Country.KYRGYZSTAN, Country.fromString("Kyrgyz Republic"));
        assertEquals(Country.SAINT_KITTS_AND_NEVIS, Country.fromString("St. Kitts and Nevis"));
        assertEquals(Country.SAINT_LUCIA, Country.fromString("St. Lucia"));
        assertEquals(Country.SAINT_VINCENT_AND_THE_GRENADINES, Country.fromString("St. Vincent and the Grenadines"));
        assertEquals(Country.GAMBIA, Country.fromString("The Gambia"));
        assertEquals(Country.BAHAMAS, Country.fromString("The Bahamas"));
        assertEquals(Country.RUSSIA, Country.fromString("Russian Federation"));
        assertEquals(Country.NIGERIA, Country.fromString("Nigeria FY25"));
        assertEquals(Country.DOMINICA, Country.fromString("Dominica"));
        assertEquals(Country.ROW, Country.fromString("Dominican Republic"));
        assertEquals(Country.GUINEA, Country.fromString("Guinea"));
        // FIX (2026-09-25): Equatorial Guinea used to silently fall through to ROW (it
        // wasn't in the enum at all), which meant any Excel row for it was folded into
        // the generic Rest-of-World bucket instead of tracked separately -- and it's one
        // of the 19 full-suspension countries under Proclamation 10998, so lumping it
        // into ROW would also have hidden that it's restricted. It now resolves to its
        // own enum value.
        assertEquals(Country.EQUATORIAL_GUINEA, Country.fromString("Equatorial Guinea"));
        assertEquals(Country.ROW, Country.fromString("Guinea-Bissau"));
    }

    @Test
    void testStandardCountriesAreNotRestricted() {
        assertFalse(Country.INDIA.isRestricted(), "India should not be restricted");
        assertFalse(Country.CHINA.isRestricted(), "China should not be restricted");
        assertFalse(Country.PHILIPPINES.isRestricted(), "Philippines should not be restricted");
        assertFalse(Country.MEXICO.isRestricted(), "Mexico should not be restricted");
        assertFalse(Country.BRAZIL.isRestricted(), "Brazil should not be restricted (see GAPS_AND_FIXES.md #3)");
        assertFalse(Country.ROW.isRestricted(), "ROW should not be restricted");
    }
}
