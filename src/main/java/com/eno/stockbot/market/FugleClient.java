package com.eno.stockbot.market;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eno.stockbot.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 富果行情 REST API。只用到免費方案可用的 /intraday/quote/{symbol}。
 */
@Component
public class FugleClient {

    private static final Logger log = LoggerFactory.getLogger(FugleClient.class);

    private final HttpClient http;
    private final ObjectMapper om;
    private final RateLimiter rateLimiter;
    private final String baseUrl;
    private final String apiKey;

    public FugleClient(HttpClient http, ObjectMapper om, BotProperties props) {
        this.http = http;
        this.om = om;
        this.rateLimiter = new RateLimiter(props.fugle().requestsPerMinute());
        this.baseUrl = props.fugle().baseUrl();
        this.apiKey = props.fugle().apiKey();
    }

    /**
     * 查詢即時報價。
     *
     * @return 找不到該股票時回傳 empty
     * @throws FugleException 網路錯誤、額度用盡或 API key 無效
     */
    public Optional<Quote> getQuote(String symbol) {
        String url = baseUrl + "/intraday/quote/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-KEY", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> resp = send(request);
            int status = resp.statusCode();

            if (status == 200) {
                return Optional.of(Quote.from(readTree(resp.body())));
            }
            if (status == 400 || status == 404) {
                return Optional.empty();
            }
            if (status == 429 && attempt < 3) {
                log.warn("Fugle 429 for {}, retry #{}", symbol, attempt);
                sleep(Duration.ofSeconds(5L * attempt));
                continue;
            }
            if (status == 401 || status == 403) {
                throw new FugleAuthException("富果 API key 無效或沒有權限 (HTTP " + status + ")");
            }
            throw new FugleException("富果 API 錯誤 HTTP " + status + ": " + abbreviate(resp.body()));
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            rateLimiter.acquire();
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FugleException("interrupted", e);
        } catch (IOException e) {
            throw new FugleException("無法連線到富果 API: " + e.getMessage(), e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String body) {
        try {
            return om.readTree(body);
        } catch (IOException e) {
            throw new FugleException("無法解析富果回應: " + abbreviate(body), e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FugleException("interrupted", e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    public static class FugleException extends RuntimeException {
        public FugleException(String message) {
            super(message);
        }

        public FugleException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** API key 錯誤：所有請求都會失敗，不值得逐檔重試。 */
    public static class FugleAuthException extends FugleException {
        public FugleAuthException(String message) {
            super(message);
        }
    }
}
