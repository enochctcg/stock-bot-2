package com.eno.stockbot.bean.subscriber;

import java.util.List;

/**
 * 新增 / 移除觀察清單的結果。
 *
 * @param done     成功的項目，例如「2330 台積電」
 * @param problems 失敗的項目與原因，例如「2330：已經在清單中」
 */
public record WatchChange(List<String> done, List<String> problems) {
}
