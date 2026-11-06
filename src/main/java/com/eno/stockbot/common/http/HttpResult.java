package com.eno.stockbot.common.http;

/**
 * HTTP 回應：狀態碼 + 內容。要不要重試、怎麼判斷錯誤由呼叫端決定。
 */
public record HttpResult(int status, String body) {

    public boolean isOk() {
        return status >= 200 && status < 300;
    }
}
