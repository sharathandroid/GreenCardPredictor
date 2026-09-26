package org.innovativebrains.greencardpredictor.service;

import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class VisaBulletinService {

    private final Map<String, LocalDate> finalActionDates = new HashMap<>();
    private final Map<String, LocalDate> filingDates = new HashMap<>();

    public VisaBulletinService() {
        // February 2026 Visa Bulletin Dates from T&S Law (tandslaw.com)
        
        // Final Action Dates (Chart A)
        setFinalActionDate(Country.INDIA, EbCategory.EB1, LocalDate.of(2023, 2, 1));
        setFinalActionDate(Country.INDIA, EbCategory.EB2, LocalDate.of(2013, 7, 15));
        setFinalActionDate(Country.INDIA, EbCategory.EB3, LocalDate.of(2013, 11, 15));
        
        setFinalActionDate(Country.CHINA, EbCategory.EB1, LocalDate.of(2023, 2, 1));
        setFinalActionDate(Country.CHINA, EbCategory.EB2, LocalDate.of(2021, 9, 1));
        setFinalActionDate(Country.CHINA, EbCategory.EB3, LocalDate.of(2021, 5, 1));
        
        setFinalActionDate(Country.ROW, EbCategory.EB1, LocalDate.now()); // Current
        setFinalActionDate(Country.ROW, EbCategory.EB2, LocalDate.of(2024, 4, 1));
        setFinalActionDate(Country.ROW, EbCategory.EB3, LocalDate.of(2023, 6, 1));

        // Dates for Filing (Chart B)
        setFilingDate(Country.INDIA, EbCategory.EB1, LocalDate.of(2023, 8, 1));
        setFilingDate(Country.INDIA, EbCategory.EB2, LocalDate.of(2013, 12, 1));
        setFilingDate(Country.INDIA, EbCategory.EB3, LocalDate.of(2014, 8, 15));

        setFilingDate(Country.CHINA, EbCategory.EB1, LocalDate.of(2023, 8, 1));
        setFilingDate(Country.CHINA, EbCategory.EB2, LocalDate.of(2022, 1, 1));
        setFilingDate(Country.CHINA, EbCategory.EB3, LocalDate.of(2022, 1, 1));

        setFilingDate(Country.ROW, EbCategory.EB1, LocalDate.now()); // Current
        setFilingDate(Country.ROW, EbCategory.EB2, LocalDate.of(2024, 10, 15));
        setFilingDate(Country.ROW, EbCategory.EB3, LocalDate.of(2023, 10, 1));
    }

    private void setFinalActionDate(Country c, EbCategory cat, LocalDate date) {
        finalActionDates.put(c.name() + "_" + cat.name(), date);
    }

    private void setFilingDate(Country c, EbCategory cat, LocalDate date) {
        filingDates.put(c.name() + "_" + cat.name(), date);
    }

    public LocalDate getFinalActionCutOff(Country c, EbCategory cat) {
        return finalActionDates.getOrDefault(c.name() + "_" + cat.name(), LocalDate.of(2010, 1, 1));
    }

    public LocalDate getFilingCutOff(Country c, EbCategory cat) {
        return filingDates.getOrDefault(c.name() + "_" + cat.name(), LocalDate.of(2010, 1, 1));
    }
}
