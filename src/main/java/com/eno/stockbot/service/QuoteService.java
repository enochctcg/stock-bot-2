package com.eno.stockbot.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.eno.stockbot.client.FugleClient;
import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.dto.Quote;

/**
 * 包一層短期快取：同一檔股票在 cache-ttl 內不重複打富果，節省免費方案額度。
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private record Cached(Quote quote, Instant fetchedAt) {
    }

    private final FugleClient fugle;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public QuoteService(FugleClient fugle, Clock clock, BotProperties props) {
        this.fugle = fugle;
        this.clock = clock;
        this.ttl = props.fugle().cacheTtl() == null ? Duration.ofSeconds(30) : props.fugle().cacheTtl();
    }

    /** 查單一股票（例如 /add 時驗證代號）。 */
    public Optional<Quote> getQuote(String symbol) {
        Cached c = cache.get(symbol);
        if (c != null && c.fetchedAt().plus(ttl).isAfter(clock.instant())) {
            return Optional.of(c.quote());
        }
        Optional<Quote> q = fugle.getQuote(symbol);
        q.ifPresent(quote -> cache.put(symbol, new Cached(quote, clock.instant())));
        return q;
    }

    /**
     * 批次查詢；單檔失敗只記 log，不影響其他股票。
     * API key 無效這類全域錯誤會直接拋出。
     */
    public Map<String, Quote> getQuotes(Collection<String> symbols) {
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            try {
                getQuote(symbol).ifPresent(q -> result.put(symbol, q));
            } catch (FugleClient.FugleAuthException e) {
                throw e;
            } catch (FugleClient.FugleException e) {
                log.warn("查詢 {} 失敗: {}", symbol, e.getMessage());
            }
        }
        return result;
    }
}
