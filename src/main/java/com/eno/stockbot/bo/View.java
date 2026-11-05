package com.eno.stockbot.bo;

/**
 * 一個畫面 = 文字 + 鍵盤（可為 null）。html 為 true 時以 parse_mode=HTML 送出（報價表格）。
 */
public record View(String text, Object markup, boolean html) {

    public View(String text, Object markup) {
        this(text, markup, false);
    }
}
