package com.eno.stockbot.bean.fugle;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 富果 /intraday/quote/{symbol} 回應中推播需要的欄位。
 *
 * @param price         最新成交價；尚未有任何成交時為 null
 * @param change        漲跌（元）
 * @param changePercent 漲跌幅（%）
 * @param date          資料日期（yyyy-MM-dd），用來判斷今天是否開盤
 */
public record Quote(
        String symbol,
        String name,
        String date,
        Double price,
        Double change,
        Double changePercent,
        boolean trial,
        boolean closed,
        boolean limitUp,
        boolean limitDown,
        boolean halted) {

    public static Quote from(JsonNode n) {
        Double price = firstPositive(n.path("lastPrice"), n.path("closePrice"));
        return new Quote(
                n.path("symbol").asText(""),
                n.path("name").asText(""),
                n.path("date").asText(""),
                price,
                price == null ? null : number(n.path("change")),
                price == null ? null : number(n.path("changePercent")),
                n.path("isTrial").asBoolean(false),
                n.path("isClose").asBoolean(false),
                n.path("isLimitUpPrice").asBoolean(false),
                n.path("isLimitDownPrice").asBoolean(false),
                n.path("tradingHalt").path("isHalted").asBoolean(false)
                        || n.path("isLimitUpHalt").asBoolean(false)
                        || n.path("isLimitDownHalt").asBoolean(false));
    }

    public boolean hasTrade() {
        return price != null;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static Double firstPositive(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node.isNumber() && node.asDouble() > 0) {
                return node.asDouble();
            }
        }
        return null;
    }
}
