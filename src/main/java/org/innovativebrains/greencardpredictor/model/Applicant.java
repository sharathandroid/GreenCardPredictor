package org.innovativebrains.greencardpredictor.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Applicant {
    private Country country;
    private EbCategory category;
    private LocalDate priorityDate;
    private Double manualFbSpillover; // Optional manual override for family-based spillover (unused family visas)
    private boolean consularShutdown; // Optional flag to simulate 2020-2022 style consular shutdown (higher EB2 allocation)

    /**
     * Optional estimate, from 0.0 (no effect) to 1.0 (fully suspended), of how
     * much the CURRENT worldwide immigrant-visa-interview pause (DOS began
     * this ~Aug 25, 2026 for consular retraining; it primarily affects
     * FAMILY-based cases and was expected -- not guaranteed -- to lift by
     * mid-September 2026) is suppressing family-preference visa issuance this
     * fiscal year. This feeds the FB-to-EB spillover estimate: a bigger
     * reduction in family visas actually issued means more unused family
     * numbers legally fall across to the employment-based pool the
     * following year. Leave at 0 to ignore this effect (e.g. when supplying
     * manualFbSpillover directly). Values outside [0,1] are clamped.
     */
    private double familyVisaPauseSeverity;

    public Applicant(Country country, EbCategory category, LocalDate priorityDate) {
        this.country = country;
        this.category = category;
        this.priorityDate = priorityDate;
    }
}
