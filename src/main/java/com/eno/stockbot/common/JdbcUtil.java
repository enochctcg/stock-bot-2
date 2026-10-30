package com.eno.stockbot.common;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.stereotype.Component;

/**
 * JdbcTemplate 的薄封裝，統一 Repository 的寫法：
 * <ul>
 *   <li>查單筆一律回傳 {@link Optional}，不用處理 EmptyResultDataAccessException</li>
 *   <li>可直接映射成 record：欄位 snake_case 自動對應 camelCase（chat_id → chatId）</li>
 *   <li>count / exists 不會回傳 null</li>
 *   <li>DEBUG 等級印出 SQL 與參數；超過 {@value #SLOW_SQL_MS} ms 的 SQL 以 WARN 記錄</li>
 * </ul>
 * 做成 Spring Bean 而不是 static 方法，才能注入 JdbcTemplate、交易也能照常運作，測試時也方便替換。
 */
@Component
public class JdbcUtil {

    private static final Logger log = LoggerFactory.getLogger(JdbcUtil.class);
    static final long SLOW_SQL_MS = 500;

    private final JdbcTemplate jdbc;

    public JdbcUtil(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 寫入
    // ------------------------------------------------------------------

    /** INSERT / UPDATE / DELETE，回傳影響筆數。 */
    public int update(String sql, Object... args) {
        return timed(sql, args, () -> jdbc.update(sql, args));
    }

    /** 批次寫入，每個 Object[] 是一組參數。 */
    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
        return timed(sql, new Object[] {batchArgs.size() + " rows"}, () -> jdbc.batchUpdate(sql, batchArgs));
    }

    // ------------------------------------------------------------------
    // 查多筆
    // ------------------------------------------------------------------

    /** 查詢多筆並映射成 record / POJO（依欄位名稱）。 */
    public <T> List<T> queryList(String sql, Class<T> type, Object... args) {
        return queryList(sql, DataClassRowMapper.newInstance(type), args);
    }

    public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
        return timed(sql, args, () -> jdbc.query(sql, mapper, args));
    }

    /** 查單一欄位的多筆值，例如 SELECT symbol FROM ... */
    public <T> List<T> queryColumn(String sql, Class<T> columnType, Object... args) {
        return queryList(sql, SingleColumnRowMapper.newInstance(columnType), args);
    }

    /** 逐筆處理，不在記憶體中建立 List（例如分組成 Map）。 */
    public void forEach(String sql, RowCallbackHandler handler, Object... args) {
        timed(sql, args, () -> {
            jdbc.query(sql, handler, args);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 查單筆
    // ------------------------------------------------------------------

    /** 查單筆並映射成 record / POJO；查無資料回傳 empty，多於一筆丟出例外。 */
    public <T> Optional<T> queryOne(String sql, Class<T> type, Object... args) {
        return queryOne(sql, DataClassRowMapper.newInstance(type), args);
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        return single(sql, queryList(sql, mapper, args));
    }

    /** 查單一欄位的單一值，例如 SELECT enabled FROM ... WHERE id = ? */
    public <T> Optional<T> queryValue(String sql, Class<T> columnType, Object... args) {
        return single(sql, queryColumn(sql, columnType, args));
    }

    /** SELECT COUNT(*) ...，不會回傳 null。 */
    public long count(String sql, Object... args) {
        return queryValue(sql, Long.class, args).orElse(0L);
    }

    /** 只要有一筆就回傳 true；SQL 寫 SELECT 1 FROM ... LIMIT 1 最有效率。 */
    public boolean exists(String sql, Object... args) {
        return !queryList(sql, (rs, i) -> Boolean.TRUE, args).isEmpty();
    }

    // ------------------------------------------------------------------

    private static <T> Optional<T> single(String sql, List<T> rows) {
        if (rows.size() > 1) {
            throw new IllegalStateException("預期最多 1 筆，實際 " + rows.size() + " 筆: " + oneLine(sql));
        }
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    private <T> T timed(String sql, Object[] args, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= SLOW_SQL_MS) {
                log.warn("慢查詢 {} ms: {} {}", ms, oneLine(sql), argsToString(args));
            } else if (log.isDebugEnabled()) {
                log.debug("{} ms: {} {}", ms, oneLine(sql), argsToString(args));
            }
        }
    }

    static String oneLine(String sql) {
        return sql.strip().replaceAll("\\s+", " ");
    }

    private static String argsToString(Object[] args) {
        return args == null || args.length == 0 ? "" : java.util.Arrays.toString(args);
    }
}
