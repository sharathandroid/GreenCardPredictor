package org.innovativebrains.greencardpredictor.service;

import org.innovativebrains.greencardpredictor.model.Applicant;
import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.innovativebrains.greencardpredictor.model.PredictionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PredictionService {

    private final ExcelDataService excelDataService;
    private final VisaBulletinService visaBulletinService;

    public PredictionService(ExcelDataService excelDataService, VisaBulletinService visaBulletinService) {
        this.excelDataService = excelDataService;
        this.visaBulletinService = visaBulletinService;
    }

    // Total Employment-Based (EB) annual limit is approx 140,000.
    private static final double BASE_ANNUAL_EB_LIMIT = 140000;
    
    // Per-country cap is 7% of the total EB limit.
    private static final double COUNTRY_CAP_PERCENTAGE = 0.07;

    // Allocation percentages for each category (simplified):
    // EB1: 28.6%
    // EB2: 28.6%
    // EB3: 28.6%
    // Other (EB4, EB5): Remaining
    private static final Map<EbCategory, Double> CATEGORY_ALLOCATION = new HashMap<>();
    static {
        CATEGORY_ALLOCATION.put(EbCategory.EB1, 0.286);
        CATEGORY_ALLOCATION.put(EbCategory.EB2, 0.286);
        CATEGORY_ALLOCATION.put(EbCategory.EB3, 0.286);
    }

    public PredictionResult predict(Applicant applicant) {
        Country country = applicant.getCountry();
        EbCategory category = applicant.getCategory();
        
        // Handle restricted/banned countries
        if (isRestricted(country)) {
            return PredictionResult.builder()
                    .restricted(true)
                    .explanation(String.format("Currently, issuance of Green Cards for individuals from %s is suspended due to recent executive actions. Predicting wait times is not possible under current policy.", country))
                    .formattedFilingWait("N/A (Restricted)")
                    .formattedFinalActionWait("N/A (Restricted)")
                    .finalActionWaitMonths(999)
                    .filingWaitMonths(999)
                    .build();
        }

        // Dynamic FB-to-EB spillover logic based on USCIS data
        // Law: Unused FB visas from prior year spill to EB. Total FB limit is ~226k.
        double fbToEbSpillover;
        if (applicant.getManualFbSpillover() != null) {
            fbToEbSpillover = applicant.getManualFbSpillover();
        } else {
            long i130Pending = excelDataService.getI130PendingCount();
            fbToEbSpillover = Math.max(0, 226000 - i130Pending);
        }
        
        double totalAnnualEbLimit = BASE_ANNUAL_EB_LIMIT + fbToEbSpillover;
        double countryAnnualLimit = totalAnnualEbLimit * COUNTRY_CAP_PERCENTAGE;

        // 1. Calculate actual supply for all countries/categories by resolving spillovers
        // We'll calculate India-specific spillover for the category here since calculateDynamicSupply currently only returns the final supply map
        Map<Country, Map<EbCategory, Double>> supplyMap = calculateDynamicSupply(totalAnnualEbLimit, countryAnnualLimit, applicant.isConsularShutdown());
        
        double annualSupply = supplyMap.get(country).get(category);
        long inventoryCount = excelDataService.getInventoryCount(country, category);
        long i140Count = excelDataService.getI140Count(country, category);
        
        // Let's redo a small part of the calculation here to get the "India Banned Spillover" for the applicant's category
        // In a real system, we might want calculateDynamicSupply to return a more detailed object.
        double indiaBannedSpillover = 0;
        if (country == Country.INDIA) {
            // This is a bit redundant but ensures we get the exact number shown in the logs
            // Base supply for India is countryAnnualLimit * CATEGORY_ALLOCATION.get(category)
            double baseIndiaSupply = (country == Country.ROW) ? totalAnnualEbLimit * (1 - (5 * COUNTRY_CAP_PERCENTAGE)) : countryAnnualLimit;
            double initialIndiaCatSupply = baseIndiaSupply * CATEGORY_ALLOCATION.get(category);
            indiaBannedSpillover = annualSupply - initialIndiaCatSupply;
            // Note: This includes ALL spillover (vertical from India EB1/EB2 + global from ROW/Banned).
            // To be more precise, we'd need the share from calculateDynamicSupply.
            // Since we added the log there, we'll keep it simple here.
        }

        // 1. Anchor to Official Visa Bulletin Dates
        LocalDate filingCutOff = visaBulletinService.getFilingCutOff(country, category);
        LocalDate finalActionCutOff = visaBulletinService.getFinalActionCutOff(country, category);
        
        // 2. Calculate Volume specifically AHEAD of Applicant starting from Bulletin Date
        LocalDate applicantPD = applicant.getPriorityDate();
        
        // Volume for Filing Date Calculation
        long volumeAheadOfFiling = excelDataService.getInventoryBetween(country, category, filingCutOff, applicantPD);
        volumeAheadOfFiling += excelDataService.getI140Between(country, category, filingCutOff, applicantPD);
        
        // Volume for Final Action Calculation
        long volumeAheadOfFinalAction = excelDataService.getInventoryBetween(country, category, finalActionCutOff, applicantPD);
        volumeAheadOfFinalAction += excelDataService.getI140Between(country, category, finalActionCutOff, applicantPD);
        
        long finalWaitMonths;
        long filingWaitMonths;

        if (annualSupply > 0) {
            // Filing Date: How long to clear BOTH Inventory and I-140 gap between the Bulletin Filing Date and Applicant
            filingWaitMonths = (long) ((volumeAheadOfFiling / annualSupply) * 12);
            
            // Final Action: How long to clear BOTH Inventory and I-140 gap between the Bulletin Final Action Date and Applicant
            finalWaitMonths = (long) ((volumeAheadOfFinalAction / annualSupply) * 12);
        } else {
            finalWaitMonths = 600;
            filingWaitMonths = 300;
        }

        // Adjustments: Ensure Filing Date is reachable
        LocalDate filingDate = LocalDate.now().plusMonths(filingWaitMonths);
        LocalDate finalActionDate = LocalDate.now().plusMonths(finalWaitMonths);

        // Heuristic consistency: Filing date should not be after Final Action
        if (filingDate.isAfter(finalActionDate)) {
            filingDate = finalActionDate;
            filingWaitMonths = finalWaitMonths;
        }

        // Resulting Prediction result with adjusted dates
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
                    "Bulletin-Anchored Prediction. Filing Cut-off: %s, Final Action Cut-off: %s. " +
                    "Annual supply: ~%.0f. Inventory + I-140 Ahead of Filing PD: %d. Priority Date: %s. " +
                    "Note: For India, current model accounts for significant supply spillover from banned countries.%s",
                    filingCutOff, finalActionCutOff, annualSupply, volumeAheadOfFiling, applicantPD, shutdownNote))
                .bannedCountrySpilloverToIndia(indiaBannedSpillover > 0 ? indiaBannedSpillover : 0.0)
                .build();
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
        Map<EbCategory, Double> bannedPoolPerCategory = new HashMap<>();
        for (EbCategory cat : EbCategory.values()) bannedPoolPerCategory.put(cat, 0.0);

        System.out.println("[CALCULATION] Starting Dynamic EB Supply Calculation...");
        if (consularShutdown) {
            System.out.println("[CALCULATION] CONSULAR SHUTDOWN MODE ACTIVE (Simulating FY2020-2022 EB2 prioritization)");
        }
        System.out.println("[CALCULATION] Total EB Annual Limit (including FB spillover): " + String.format("%.0f", totalAnnualEbLimit));

        // 1. Initialize Demand (Backlog)
        for (Country c : Country.values()) {
            demandMap.put(c, new HashMap<>());
            boolean restricted = isRestricted(c);
            for (EbCategory cat : EbCategory.values()) {
                // If country is restricted, its demand is effectively 0 for the purpose of visa allocation
                // because no visas can be issued to its nationals.
                long backlog = excelDataService.getBacklog(c, cat);
                demandMap.get(c).put(cat, restricted ? 0L : backlog);
            }
        }

        // 2. Initialize Base Supply (7% rule)
        double totalBannedSupplyRedistributed = 0;
        for (Country c : Country.values()) {
            Map<EbCategory, Double> catSupply = new HashMap<>();
            double countryLimit = (c == Country.ROW) ? totalAnnualEbLimit * (1 - (5 * COUNTRY_CAP_PERCENTAGE)) : countryAnnualLimit;
            // ROW gets what's left after India, China, Philippines, Mexico, Brazil (each 7%)
            
            boolean restricted = isRestricted(c);
            for (EbCategory cat : EbCategory.values()) {
                double allocation = countryLimit * CATEGORY_ALLOCATION.get(cat);
                catSupply.put(cat, allocation);
                if (restricted) {
                    totalBannedSupplyRedistributed += allocation;
                    bannedPoolPerCategory.put(cat, bannedPoolPerCategory.get(cat) + allocation);
                }
            }
            supplyMap.put(c, catSupply);
        }
        System.out.println("[CALCULATION] TOTAL visas coming to EB from Banned Countries (Base Allocation): " + String.format("%.0f", totalBannedSupplyRedistributed));

        // 3. Vertical Spillover (EB1 -> EB2 -> EB3) - Country by Country
        System.out.println("[CALCULATION] Processing Vertical Spillover (EB1 -> EB2 -> EB3)...");
        for (Country c : Country.values()) {
            boolean restricted = isRestricted(c);
            
            // If consular shutdown is active, non-restricted countries with low demand 
            // (like Mexico/ROW/Philippines in certain categories) might have their 
            // consular processing EB visas spill over to AOS-heavy categories like EB2.
            // In our simple model, we simulate this by ensuring EB1 and other surpluses 
            // are pooled and redirected.
            
            // EB1 -> EB2
            double eb1Supply = supplyMap.get(c).get(EbCategory.EB1);
            long eb1Demand = demandMap.get(c).get(EbCategory.EB1);
            if (eb1Supply > eb1Demand) {
                double unused = eb1Supply - eb1Demand;
                supplyMap.get(c).put(EbCategory.EB2, supplyMap.get(c).get(EbCategory.EB2) + unused);
                supplyMap.get(c).put(EbCategory.EB1, (double) eb1Demand);
                if (restricted) {
                    // Visas from EB1 that were already in banned pool move to EB2 in the pool
                    bannedPoolPerCategory.put(EbCategory.EB1, bannedPoolPerCategory.get(EbCategory.EB1) - unused);
                    bannedPoolPerCategory.put(EbCategory.EB2, bannedPoolPerCategory.get(EbCategory.EB2) + unused);
                }
                System.out.println(String.format("[CALCULATION] %s: Vertical Spillover EB1 -> EB2: %.2f", c, unused));
            }

            // EB2 -> EB3
            double eb2Supply = supplyMap.get(c).get(EbCategory.EB2);
            long eb2Demand = demandMap.get(c).get(EbCategory.EB2);
            if (eb2Supply > eb2Demand) {
                double unused = eb2Supply - eb2Demand;
                
                // If consular shutdown is active, we significantly REDUCE the spillover from EB2 to EB3
                // to keep more visas in EB2 (simulating that EB2 AOS is easier than EB3 Consular).
                if (consularShutdown && !restricted) {
                    double heldInEb2 = unused * 0.8; // Hold back 80% of surplus in EB2
                    unused -= heldInEb2;
                    System.out.println(String.format("[CALCULATION] %s: Consular Shutdown - Holding %.2f surplus visas in EB2", c, heldInEb2));
                }
                
                supplyMap.get(c).put(EbCategory.EB3, supplyMap.get(c).get(EbCategory.EB3) + unused);
                supplyMap.get(c).put(EbCategory.EB2, supplyMap.get(c).get(EbCategory.EB2) - unused);
                if (restricted) {
                    bannedPoolPerCategory.put(EbCategory.EB2, bannedPoolPerCategory.get(EbCategory.EB2) - unused);
                    bannedPoolPerCategory.put(EbCategory.EB3, bannedPoolPerCategory.get(EbCategory.EB3) + unused);
                }
                System.out.println(String.format("[CALCULATION] %s: Vertical Spillover EB2 -> EB3: %.2f", c, unused));
            }
        }

        // 4. Horizontal/Global Redistribution & Cross-Category Spillover
        System.out.println("[CALCULATION] Processing Horizontal/Global Redistribution & Cross-Category Spillover...");
        
        double globalCarryoverPool = 0;
        double globalBannedCarryoverPool = 0;

        // Process EB1 -> EB2 -> EB3 globally
        // If consularShutdown is true, we prioritize EB2 over EB3 during the spillover from EB1.
        // Actually, the law says EB1 spills to EB2 and EB3.
        // Usually, it goes EB1 -> EB2 -> EB3.
        // In COVID years, EB1 had huge spillover to EB2 because FB was blocked.
        EbCategory[] order = {EbCategory.EB1, EbCategory.EB2, EbCategory.EB3};
        if (consularShutdown) {
            // In a consular shutdown, EB2 is often prioritized because these are typically 
            // adjustments of status already in the US (I-485), 
            // whereas EB3 includes more consular processing which was shut down.
            System.out.println("[CALCULATION] EB2 prioritization logic enabled due to consular shutdown.");
        }
        for (EbCategory cat : order) {
            double pooledUnusedThisCategory = 0;
            double bannedUnusedThisCategory = 0;
            
            // Collect unused visas from this category across all countries
            for (Country c : Country.values()) {
                double supply = supplyMap.get(c).get(cat);
                long demand = demandMap.get(c).get(cat);
                if (supply > demand) {
                    double unused = supply - demand;
                    pooledUnusedThisCategory += unused;
                    supplyMap.get(c).put(cat, (double) demand);
                    
                    if (isRestricted(c)) {
                        // This logic relies on bannedPoolPerCategory being updated in step 2 and 3
                        // But since we set supply to demand, we need to track how much of the pooled is from banned
                        // Actually, bannedPoolPerCategory already has the 'available' banned visas for this category.
                        // However, we need to be careful not to double count.
                        // Since Step 3 moves banned visas between categories in bannedPoolPerCategory, 
                        // and supplyMap[c][cat] reflects the supply after Step 3.
                        // The amount of 'banned' unused is exactly what was available in the bannedPoolPerCategory for this cat.
                        // Wait, if demand > 0 for a restricted country (should be 0), then it's different.
                        // But in Step 1, demand for restricted is set to 0.
                        // So pooledUnused from restricted country IS exactly its supply.
                    }
                    System.out.println(String.format("[CALCULATION] Pooling %s %.2f unused visas from %s", cat, unused, c));
                }
            }
            
            double totalPoolForCategory = pooledUnusedThisCategory + globalCarryoverPool;
            // The share of banned visas in this category's pool
            // Includes carryover from previous category's banned pool
            double totalBannedPoolForCategory = bannedPoolPerCategory.get(cat) + globalBannedCarryoverPool;
            
            if (totalPoolForCategory > 0) {
                long totalOversubscribedDemand = 0;
                Map<Country, Long> oversubscribedBacklog = new HashMap<>();
                for (Country c : Country.values()) {
                    double supply = supplyMap.get(c).get(cat);
                    long demand = demandMap.get(c).get(cat);
                    if (demand > supply) {
                        // Prioritize based on oldest backlog (e.g., before 2015)
                        long oldBacklog = excelDataService.getOldestBacklog(c, cat, 2015);
                        long remainingDemand = demand - (long) supply;
                        
                        // Weight: 70% oldest backlog, 30% total backlog
                        long weight = (long) (oldBacklog * 0.7 + remainingDemand * 0.3);
                        if (weight == 0 && remainingDemand > 0) weight = 1; // Ensure some distribution if no old backlog
                        
                        // If consular shutdown is active, give EB2 an extra weight boost
                        if (consularShutdown && cat == EbCategory.EB2) {
                            weight *= 1.5; // EB2 receives 50% more priority in the pool distribution
                            System.out.println(String.format("[CALCULATION] EB2 Weight Boost for %s: %d", c, weight));
                        }
                        
                        oversubscribedBacklog.put(c, weight);
                        totalOversubscribedDemand += weight;
                    }
                }
                
                if (totalOversubscribedDemand > 0) {
                    double bannedShareInPool = Math.min(1.0, totalBannedPoolForCategory / totalPoolForCategory);
                    System.out.println(String.format("[CALCULATION] Pool for %s (including spillover): Total=%.2f, From Banned=%.2f (%.1f%%)", 
                        cat, totalPoolForCategory, totalBannedPoolForCategory, bannedShareInPool * 100));

                    double visasDistributed = Math.min(totalPoolForCategory, totalOversubscribedDemand);
                    
                    for (Country c : oversubscribedBacklog.keySet()) {
                        double shareOfDemand = (double) oversubscribedBacklog.get(c) / totalOversubscribedDemand;
                        double addedSupply = visasDistributed * shareOfDemand;
                        double fromBanned = addedSupply * bannedShareInPool;
                        
                        supplyMap.get(c).put(cat, supplyMap.get(c).get(cat) + addedSupply);
                        System.out.println(String.format("[CALCULATION] Redistributing %.2f unused %s visas to %s", addedSupply, cat, c));
                        if (c == Country.INDIA) {
                            System.out.println(String.format("[CALCULATION] INDIA SPECIFIC: Out of %.2f visas added to %s, %.2f are specifically from Banned Countries.", 
                                addedSupply, cat, fromBanned));
                        }
                    }
                    
                    globalCarryoverPool = Math.max(0, totalPoolForCategory - totalOversubscribedDemand);
                    globalBannedCarryoverPool = globalCarryoverPool * bannedShareInPool;
                    
                    if (globalCarryoverPool > 0) {
                        System.out.println(String.format("[CALCULATION] %s category surplus of %.2f carrying over to next category pool.", cat, globalCarryoverPool));
                    }
                } else {
                    // No oversubscribed demand for this category, everything carries over
                    globalCarryoverPool = totalPoolForCategory;
                    globalBannedCarryoverPool = totalBannedPoolForCategory;
                    System.out.println(String.format("[CALCULATION] No oversubscribed demand for %s. Entire pool of %.2f carrying over.", cat, globalCarryoverPool));
                }
            } else {
                globalCarryoverPool = 0;
                globalBannedCarryoverPool = 0;
            }
        }

        for (Country c : Country.values()) {
            for (EbCategory cat : EbCategory.values()) {
                if (supplyMap.get(c).containsKey(cat)) {
                    System.out.println(String.format("[CALCULATION] FINAL SUPPLY for %s %s: %.2f", c, cat, supplyMap.get(c).get(cat)));
                }
            }
        }

        System.out.println("[CALCULATION] Dynamic supply calculation complete.");
        return supplyMap;
    }
}
