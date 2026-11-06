package com.eno.stockbot.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus：掃描 mapper 套件底下的介面，自動產生實作。
 * SQL 的耗時記錄見 {@link SqlTimingInterceptor}。
 */
@Configuration
@MapperScan("com.eno.stockbot.mapper")
public class MybatisPlusConfig {
}
