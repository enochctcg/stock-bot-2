package com.eno.stockbot.service.telegram;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eno.stockbot.bean.fugle.Quote;

/**
 * 把報價組成 Telegram 訊息（HTML 格式，送出時要帶 parse_mode=HTML）。台股慣例：紅漲綠跌。
 *
 * <p>表格放在 &lt;pre&gt; 裡用等寬字型對齊。中文與 emoji 在各裝置的寬度不一致，
 * 所以數字欄放前面、名稱放最後一欄，數字欄一定對齊，名稱長短不影響。
 *
 * <pre>
 * ⏱ 12:30 盤中報價
 * Code      Price     Chg   Chg%    Name
 * 00919     31.13   -0.59 -1.86% 🟢 群益台灣精選高息
 * 2330   2,245.00  -50.00 -2.18% 🟢 台積電
 * 00632R    10.72   +0.20 +1.90% 🔴 元大台灣50反1
 * </pre>
 */
public final class ReportFormatter {

    private static final String NONE = "-";

    private ReportFormatter() {
    }

    /** 一列表格的各欄文字（尚未補空白）。 */
    record Row(String symbol, String price, String change, String percent, String label) {
    }

    /**
     * @param header  第一行標題（純文字，會自動跳脫）
     * @param symbols 要顯示的股票（依此順序）
     * @param quotes  查到的報價；缺少的股票顯示「查無資料」
     */
    public static String format(String header, List<String> symbols, Map<String, Quote> quotes) {
        List<Row> rows = new ArrayList<>();
        for (String symbol : symbols) {
            Quote q = quotes.get(symbol);
            rows.add(q == null ? new Row(symbol, NONE, NONE, NONE, "❔ 查無資料") : row(q));
        }
        return escape(header) + "\n<pre>" + escape(table(rows)) + "</pre>";
    }

    /** 依每欄最長的內容決定欄寬：代號靠左，數字靠右。 */
    static String table(List<Row> rows) {
        Row head = new Row("Code", "Price", "Chg", "Chg%", "   Name");
        int symbolWidth = head.symbol().length();
        int priceWidth = head.price().length();
        int changeWidth = head.change().length();
        int percentWidth = head.percent().length();
        for (Row r : rows) {
            symbolWidth = Math.max(symbolWidth, r.symbol().length());
            priceWidth = Math.max(priceWidth, r.price().length());
            changeWidth = Math.max(changeWidth, r.change().length());
            percentWidth = Math.max(percentWidth, r.percent().length());
        }

        List<String> lines = new ArrayList<>();
        List<Row> all = new ArrayList<>();
        all.add(head);
        all.addAll(rows);
        for (Row r : all) {
            lines.add(padRight(r.symbol(), symbolWidth)
                    + " " + padLeft(r.price(), priceWidth)
                    + " " + padLeft(r.change(), changeWidth)
                    + " " + padLeft(r.percent(), percentWidth)
                    + " " + r.label());
        }
        return String.join("\n", lines);
    }

    static Row row(Quote q) {
        if (!q.hasTrade()) {
            return new Row(q.symbol(), NONE, NONE, NONE, "⚪ " + q.name() + " 尚無成交");
        }

        double change = q.change() == null ? 0 : q.change();
        StringBuilder label = new StringBuilder(change > 0 ? "🔴 " : change < 0 ? "🟢 " : "⚪ ").append(q.name());
        if (q.limitUp()) {
            label.append(" 漲停");
        } else if (q.limitDown()) {
            label.append(" 跌停");
        }
        if (q.halted()) {
            label.append(" ⏸暫停");
        }
        if (q.trial()) {
            label.append(" (試撮)");
        }
        return new Row(q.symbol(),
                price(q.price()),
                q.change() == null ? NONE : signed(q.change()),
                q.changePercent() == null ? NONE : String.format("%+.2f%%", q.changePercent()),
                label.toString());
    }

    /** Telegram HTML 模式只需要跳脫這三個字元。 */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static String padLeft(String s, int width) {
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }

    private static String price(double v) {
        return new DecimalFormat("#,##0.00").format(v);
    }

    private static String signed(double v) {
        String s = new DecimalFormat("#,##0.00").format(Math.abs(v));
        return (v > 0 ? "+" : v < 0 ? "-" : "") + s;
    }
}
