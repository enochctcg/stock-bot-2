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
    void formatsAlignedTableWithNameLast() {
        Map<String, Quote> quotes = Map.of(
                "2330", quote("2330", "台積電", 2245.0, -50.0, -2.18),
                "00632R", quote("00632R", "元大台灣50反1", 10.72, 0.2, 1.9),
                "0050", quote("0050", "元大台灣50", 98.4, 0.0, 0.0),
                "6488", quote("6488", "環球晶", null, null, null));

        String text = ReportFormatter.format("⏱ 12:30 盤中報價",
                List.of("2330", "00632R", "0050", "6488", "9999"), quotes);

        assertEquals("""
                ⏱ 12:30 盤中報價
                <pre>Code      Price    Chg   Chg%    Name
                2330   2,245.00 -50.00 -2.18% 🟢 台積電
                00632R    10.72  +0.20 +1.90% 🔴 元大台灣50反1
                0050      98.40   0.00 +0.00% ⚪ 元大台灣50
                6488          -      -      - ⚪ 環球晶 尚無成交
                9999          -      -      - ❔ 查無資料</pre>""", text);
    }

    @Test
    void appendsLimitAndTrialFlagsAfterName() {
        Quote q = new Quote("1234", "X", "2026-10-28", 11.0, 1.0, 10.0, true, false, true, false, true);
        assertEquals("🔴 X 漲停 ⏸暫停 (試撮)", ReportFormatter.row(q).label());
    }

    @Test
    void escapesHtmlInHeaderAndNames() {
        Map<String, Quote> quotes = Map.of("1234", quote("1234", "A&B<C>", 10.0, 0.0, 0.0));
        String text = ReportFormatter.format("x < y", List.of("1234"), quotes);
        assertEquals("x &lt; y", text.lines().findFirst().orElseThrow());
        assertEquals(true, text.contains("A&amp;B&lt;C&gt;"));
    }
}
