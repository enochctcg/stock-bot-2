package com.eno.stockbot.bean.telegram;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 解析後的指令，例如 "/add@MyStockBot 2330, 2317" → name=add, args=[2330, 2317]。
 */
public record Command(String name, List<String> args) {

    public static Optional<Command> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("/") || trimmed.length() == 1) {
            return Optional.empty();
        }

        String[] tokens = trimmed.substring(1).split(SEPARATOR);
        String name = tokens[0];
        int at = name.indexOf('@');           // 群組中指令會帶 @botname
        if (at >= 0) {
            name = name.substring(0, at);
        }
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Command(name.toLowerCase(Locale.ROOT), normalize(Arrays.stream(tokens).skip(1).toList())));
    }

    /** 解析沒有指令前綴的代號清單，例如 ForceReply 回覆的「2330 2317, 00878」。 */
    public static List<String> args(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return normalize(Arrays.asList(text.strip().split(SEPARATOR)));
    }

    private static final String SEPARATOR = "[\\s,，、]+";

    private static List<String> normalize(List<String> tokens) {
        return tokens.stream()
                .filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
