package com.majstr.backend;

import com.majstr.backend.config.CorsProperties;
import com.majstr.backend.config.JwtProperties;
import com.majstr.backend.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class, RateLimitProperties.class})
public class MajstrApplication {

    public static void main(String[] args) {
        SpringApplication.run(MajstrApplication.class, args);
    }
}
