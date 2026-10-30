package com.eno.stockbot.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 用假的 JdbcTemplate 驗證 JdbcUtil 對查詢結果的處理（不需要資料庫）。
 */
class JdbcUtilTest {

    /** query(sql, mapper, args) 固定回傳指定的資料列（略過 mapper）。 */
    private static JdbcUtil returning(List<?> rows) {
        JdbcTemplate fake = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                return (List<T>) rows;
            }
        };
        return new JdbcUtil(fake);
    }

    @Test
    void queryValueReturnsEmptyWhenNoRow() {
        assertEquals(Optional.empty(), returning(List.of()).queryValue("SELECT enabled FROM t", Boolean.class));
    }

    @Test
    void queryValueReturnsSingleRow() {
        assertEquals(Optional.of(true), returning(List.of(true)).queryValue("SELECT enabled FROM t", Boolean.class));
    }

    @Test
    void queryOneRejectsMultipleRows() {
        boolean thrown = false;
        try {
            returning(List.of("a", "b")).queryValue("SELECT x FROM t", String.class);
        } catch (IllegalStateException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }

    @Test
    void countNeverReturnsNull() {
        assertEquals(0L, returning(List.of()).count("SELECT COUNT(*) FROM t"));
        assertEquals(3L, returning(List.of(3L)).count("SELECT COUNT(*) FROM t"));
    }

    @Test
    void existsChecksForAnyRow() {
        assertTrue(returning(List.of(true)).exists("SELECT 1 FROM t LIMIT 1"));
        assertFalse(returning(List.of()).exists("SELECT 1 FROM t LIMIT 1"));
    }

    @Test
    void oneLineCollapsesWhitespace() {
        assertEquals("SELECT a FROM t WHERE b = ?", JdbcUtil.oneLine("""
                SELECT a
                  FROM t
                 WHERE b = ?
                """));
    }
}
