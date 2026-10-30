package com.eno.stockbot.report;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eno.stockbot.market.Quote;

/**
 * 把報價組成 Telegram 訊息。台股慣例：紅漲綠跌。
 *
 * <pre>
 * ⏱ 10:20 盤中報價
 * 🔴 2330 台積電  1,085.00  +15.00 (+1.40%)
 * 🟢 2317 鴻海  212.50  -1.00 (-0.47%)
 * </pre>
 */
public final class ReportFormatter {

    private ReportFormatter() {
    }

    /**
     * @param header  第一行標題
     * @param symbols 要顯示的股票（依此順序）
     * @param quotes  查到的報價；缺少的股票顯示「查無資料」
     */
    public static String format(String header, List<String> symbols, Map<String, Quote> quotes) {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        for (String symbol : symbols) {
            Quote q = quotes.get(symbol);
            lines.add(q == null ? "❔ " + symbol + "  查無資料" : line(q));
        }
        return String.join("\n", lines);
    }

    static String line(Quote q) {
        StringBuilder sb = new StringBuilder();
        String label = q.symbol() + " " + q.name();

        if (!q.hasTrade()) {
            return sb.append("⚪ ").append(label).append("  尚無成交").toString();
        }

        double change = q.change() == null ? 0 : q.change();
        sb.append(change > 0 ? "🔴 " : change < 0 ? "🟢 " : "⚪ ")
                .append(label)
                .append("  ").append(price(q.price()));

        if (q.change() != null && q.changePercent() != null) {
            sb.append("  ").append(signed(q.change()))
                    .append(" (").append(String.format("%+.2f%%", q.changePercent())).append(")");
        }

        if (q.limitUp()) {
            sb.append(" 漲停");
        } else if (q.limitDown()) {
            sb.append(" 跌停");
        }
        if (q.halted()) {
            sb.append(" ⏸暫停");
        }
        if (q.trial()) {
            sb.append(" (試撮)");
        }
        return sb.toString();
    }

    private static String price(double v) {
        return new DecimalFormat("#,##0.00").format(v);
    }

    private static String signed(double v) {
        String s = new DecimalFormat("#,##0.00").format(Math.abs(v));
        return (v > 0 ? "+" : v < 0 ? "-" : "") + s;
    }
}
