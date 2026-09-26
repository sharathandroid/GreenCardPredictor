package org.innovativebrains.greencardpredictor.controller;

import org.innovativebrains.greencardpredictor.model.Applicant;
import org.innovativebrains.greencardpredictor.model.Country;
import org.innovativebrains.greencardpredictor.model.EbCategory;
import org.innovativebrains.greencardpredictor.model.PredictionResult;
import org.innovativebrains.greencardpredictor.service.PredictionService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/prediction")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @PostMapping("/predict")
    public PredictionResult predict(@RequestBody Applicant applicant) {
        return predictionService.predict(applicant);
    }

    @GetMapping("/test")
    public PredictionResult test() {
        Applicant testApplicant = new Applicant(Country.INDIA, EbCategory.EB2, LocalDate.of(2018, 1, 1));
        return predictionService.predict(testApplicant);
    }
}
