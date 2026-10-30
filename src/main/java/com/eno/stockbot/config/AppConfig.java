package com.eno.stockbot.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Bean
    public Clock clock() {
        return Clock.system(TAIPEI);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
