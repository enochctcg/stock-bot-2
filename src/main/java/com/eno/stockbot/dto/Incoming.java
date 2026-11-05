package com.eno.stockbot.dto;

/**
 * 從 Telegram update 解析出來、交給 {@link CommandHandler} 的輸入。
 */
public final class Incoming {

    private Incoming() {
    }

    /**
     * 一般文字訊息。
     *
     * @param replyToText 若是回覆某則訊息，該訊息的文字（用來辨識 ForceReply 的回覆）；否則為 null
     * @param privateChat 是否為一對一私訊
     */
    public record Message(long chatId, String username, String text, String replyToText, boolean privateChat) {
    }

    /**
     * 使用者點擊 inline 按鈕。
     *
     * @param messageId 按鈕所在的訊息，用來原地編輯
     */
    public record Callback(String id, long chatId, long messageId, String username, String data) {
    }
}
