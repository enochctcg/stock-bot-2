package com.eno.stockbot.service.subscriber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.eno.stockbot.bean.subscriber.QuoteSnapshot;
import com.eno.stockbot.bean.subscriber.PushSchedule;

/**
 * 把一位使用者當天的歷史報價整理成 Excel，兩張工作表：
 * <ol>
 *   <li>總覽：每列一檔股票，欄位為各時間點的成交價，最後是收盤漲跌、漲跌幅</li>
 *   <li>明細：每筆紀錄一列（時間、代號、名稱、成交價、漲跌、漲跌幅），方便篩選排序</li>
 * </ol>
 * 台股慣例紅漲綠跌：價格與漲跌依當時的漲跌上色。
 */
public final class DailyExcel {

    static final String SUMMARY_SHEET = "總覽";
    static final String DETAIL_SHEET = "明細";

    private static final String PRICE_FORMAT = "#,##0.00";
    private static final String SIGNED_FORMAT = "+#,##0.00;-#,##0.00;0.00";

    private DailyExcel() {
    }

    /** 檔名，例如 股價紀錄_2026-11-03.xlsx */
    public static String fileName(LocalDate date) {
        return "股價紀錄_" + date + ".xlsx";
    }

    /**
     * @param symbols   這位使用者的觀察清單（依此順序）
     * @param snapshots 當天所有紀錄（可以包含其他人的股票，會自動過濾）
     */
    public static byte[] build(LocalDate date, List<String> symbols, List<QuoteSnapshot> snapshots) {
        Map<String, Map<LocalTime, QuoteSnapshot>> bySymbol = new HashMap<>();
        TreeSet<LocalTime> times = new TreeSet<>();
        for (QuoteSnapshot s : snapshots) {
            if (symbols.contains(s.getSymbol())) {
                bySymbol.computeIfAbsent(s.getSymbol(), k -> new HashMap<>()).put(s.getSnapshotTime(), s);
                times.add(s.getSnapshotTime());
            }
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(wb);
            summary(wb.createSheet(SUMMARY_SHEET), styles, date, symbols, new ArrayList<>(times), bySymbol);
            detail(wb.createSheet(DETAIL_SHEET), styles, symbols, new ArrayList<>(times), bySymbol);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 13:30 之後的紀錄是收盤後的最終價。 */
    static String timeLabel(LocalTime time) {
        return PushSchedule.format(time) + (time.isAfter(PushSchedule.MARKET_CLOSE) ? " 收盤" : "");
    }

    private static void summary(Sheet sheet, Styles styles, LocalDate date, List<String> symbols,
                                List<LocalTime> times, Map<String, Map<LocalTime, QuoteSnapshot>> bySymbol) {
        List<String> headers = new ArrayList<>(List.of("代號", "名稱"));
        times.forEach(t -> headers.add(timeLabel(t)));
        headers.add("漲跌");
        headers.add("漲跌幅(%)");

        Row title = sheet.createRow(0);
        text(title, 0, date + " 觀察清單報價（每 30 分鐘一筆）", styles.title);
        header(sheet.createRow(1), headers, styles);

        int r = 2;
        for (String symbol : symbols) {
            Map<LocalTime, QuoteSnapshot> byTime = bySymbol.getOrDefault(symbol, Map.of());
            Row row = sheet.createRow(r++);
            text(row, 0, symbol, styles.text);
            text(row, 1, nameOf(byTime), styles.text);

            int c = 2;
            QuoteSnapshot last = null;
            for (LocalTime t : times) {
                QuoteSnapshot s = byTime.get(t);
                number(row, c++, s == null ? null : s.getPrice(), styles.price(s == null ? null : s.getChangeAmount()));
                if (s != null && s.getPrice() != null) {
                    last = s;
                }
            }
            Double change = last == null ? null : last.getChangeAmount();
            number(row, c++, change, styles.signed(change));
            number(row, c, last == null ? null : last.getChangePercent(), styles.signed(change));
        }

        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.max(1, headers.size() - 1)));
        sheet.createFreezePane(2, 2);
        sheet.setColumnWidth(0, 9 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        for (int c = 2; c < headers.size(); c++) {
            sheet.setColumnWidth(c, 12 * 256);
        }
    }

    private static void detail(Sheet sheet, Styles styles, List<String> symbols, List<LocalTime> times,
                               Map<String, Map<LocalTime, QuoteSnapshot>> bySymbol) {
        List<String> headers = List.of("日期", "時間", "代號", "名稱", "成交價", "漲跌", "漲跌幅(%)");
        header(sheet.createRow(0), headers, styles);

        int r = 1;
        for (String symbol : symbols) {
            Map<LocalTime, QuoteSnapshot> byTime = bySymbol.getOrDefault(symbol, Map.of());
            for (LocalTime t : times) {
                QuoteSnapshot s = byTime.get(t);
                if (s == null) {
                    continue;
                }
                Row row = sheet.createRow(r++);
                text(row, 0, s.getTradeDate().toString(), styles.text);
                text(row, 1, timeLabel(t), styles.text);
                text(row, 2, s.getSymbol(), styles.text);
                text(row, 3, s.getName(), styles.text);
                number(row, 4, s.getPrice(), styles.price(s.getChangeAmount()));
                number(row, 5, s.getChangeAmount(), styles.signed(s.getChangeAmount()));
                number(row, 6, s.getChangePercent(), styles.signed(s.getChangeAmount()));
            }
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, r - 1), 0, headers.size() - 1));
        int[] widths = {12, 10, 9, 14, 12, 10, 11};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }
    }

    private static String nameOf(Map<LocalTime, QuoteSnapshot> byTime) {
        return byTime.values().stream().map(QuoteSnapshot::getName)
                .filter(n -> n != null && !n.isEmpty()).findFirst().orElse("");
    }

    private static void header(Row row, List<String> headers, Styles styles) {
        for (int c = 0; c < headers.size(); c++) {
            text(row, c, headers.get(c), styles.header);
        }
    }

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    /** 沒有資料（未記錄到、尚無成交）顯示「-」。 */
    private static void number(Row row, int col, Double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value == null) {
            cell.setCellValue("-");
        } else {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    /** 同一份檔案的樣式共用，避免每格建立一個（Excel 樣式數量有上限）。 */
    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle text;
        private final CellStyle[] price = new CellStyle[3];    // 平、漲、跌
        private final CellStyle[] signed = new CellStyle[3];

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            title = wb.createCellStyle();
            title.setFont(bold);

            header = bordered(wb);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);

            text = bordered(wb);

            short[] colors = {IndexedColors.BLACK.getIndex(), IndexedColors.RED.getIndex(), IndexedColors.GREEN.getIndex()};
            short priceFormat = wb.createDataFormat().getFormat(PRICE_FORMAT);
            short signedFormat = wb.createDataFormat().getFormat(SIGNED_FORMAT);
            for (int i = 0; i < 3; i++) {
                Font font = wb.createFont();
                font.setColor(colors[i]);
                price[i] = bordered(wb);
                price[i].setFont(font);
                price[i].setDataFormat(priceFormat);
                price[i].setAlignment(HorizontalAlignment.RIGHT);
                signed[i] = bordered(wb);
                signed[i].setFont(font);
                signed[i].setDataFormat(signedFormat);
                signed[i].setAlignment(HorizontalAlignment.RIGHT);
            }
        }

        CellStyle price(Double change) {
            return price[direction(change)];
        }

        CellStyle signed(Double change) {
            return signed[direction(change)];
        }

        private static int direction(Double change) {
            return change == null || change == 0 ? 0 : change > 0 ? 1 : 2;
        }

        private static CellStyle bordered(XSSFWorkbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }
    }
}
