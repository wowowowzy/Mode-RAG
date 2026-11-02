package com.challenger.modetest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.challenger.modetest.config.ModeTestProperties;

@SpringBootApplication
@EnableConfigurationProperties(ModeTestProperties.class)
public class ModeTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModeTestApplication.class, args);
    }
}
