package com.eno.stockbot.service.subscriber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.eno.stockbot.bean.fugle.Quote;
import com.eno.stockbot.bean.subscriber.Watch;
import com.eno.stockbot.bean.subscriber.WatchChange;
import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.mapper.subscriber.WatchMapper;
import com.eno.stockbot.service.fugle.QuoteService;

/**
 * 觀察清單：新增時檢查代號格式、重複、上限，並向富果確認股票存在。
 */
@Service
public class WatchService {

    private static final Logger log = LoggerFactory.getLogger(WatchService.class);

    /** 台股代號：4~6 碼數字或英數（ETF 如 00878、權證、特別股如 2881A）。 */
    private static final Pattern SYMBOL = Pattern.compile("^[0-9][0-9A-Z]{3,5}$");

    private final WatchMapper watchMapper;
    private final QuoteService quotes;
    private final BotProperties props;

    public WatchService(WatchMapper watchMapper, QuoteService quotes, BotProperties props) {
        this.watchMapper = watchMapper;
        this.quotes = quotes;
        this.props = props;
    }

    /** 依加入順序。 */
    public List<Watch> findByChatId(long chatId) {
        return watchMapper.selectList(Wrappers.<Watch>lambdaQuery()
                .eq(Watch::getChatId, chatId)
                .orderByAsc(Watch::getId));
    }

    /** 觀察清單的代號，依加入順序。 */
    public List<String> symbols(long chatId) {
        return findByChatId(chatId).stream().map(Watch::getSymbol).toList();
    }

    public int maxPerUser() {
        return props.maxWatchPerUser();
    }

    /** 所有人觀察清單的聯集（每檔一次），給歷史報價紀錄用。 */
    public List<String> findAllSymbols() {
        return watchMapper.selectObjs(Wrappers.<Watch>query()
                .select("DISTINCT symbol")
                .orderByAsc("symbol"));
    }

    /** 所有人的觀察清單，依 chat_id 分組並保留加入順序；要推給誰由呼叫端依各自的設定過濾。 */
    public Map<Long, List<String>> findGroupedByChat() {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        watchMapper.selectList(Wrappers.<Watch>lambdaQuery()
                        .select(Watch::getChatId, Watch::getSymbol)
                        .orderByAsc(Watch::getChatId, Watch::getId))
                .forEach(w -> result.computeIfAbsent(w.getChatId(), k -> new ArrayList<>()).add(w.getSymbol()));
        return result;
    }

    /** @return done 為「代號 名稱」 */
    public WatchChange add(long chatId, List<String> symbols) {
        List<String> added = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        long count = watchMapper.selectCount(Wrappers.<Watch>lambdaQuery().eq(Watch::getChatId, chatId));

        for (String symbol : symbols) {
            if (!SYMBOL.matcher(symbol).matches()) {
                problems.add(symbol + "：代號格式不正確");
            } else if (exists(chatId, symbol)) {
                problems.add(symbol + "：已經在清單中");
            } else if (count >= maxPerUser()) {
                problems.add(symbol + "：已達上限 " + maxPerUser() + " 檔");
            } else {
                Optional<Quote> q = quotes.getQuote(symbol);
                if (q.isEmpty()) {
                    problems.add(symbol + "：查無此股票");
                } else {
                    int rows = watchMapper.upsert(chatId, symbol, q.get().name());
                    log.info("[DB] 觀察清單{} chat {}：{} {}", rows == 1 ? "新增" : "已存在", chatId, symbol, q.get().name());
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
        int rows = watchMapper.delete(Wrappers.<Watch>lambdaQuery()
                .eq(Watch::getChatId, chatId)
                .eq(Watch::getSymbol, symbol));
        log.info("[DB] 觀察清單移除 chat {}：{}（{}）", chatId, symbol, rows > 0 ? "已刪除" : "不存在");
        return rows > 0;
    }

    private boolean exists(long chatId, String symbol) {
        return watchMapper.exists(Wrappers.<Watch>lambdaQuery()
                .eq(Watch::getChatId, chatId)
                .eq(Watch::getSymbol, symbol));
    }
}
