package com.eno.stockbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.eno.stockbot.bean.QuoteSnapshot;

class DailyExcelTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-03");

    private static QuoteSnapshot snap(String time, String symbol, String name, Double price, Double change, Double pct) {
        return new QuoteSnapshot(DAY, LocalTime.parse(time), symbol, name, price, change, pct);
    }

    private static final List<QuoteSnapshot> SNAPSHOTS = List.of(
            snap("09:00", "2330", "台積電", 1080.0, 10.0, 0.93),
            snap("09:00", "2317", "鴻海", 213.0, -0.5, -0.23),
            snap("09:00", "9999", "別人的股票", 50.0, 1.0, 2.0),
            snap("09:30", "2330", "台積電", 1085.0, 15.0, 1.40),
            snap("13:35", "2330", "台積電", 1090.0, 20.0, 1.87),
            snap("13:35", "2317", "鴻海", 212.0, -1.5, -0.70));

    private static Workbook read(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static String cell(Row row, int col) {
        var c = row.getCell(col);
        return switch (c.getCellType()) {
            case NUMERIC -> String.valueOf(c.getNumericCellValue());
            default -> c.getStringCellValue();
        };
    }

    @Test
    void summaryHasOneRowPerStockAndOneColumnPerTime() throws IOException {
        try (Workbook wb = read(DailyExcel.build(DAY, List.of("2330", "2317"), SNAPSHOTS))) {
            Sheet sheet = wb.getSheet(DailyExcel.SUMMARY_SHEET);

            Row header = sheet.getRow(1);
            assertEquals(List.of("代號", "名稱", "09:00", "09:30", "13:35 收盤", "漲跌", "漲跌幅(%)"),
                    List.of(cell(header, 0), cell(header, 1), cell(header, 2), cell(header, 3), cell(header, 4),
                            cell(header, 5), cell(header, 6)));

            Row tsmc = sheet.getRow(2);
            assertEquals("2330", cell(tsmc, 0));
            assertEquals("台積電", cell(tsmc, 1));
            assertEquals("1080.0", cell(tsmc, 2));
            assertEquals("1090.0", cell(tsmc, 4));
            assertEquals("20.0", cell(tsmc, 5));     // 漲跌取最後一筆
            assertEquals("1.87", cell(tsmc, 6));

            Row foxconn = sheet.getRow(3);
            assertEquals("2317", cell(foxconn, 0));
            assertEquals("-", cell(foxconn, 3));      // 09:30 沒有紀錄
            assertEquals("-1.5", cell(foxconn, 5));

            assertEquals(3, sheet.getLastRowNum());   // 別人的股票不會出現
        }
    }

    @Test
    void pricesAreRedWhenUpAndGreenWhenDown() throws IOException {
        try (Workbook wb = read(DailyExcel.build(DAY, List.of("2330", "2317"), SNAPSHOTS))) {
            Sheet sheet = wb.getSheet(DailyExcel.SUMMARY_SHEET);
            assertEquals(IndexedColors.RED.getIndex(), color(wb, sheet.getRow(2), 2));
            assertEquals(IndexedColors.GREEN.getIndex(), color(wb, sheet.getRow(3), 2));
        }
    }

    private static short color(Workbook wb, Row row, int col) {
        return wb.getFontAt(row.getCell(col).getCellStyle().getFontIndex()).getColor();
    }

    @Test
    void detailHasOneRowPerSnapshotInWatchlistOrder() throws IOException {
        try (Workbook wb = read(DailyExcel.build(DAY, List.of("2330", "2317"), SNAPSHOTS))) {
            Sheet sheet = wb.getSheet(DailyExcel.DETAIL_SHEET);
            assertEquals(5, sheet.getLastRowNum());   // 標題 + 5 筆
            assertEquals(List.of("2026-11-03", "09:00", "2330", "台積電", "1080.0"),
                    List.of(cell(sheet.getRow(1), 0), cell(sheet.getRow(1), 1), cell(sheet.getRow(1), 2),
                            cell(sheet.getRow(1), 3), cell(sheet.getRow(1), 4)));
            assertEquals("13:35 收盤", cell(sheet.getRow(3), 1));
            assertEquals("2317", cell(sheet.getRow(4), 2));
        }
    }

    @Test
    void stockWithoutTradeShowsDash() throws IOException {
        List<QuoteSnapshot> snapshots = List.of(snap("09:00", "6488", "環球晶", null, null, null));
        try (Workbook wb = read(DailyExcel.build(DAY, List.of("6488"), snapshots))) {
            Row row = wb.getSheet(DailyExcel.SUMMARY_SHEET).getRow(2);
            assertEquals("-", cell(row, 2));
            assertEquals("-", cell(row, 3));
        }
    }

    @Test
    void fileNameContainsDate() {
        assertEquals("股價紀錄_2026-11-03.xlsx", DailyExcel.fileName(DAY));
    }
}
