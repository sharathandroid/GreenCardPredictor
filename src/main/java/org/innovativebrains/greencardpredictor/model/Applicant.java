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
     * Optional: the applicant's spouse's country of birth, for INA 202(b)
     * cross-chargeability. When set (and different from this applicant's own
     * country, and not itself a restricted country), PredictionService
     * compares both countries' bulletin-anchored outcomes and elects
     * whichever is more favorable -- exactly as a couple can actually elect
     * at filing time, per 9 FAM 503.2. Never makes the outcome worse: if the
     * applicant's own country is already better (or the spouse's country is
     * itself Unauthorized/restricted), this has no effect.
     */
    private Country spouseCountryOfBirth;

    /**
     * Optional: true if the applicant intends to file Form I-485 (adjustment
     * of status) domestically rather than through consular processing
     * abroad. Only relevant when the applicant's country is currently
     * restricted under Presidential Proclamation 10998: that suspension is
     * strongest for consular issuance, and a June 2026 district court ruling
     * allowed some domestic AOS processing to continue for affected
     * nationals. This app's backlog data isn't split by filing location, so
     * setting this does not produce a numeric wait estimate -- it only
     * changes the restricted-country explanation to note that a domestic
     * filing may still be possible, instead of flatly saying issuance is
     * blocked. Leave false (the default) for consular processing or when
     * unsure -- it's the more conservative assumption.
     */
    private boolean filingDomestically;

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
