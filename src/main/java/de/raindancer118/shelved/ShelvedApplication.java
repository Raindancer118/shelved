package de.raindancer118.shelved;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShelvedApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShelvedApplication.class, args);
    }
}
