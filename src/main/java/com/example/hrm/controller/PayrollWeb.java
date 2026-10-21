package com.example.hrm.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;

/** Tiện ích dùng chung cho các controller của module lương */
final class PayrollWeb {

    private PayrollWeb() {
    }

    /** Tên đăng nhập hiện tại (ghi nhật ký) */
    static String username(Principal principal) {
        return principal == null ? "?" : principal.getName();
    }

    /** Tháng/năm từ tham số, thiếu thì lấy tháng của 'today' */
    static YearMonth ym(Integer month, Integer year, LocalDate today) {
        if (month != null && year != null && month >= 1 && month <= 12) {
            return YearMonth.of(year, month);
        }
        return YearMonth.from(today);
    }

    /** 5000000 -> "5.000.000"; null -> "" */
    static String money(Double v) {
        if (v == null) {
            return "";
        }
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator('.');
        sym.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", sym).format(v);
    }

    /** Trả file .xlsx để tải về */
    static ResponseEntity<byte[]> xlsx(byte[] data, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }
}