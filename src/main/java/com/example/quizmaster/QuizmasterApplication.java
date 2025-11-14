package com.example.quizmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder; // <-- IMPORT THIS
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class QuizmasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizmasterApplication.class, args);
    }

    // THIS IS THE MODIFIED METHOD
    // It now uses RestTemplateBuilder to automatically configure
    // the modern, more reliable HTTP client.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}