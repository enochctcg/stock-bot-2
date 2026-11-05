package com.eno.stockbot.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class QuoteTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void parsesIntradayQuote() throws Exception {
        String json = """
                {"date":"2026-10-28","type":"EQUITY","exchange":"TWSE","market":"TSE",
                 "symbol":"2330","name":"台積電","referencePrice":1070,"previousClose":1070,
                 "closePrice":1085,"change":15,"changePercent":1.4,"lastPrice":1085,
                 "isLimitUpPrice":false,"isTrial":false,"isClose":false,
                 "tradingHalt":{"isHalted":false}}
                """;
        Quote q = Quote.from(om.readTree(json));

        assertEquals("2330", q.symbol());
        assertEquals("台積電", q.name());
        assertEquals("2026-10-28", q.date());
        assertEquals(1085.0, q.price());
        assertEquals(15.0, q.change());
        assertEquals(1.4, q.changePercent());
        assertTrue(q.hasTrade());
        assertFalse(q.halted());
    }

    @Test
    void noTradeYet() throws Exception {
        Quote q = Quote.from(om.readTree("""
                {"date":"2026-10-28","symbol":"6488","name":"環球晶","referencePrice":500}
                """));
        assertFalse(q.hasTrade());
        assertNull(q.changePercent());
    }

    @Test
    void fallsBackToClosePriceAndFlags() throws Exception {
        Quote q = Quote.from(om.readTree("""
                {"symbol":"1234","name":"X","closePrice":10.5,"change":0.95,"changePercent":9.95,
                 "isLimitUpPrice":true,"isLimitUpHalt":true,"isClose":true}
                """));
        assertEquals(10.5, q.price());
        assertTrue(q.limitUp());
        assertTrue(q.halted());
        assertTrue(q.closed());
    }
}
