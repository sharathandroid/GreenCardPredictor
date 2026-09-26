package org.innovativebrains.greencardpredictor.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDate;

@Data
@Builder
public class PredictionResult {
    private LocalDate finalActionDate;
    private LocalDate filingDate;
    private long filingWaitMonths;
    private long finalActionWaitMonths;
    private String formattedFilingWait;
    private String formattedFinalActionWait;
    private String explanation;
    private boolean restricted;
    private Double bannedCountrySpilloverToIndia;
}
