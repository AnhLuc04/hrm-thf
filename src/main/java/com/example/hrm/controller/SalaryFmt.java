package com.example.hrm.controller;

import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Định dạng số cho giao diện: dùng trong Thymeleaf bằng ${@salaryFmt.money(x)} */
@Component("salaryFmt")
public class SalaryFmt {

    private static final DecimalFormatSymbols VN;

    static {
        VN = new DecimalFormatSymbols(Locale.ROOT);
        VN.setGroupingSeparator('.');
        VN.setDecimalSeparator(',');
    }

    /** 12500000 -> "12.500.000" ; null / 0 -> "" (để bảng gọn) */
    public String money(Double v) {
        if (v == null || v == 0) {
            return "";
        }
        return new DecimalFormat("#,##0", VN).format(Math.round(v));
    }

    /** Số thường, tối đa 4 chữ số thập phân: 0.105 -> "0,105" ; 26 -> "26" */
    public String num(Double v) {
        if (v == null) {
            return "";
        }
        return new DecimalFormat("#,##0.####", VN).format(v);
    }

    /** Hiện giá trị trên trang giải trình: số lớn định dạng tiền, số nhỏ giữ thập phân */
    public String auto(Double v) {
        if (v == null) {
            return "";
        }
        if (Math.abs(v) >= 1000) {
            return new DecimalFormat("#,##0", VN).format(Math.round(v));
        }
        return num(v);
    }
}