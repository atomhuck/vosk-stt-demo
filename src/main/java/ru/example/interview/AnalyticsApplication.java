package ru.example.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Точка входа микросервиса аналитики интервью. */
@SpringBootApplication(scanBasePackages = "ru.example")
@ConfigurationPropertiesScan("ru.example")
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
