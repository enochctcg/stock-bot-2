package com.eno.stockbot.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.eno.stockbot.bean.QuoteSnapshot;
import com.eno.stockbot.common.JdbcUtil;
import com.eno.stockbot.so.QuoteSnapshotSo;

class QuoteHistoryRepositoryTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-03");

    private final JdbcUtil db = mock(JdbcUtil.class);
    private final QuoteHistoryRepository repo = new QuoteHistoryRepository(db);

    @Test
    void findByDateOnly() {
        repo.find(QuoteSnapshotSo.ofDate(DAY));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(db).queryList(sql.capture(), eq(QuoteSnapshot.class), args.capture());
        assertFalse(sql.getValue().contains("IN ("));
        assertArrayEquals(new Object[] {DAY}, args.getValue());
    }

    @Test
    void findByDateAndSymbols() {
        repo.find(new QuoteSnapshotSo(DAY, List.of("2330", "2317")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(db).queryList(sql.capture(), eq(QuoteSnapshot.class), args.capture());
        assertTrue(sql.getValue().contains("AND symbol IN (?, ?) ORDER BY"));
        assertArrayEquals(new Object[] {DAY, "2330", "2317"}, args.getValue());
    }
}
