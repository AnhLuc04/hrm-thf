package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 1 thiết bị (điện thoại / máy tính) đã bật nhận thông báo đẩy của 1 người */
@Entity
@Table(name = "push_subscription", indexes = @Index(name = "idx_push_user", columnList = "user_id"))
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 700, unique = true)
    private String endpoint;

    @Column(length = 200) private String p256dh;
    @Column(length = 100) private String auth;
    @Column(length = 300) private String userAgent;
    private LocalDateTime createdAt;
    private LocalDateTime lastOkAt;
    private Integer failCount;
    /** Lỗi lần gửi gần nhất (để chẩn đoán vì sao điện thoại không nhận) */
    @Column(length = 300) private String lastError;

    /** Tên thiết bị dễ hiểu từ user agent */
    public String getDeviceLabel() {
        String ua = userAgent == null ? "" : userAgent;
        String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac") ? "Mac" : "Thiết bị";
        String br = ua.contains("Edg/") ? "Edge" : ua.contains("Chrome") ? "Chrome" : ua.contains("Firefox") ? "Firefox"
                : ua.contains("Safari") ? "Safari" : "";
        return br.isEmpty() ? os : os + " · " + br;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getP256dh() { return p256dh; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }
    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastOkAt() { return lastOkAt; }
    public void setLastOkAt(LocalDateTime lastOkAt) { this.lastOkAt = lastOkAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }
}
