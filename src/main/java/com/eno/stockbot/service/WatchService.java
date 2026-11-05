package com.eno.stockbot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.eno.stockbot.bean.Watch;
import com.eno.stockbot.bo.WatchChange;
import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.dto.Quote;
import com.eno.stockbot.repository.WatchRepository;

/**
 * 觀察清單：新增時檢查代號格式、重複、上限，並向富果確認股票存在。
 */
@Service
public class WatchService {

    /** 台股代號：4~6 碼數字或英數（ETF 如 00878、權證、特別股如 2881A）。 */
    private static final Pattern SYMBOL = Pattern.compile("^[0-9][0-9A-Z]{3,5}$");

    private final WatchRepository watches;
    private final QuoteService quotes;
    private final BotProperties props;

    public WatchService(WatchRepository watches, QuoteService quotes, BotProperties props) {
        this.watches = watches;
        this.quotes = quotes;
        this.props = props;
    }

    public List<Watch> findByChatId(long chatId) {
        return watches.findByChatId(chatId);
    }

    /** 觀察清單的代號，依加入順序。 */
    public List<String> symbols(long chatId) {
        return findByChatId(chatId).stream().map(Watch::symbol).toList();
    }

    public int maxPerUser() {
        return props.maxWatchPerUser();
    }

    /** @return done 為「代號 名稱」 */
    public WatchChange add(long chatId, List<String> symbols) {
        List<String> added = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int count = watches.count(chatId);

        for (String symbol : symbols) {
            if (!SYMBOL.matcher(symbol).matches()) {
                problems.add(symbol + "：代號格式不正確");
            } else if (watches.exists(chatId, symbol)) {
                problems.add(symbol + "：已經在清單中");
            } else if (count >= maxPerUser()) {
                problems.add(symbol + "：已達上限 " + maxPerUser() + " 檔");
            } else {
                Optional<Quote> q = quotes.getQuote(symbol);
                if (q.isEmpty()) {
                    problems.add(symbol + "：查無此股票");
                } else {
                    watches.add(chatId, symbol, q.get().name());
                    added.add(symbol + " " + q.get().name());
                    count++;
                }
            }
        }
        return new WatchChange(added, problems);
    }

    /** @return problems 為不在清單中的代號 */
    public WatchChange remove(long chatId, List<String> symbols) {
        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String symbol : symbols) {
            (remove(chatId, symbol) ? removed : missing).add(symbol);
        }
        return new WatchChange(removed, missing);
    }

    /** @return 是否真的有移除 */
    public boolean remove(long chatId, String symbol) {
        return watches.remove(chatId, symbol);
    }
}
