package com.example.hrm.formula;

/** Lỗi cú pháp hoặc lỗi khi tính công thức lương. Thông điệp bằng tiếng Việt để hiển thị thẳng cho HR. */
public class FormulaException extends RuntimeException {

    public FormulaException(String message) {
        super(message);
    }
}
