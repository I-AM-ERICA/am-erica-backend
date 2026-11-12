package com.america.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AmericaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmericaApplication.class, args);
    }
}
