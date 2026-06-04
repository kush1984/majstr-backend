package com.majstr.backend;

import com.majstr.backend.config.CorsProperties;
import com.majstr.backend.config.EmailProperties;
import com.majstr.backend.config.JwtProperties;
import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.config.RateLimitProperties;
import com.majstr.backend.config.StorageProperties;
import com.majstr.backend.config.VapidProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({
        JwtProperties.class,
        CorsProperties.class,
        RateLimitProperties.class,
        StorageProperties.class,
        PortalProperties.class,
        EmailProperties.class,
        VapidProperties.class
})
public class MajstrApplication {

    public static void main(String[] args) {
        SpringApplication.run(MajstrApplication.class, args);
    }
}
