package com.challenger.moderoute;

import com.challenger.moderoute.config.ModeRouteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ModeRouteProperties.class)
public class ModeRouteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModeRouteApplication.class, args);
    }
}
