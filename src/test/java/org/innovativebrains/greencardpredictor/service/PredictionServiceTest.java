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

class PredictionServiceTest {
    private final ExcelDataService excelDataService = new ExcelDataService();
    private final VisaBulletinService visaBulletinService = new VisaBulletinService();
    private final PredictionService predictionService;

    public PredictionServiceTest() {
        excelDataService.init();
        this.predictionService = new PredictionService(excelDataService, visaBulletinService);
    }

    @Test
    void testPredictForIndiaEB2() {
        Applicant applicant = new Applicant(Country.INDIA, EbCategory.EB2, LocalDate.of(2019, 12, 12));
        PredictionResult result = predictionService.predict(applicant);
        
        assertNotNull(result);
        System.out.println("[DEBUG_LOG] India EB2 Filing Prediction: " + result.getFilingWaitMonths() + " months wait (" + result.getFormattedFilingWait() + ").");
        System.out.println("[DEBUG_LOG] India EB2 Final Action Prediction: " + result.getFinalActionWaitMonths() + " months wait (" + result.getFormattedFinalActionWait() + ").");
        System.out.println("[DEBUG_LOG] Filing Date: " + result.getFilingDate());
        System.out.println("[DEBUG_LOG] Final Action Date: " + result.getFinalActionDate());
        System.out.println("[DEBUG_LOG] Explanation: " + result.getExplanation());
        
        assertTrue(result.getFilingWaitMonths() >= 0);
        assertTrue(result.getFinalActionWaitMonths() > result.getFilingWaitMonths(), "Final Action wait should be greater than Filing wait for India EB2");
        assertNotNull(result.getFinalActionDate());
        assertNotNull(result.getFilingDate());
        System.out.println("[DEBUG_LOG] EXPLANATION TO CHECK: " + result.getExplanation());
        assertTrue(result.getExplanation().contains("Filing Cut-off"));
        assertTrue(result.getExplanation().contains("Bulletin-Anchored Prediction"));
    }

    @Test
    void testPredictWithManualFbSpillover() {
        LocalDate pd = LocalDate.of(2019, 12, 19);
        
        // Scenario 1: No spillover (Low supply)
        Applicant lowSpilloverApplicant = new Applicant(Country.INDIA, EbCategory.EB2, pd);
        lowSpilloverApplicant.setManualFbSpillover(0.0);
        PredictionResult lowResult = predictionService.predict(lowSpilloverApplicant);
        
        // Scenario 2: High spillover (High supply)
        Applicant highSpilloverApplicant = new Applicant(Country.INDIA, EbCategory.EB2, pd);
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
        Applicant applicant = new Applicant(Country.RUSSIA, EbCategory.EB2, LocalDate.of(2022, 1, 1));
        PredictionResult result = predictionService.predict(applicant);

        assertNotNull(result);
        assertTrue(result.isRestricted(), "Russia should be marked as restricted");
        assertEquals("N/A (Restricted)", result.getFormattedFilingWait());
        assertTrue(result.getExplanation().contains("suspended due to recent executive actions"));
        System.out.println("[DEBUG_LOG] Restricted Country Prediction Result: " + result.getExplanation());
    }

    @Test
    void testSpilloverFromRestrictedCountries() {
        // We compare India EB2 wait time with and without "restrictions" 
        // Although the restriction list is currently hardcoded in PredictionService.
        
        // Since many countries like Russia, Iran, etc. are now restricted, 
        // their allocated visas should pool and help India/China.
        
        Applicant applicant = new Applicant(Country.INDIA, EbCategory.EB2, LocalDate.of(2013, 12, 02));
        PredictionResult result = predictionService.predict(applicant);
        
        System.out.println("[DEBUG_LOG] India EB2 Explanation with restricted spillovers: " + result.getExplanation());
        assertTrue(result.getExplanation().contains("significant supply spillover from banned countries"));
        assertNotNull(result.getBannedCountrySpilloverToIndia());
        
        // This test mostly verifies the logic is active and the explanation is present.
        // Hard to compare "before/after" easily without mocking the restriction list,
        // but the code change in calculateDynamicSupply ensures restricted countries have 0 demand,
        // so their 7% supply WILL be pooled in step 4 of calculateDynamicSupply.
    }

