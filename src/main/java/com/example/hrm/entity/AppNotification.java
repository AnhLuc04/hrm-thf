package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Thông báo gửi tới 1 người (chuông trên app). */
@Entity
@Table(name = "app_notification", indexes = {
        @Index(name = "idx_notif_user", columnList = "userId,createdAt"),
        @Index(name = "idx_notif_ref", columnList = "userId,refKey")
})
@Getter
@Setter
@NoArgsConstructor
public class AppNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** ATTENDANCE / CONFIRM / COMPLAINT / PAYROLL / NEWS */
    @Column(length = 20)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    /** Bấm vào thì mở trang này (VD /m/cong) */
    @Column(length = 300)
    private String link;

    /** Khoá chống gửi trùng (VD LATE:2026-10-05); trống = không kiểm tra */
    @Column(length = 100)
    private String refKey;

    private LocalDateTime createdAt;

    private LocalDateTime readAt;

    public boolean isUnread() {
        return readAt == null;
    }
}
