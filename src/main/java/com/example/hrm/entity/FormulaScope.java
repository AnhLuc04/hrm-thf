package com.example.hrm.entity;

/**
 * Cột "scope" của bảng salary_formula.
 *
 * Từ bản "mẫu công thức": mọi công thức là MẪU, nhân viên chọn mẫu trong cấu hình lương
 * (SalaryConfig.formula). Cột scope KHÔNG còn ý nghĩa khi tính lương; bản ghi mới lưu scope = DEFAULT.
 * Giữ nguyên các giá trị cũ để không phải sửa kiểu cột trong MySQL.
 */
public enum FormulaScope {

    DEFAULT("Mẫu công thức"),
    DEPARTMENT("Phòng ban (cũ)"),
    EMPLOYEE("Nhân viên (cũ)");

    private final String label;

    FormulaScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}