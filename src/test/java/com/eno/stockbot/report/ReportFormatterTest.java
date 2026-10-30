package com.eno.stockbot.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.eno.stockbot.market.Quote;

class ReportFormatterTest {

    private static Quote quote(String symbol, String name, Double price, Double change, Double pct) {
        return new Quote(symbol, name, "2026-10-28", price, change, pct, false, false, false, false, false);
    }

    @Test
    void formatsUpDownFlatAndMissing() {
        Map<String, Quote> quotes = Map.of(
                "2330", quote("2330", "台積電", 1085.0, 15.0, 1.4),
                "2317", quote("2317", "鴻海", 212.5, -1.0, -0.47),
                "0050", quote("0050", "元大台灣50", 190.0, 0.0, 0.0),
                "6488", quote("6488", "環球晶", null, null, null));

        String text = ReportFormatter.format("⏱ 10:20 盤中報價",
                List.of("2330", "2317", "0050", "6488", "9999"), quotes);

        assertEquals("""
                ⏱ 10:20 盤中報價
                🔴 2330 台積電  1,085.00  +15.00 (+1.40%)
                🟢 2317 鴻海  212.50  -1.00 (-0.47%)
                ⚪ 0050 元大台灣50  190.00  0.00 (+0.00%)
                ⚪ 6488 環球晶  尚無成交
                ❔ 9999  查無資料""", text);
    }

    @Test
    void appendsLimitAndTrialFlags() {
        Quote q = new Quote("1234", "X", "2026-10-28", 11.0, 1.0, 10.0, true, false, true, false, true);
        assertEquals("🔴 1234 X  11.00  +1.00 (+10.00%) 漲停 ⏸暫停 (試撮)", ReportFormatter.line(q));
    }
}
