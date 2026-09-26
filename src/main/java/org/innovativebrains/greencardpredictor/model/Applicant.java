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

    public Applicant(Country country, EbCategory category, LocalDate priorityDate) {
        this.country = country;
        this.category = category;
        this.priorityDate = priorityDate;
    }
}
