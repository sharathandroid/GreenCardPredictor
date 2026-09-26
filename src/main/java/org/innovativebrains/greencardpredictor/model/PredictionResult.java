package org.innovativebrains.greencardpredictor.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDate;
import java.util.List;

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

    /**
     * Ordered, human-readable trace of how this result was derived -- each
     * entry is one step of the calculation (restriction check, spillover
     * math, bulletin cutoffs used, backlog volume, annual supply, the final
     * wait-time formula), in the order they were actually computed. Added
     * 2026-09-26 so a caller doesn't have to reverse-engineer the single
     * `explanation` paragraph to see which inputs drove the result.
     */
    private List<String> reasoningSteps;
}