    @Test
    void testConsularShutdownMode() {
        LocalDate pd = LocalDate.of(2019, 12, 19);
        
        // Scenario 1: Standard Mode
        Applicant standardApplicant = new Applicant(Country.INDIA, EbCategory.EB2, pd);
        PredictionResult standardResult = predictionService.predict(standardApplicant);
        
        // Scenario 2: Consular Shutdown Mode
        Applicant shutdownApplicant = new Applicant(Country.INDIA, EbCategory.EB2, pd);
        shutdownApplicant.setConsularShutdown(true);
        PredictionResult shutdownResult = predictionService.predict(shutdownApplicant);
        
        System.out.println("[DEBUG_LOG] Standard India EB2 Wait: " + standardResult.getFormattedFinalActionWait());
        System.out.println("[DEBUG_LOG] Consular Shutdown India EB2 Wait: " + shutdownResult.getFormattedFinalActionWait());
        System.out.println("[DEBUG_LOG] Standard Explanation: " + standardResult.getExplanation());
        System.out.println("[DEBUG_LOG] Shutdown Explanation: " + shutdownResult.getExplanation());

        assertTrue(shutdownResult.getFinalActionWaitMonths() <= standardResult.getFinalActionWaitMonths(), 
            "Wait time should decrease or stay same when EB2 is prioritized during consular shutdown");
        assertTrue(shutdownResult.getExplanation().contains("CONSULAR SHUTDOWN MODE ACTIVE"));
    }

    @Test
    void testAll75RestrictedCountriesAreRecognizedAndRestricted() {
        String[] restrictedList = {
            "Afghanistan", "Albania", "Algeria", "Antigua and Barbuda", "Armenia",
            "Azerbaijan", "Bahamas", "Bangladesh", "Barbados", "Belarus", "Belize",
            "Bhutan", "Bosnia and Herzegovina", "Brazil", "Cambodia", "Cameroon",
            "Cape Verde", "Colombia", "Cuba", "Democratic Republic of Congo",
            "Dominica", "Egypt", "Eritrea", "Ethiopia", "Fiji", "Gambia", "Georgia",
            "Ghana", "Grenada", "Guatemala", "Guinea", "Haiti", "Iran", "Iraq",
            "Ivory Coast", "Jamaica", "Jordan", "Kazakhstan", "Kosovo", "Kuwait",
            "Kyrgyzstan", "Laos", "Lebanon", "Liberia", "Libya", "Moldova",
            "Mongolia", "Montenegro", "Morocco", "Myanmar", "Nepal", "Nicaragua",
            "Nigeria", "North Macedonia", "Pakistan", "Republic of Congo", "Russia",
            "Rwanda", "Saint Kitts and Nevis", "Saint Lucia",
            "Saint Vincent and the Grenadines", "Senegal", "Sierra Leone", "Somalia",
            "South Sudan", "Sudan", "Syria", "Tanzania", "Thailand", "Togo",
            "Tunisia", "Uganda", "Uruguay", "Uzbekistan", "Yemen"
        };

        assertEquals(75, restrictedList.length, "Must contain exactly 75 countries");

        for (String countryName : restrictedList) {
            Country country = Country.fromString(countryName);
            assertNotEquals(Country.ROW, country, "Country should not resolve to ROW: " + countryName);
            assertTrue(country.isRestricted(), countryName + " should be marked as restricted");

            Applicant applicant = new Applicant(country, EbCategory.EB2, LocalDate.of(2022, 1, 1));
            PredictionResult result = predictionService.predict(applicant);
            assertTrue(result.isRestricted(), countryName + " prediction should be restricted");
            assertEquals("N/A (Restricted)", result.getFormattedFilingWait());
            assertEquals(999, result.getFilingWaitMonths());
            assertEquals(999, result.getFinalActionWaitMonths());
            assertTrue(result.getExplanation().contains("suspended due to recent executive actions"));
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
        assertEquals(Country.ROW, Country.fromString("Equatorial Guinea"));
        assertEquals(Country.ROW, Country.fromString("Guinea-Bissau"));
    }

    @Test
    void testStandardCountriesAreNotRestricted() {
        assertFalse(Country.INDIA.isRestricted(), "India should not be restricted");
        assertFalse(Country.CHINA.isRestricted(), "China should not be restricted");
        assertFalse(Country.PHILIPPINES.isRestricted(), "Philippines should not be restricted");
        assertFalse(Country.MEXICO.isRestricted(), "Mexico should not be restricted");
        assertFalse(Country.ROW.isRestricted(), "ROW should not be restricted");
    }
}
