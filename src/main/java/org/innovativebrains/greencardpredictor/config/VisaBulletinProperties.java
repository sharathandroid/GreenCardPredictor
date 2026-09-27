package org.innovativebrains.greencardpredictor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed binding for visa-bulletin.yml (imported via
 * spring.config.import in application.properties), replacing the
 * hand-rolled java.util.Properties parsing VisaBulletinService used before
 * this app was wired up to use Spring Boot's own configuration-properties
 * mechanism.
 *
 * finalAction/filing are keyed by Country enum name, then by EbCategory enum
 * name (e.g. finalAction.get("INDIA").get("EB2")), with each leaf value
 * either an ISO date string, "CURRENT", or (final-action only) "UNAUTHORIZED"
 * -- VisaBulletinService interprets those. Keys not present fall back to a
 * fixed default cutoff there, same as before this class existed.
 *
 * FIX (2026-09-27): worldwideEbLimit and perCountryCap were added after the
 * project owner pasted the real September 2026 bulletin text, which states
 * these numbers directly (paragraphs 2 and D) rather than requiring this
 * app to derive them from a hardcoded 140,000 base plus a computed FB-to-EB
 * spillover estimate. DOS's own published total already incorporates
 * whatever spillover actually happened -- using it directly is strictly
 * more accurate than re-deriving an approximation of the same number. See
 * PredictionService.predict() for how this replaced the derived total in
 * the default (non-manual-override) path, and GAPS_AND_FIXES.md #16.
 */
@Data
@ConfigurationProperties(prefix = "visa-bulletin")
public class VisaBulletinProperties {
    private String month;
    private Long worldwideEbLimit;
    private Long perCountryCap;
    private Map<String, Map<String, String>> finalAction = new HashMap<>();
    private Map<String, Map<String, String>> filing = new HashMap<>();
}
