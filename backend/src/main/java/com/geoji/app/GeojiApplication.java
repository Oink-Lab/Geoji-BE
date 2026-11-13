package com.geoji.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GeojiApplication {
    public static void main(String[] args) { SpringApplication.run(GeojiApplication.class, args); }
}
