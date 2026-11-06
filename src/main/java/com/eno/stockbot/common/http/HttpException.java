package com.eno.stockbot.common.http;

/**
 * 沒有拿到 HTTP 回應：連不上、逾時、被中斷，或回應不是合法的 JSON。
 */
public class HttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HttpException(String message, Throwable cause) {
        super(message, cause);
    }
}
