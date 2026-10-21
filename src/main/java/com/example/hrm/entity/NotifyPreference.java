package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * BẬT / TẮT THÔNG BÁO của 1 người: có đẩy về điện thoại không, và tắt riêng từng loại.
 * Chưa có bản ghi = bật hết (chuông trong app luôn có; đẩy về máy chỉ khi người đó đã bấm "Bật" trên thiết bị).
 */
@Entity
@Table(name = "notify_preference")
public class NotifyPreference {

    public static final java.util.Map<String, String> TYPES = new java.util.LinkedHashMap<>();
    static {
        TYPES.put("ATTENDANCE", "Lỗi chấm công, nhắc hạn giải trình");
        TYPES.put("CONFIRM", "Đơn giải trình (cần duyệt / kết quả duyệt)");
        TYPES.put("LEAVE", "Đơn nghỉ phép");
        TYPES.put("PAYROLL", "Lương, phiếu lương");
        TYPES.put("NEWS", "Bảng tin");
    }

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** false = không đẩy về điện thoại / máy tính nữa (vẫn có trong chuông) */
    private Boolean pushEnabled = true;

    /** Loại KHÔNG đẩy về máy, cách nhau dấu phẩy (VD "NEWS,LEAVE") */
    @Column(length = 200)
    private String mutedTypes;

    public boolean isPushOn() {
        return !Boolean.FALSE.equals(pushEnabled);
    }

    public Set<String> muted() {
        return mutedTypes == null || mutedTypes.isBlank() ? new HashSet<>() : new HashSet<>(Arrays.asList(mutedTypes.split(",")));
    }

    public boolean wants(String type) {
        return isPushOn() && (type == null || !muted().contains(type));
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Boolean getPushEnabled() { return pushEnabled; }
    public void setPushEnabled(Boolean pushEnabled) { this.pushEnabled = pushEnabled; }
    public String getMutedTypes() { return mutedTypes; }
    public void setMutedTypes(String mutedTypes) { this.mutedTypes = mutedTypes; }
}
