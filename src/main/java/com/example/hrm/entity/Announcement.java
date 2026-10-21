package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * BẢNG TIN: thông báo nội bộ do HCNS / Ban giám đốc / quản trị đăng.
 *   unit = null       -> toàn hệ thống (chỉ quản trị hệ thống đăng)
 *   department = null -> cả đơn vị; có giá trị -> chỉ phòng đó
 */
@Entity
@Table(name = "announcement")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "unit_id")
    private CompanyUnit unit;

    @Column(length = 100)
    private String department;

    private Boolean pinned = false;

    /** Hết hạn thì tự ẩn (trống = không hết hạn) */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Ảnh / file đính kèm (xoá tin thì xoá luôn) */
    @OneToMany(mappedBy = "announcement", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<AnnouncementFile> files = new ArrayList<>();

    public List<AnnouncementFile> getImages() {
        return files.stream().filter(AnnouncementFile::isPicture).toList();
    }

    public List<AnnouncementFile> getDocuments() {
        return files.stream().filter(f -> !f.isPicture()).toList();
    }

    public List<AnnouncementFile> getFiles() { return files; }
    public void setFiles(List<AnnouncementFile> files) { this.files = files; }

    public boolean isPin() {
        return Boolean.TRUE.equals(pinned);
    }

    public boolean isExpired(LocalDate today) {
        return expiresOn != null && expiresOn.isBefore(today);
    }

    /** "Toàn hệ thống" / "TH · Thanh Hóa" / "TH · Kinh Doanh 1" */
    public String getScopeLabel() {
        if (unit == null) {
            return "Toàn hệ thống";
        }
        return unit.getCode() + " · " + (department == null || department.isBlank() ? unit.getName() : department);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public CompanyUnit getUnit() { return unit; }
    public void setUnit(CompanyUnit unit) { this.unit = unit; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
