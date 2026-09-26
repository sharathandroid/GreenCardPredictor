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
 */
@Data
@ConfigurationProperties(prefix = "visa-bulletin")
public class VisaBulletinProperties {
    private String month;
    private Map<String, Map<String, String>> finalAction = new HashMap<>();
    private Map<String, Map<String, String>> filing = new HashMap<>();
}
