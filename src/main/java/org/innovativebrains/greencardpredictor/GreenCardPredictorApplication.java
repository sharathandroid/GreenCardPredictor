package org.innovativebrains.greencardpredictor;

import org.innovativebrains.greencardpredictor.config.VisaBulletinProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VisaBulletinProperties.class)
public class GreenCardPredictorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GreenCardPredictorApplication.class, args);
    }

}
