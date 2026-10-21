package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Locale;

/** File trong hồ sơ nhân viên: CV, CCCD, bằng cấp, hợp đồng, ảnh thẻ... (file lưu ngoài static, tải có kiểm quyền) */
@Entity
@Table(name = "employee_document", indexes = @Index(name = "idx_empdoc_user", columnList = "user_id"))
@Getter
@Setter
public class EmployeeDocument {

    public static final java.util.Map<String, String> CATEGORIES = new java.util.LinkedHashMap<>();
    static {
        CATEGORIES.put("CV", "CV / Sơ yếu lý lịch");
        CATEGORIES.put("ANH_THE", "Ảnh thẻ");
        CATEGORIES.put("CCCD", "CCCD / CMND");
        CATEGORIES.put("BANG_CAP", "Bằng cấp, chứng chỉ");
        CATEGORIES.put("HOP_DONG", "Hợp đồng, quyết định");
        CATEGORIES.put("SUC_KHOE", "Giấy khám sức khoẻ");
        CATEGORIES.put("KHAC", "Giấy tờ khác");
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 20) private String category;
    @Column(length = 255) private String originalName;
    @Column(length = 80) private String storedName;
    @Column(length = 120) private String contentType;
    private Long sizeBytes;
    private Boolean image;
    @Column(length = 150) private String uploadedBy;
    /** true = HCNS tải lên (nhân viên không xoá được) */
    private Boolean byHr;
    private LocalDateTime uploadedAt;

    public String getCategoryLabel() {
        return CATEGORIES.getOrDefault(category, "Giấy tờ khác");
    }

    public boolean isPicture() {
        return Boolean.TRUE.equals(image);
    }

    public String getExt() {
        String n = originalName == null ? "" : originalName;
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toUpperCase(Locale.ROOT);
    }

    public String getSizeLabel() {
        long b = sizeBytes == null ? 0 : sizeBytes;
        return b >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", b / 1048576.0) : Math.max(1, b / 1024) + " KB";
    }
}
