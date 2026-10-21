package com.example.hrm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * File / ảnh đính kèm của 1 tin trên BẢNG TIN.
 * File nằm trên ổ đĩa (hrm.upload-dir/news/storedName), KHÔNG để trong thư mục static:
 * tải qua /news/file/{id} để kiểm tra quyền xem tin (tin đơn vị khác không tải được).
 */
@Entity
@Table(name = "announcement_file")
public class AnnouncementFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "announcement_id")
    private Announcement announcement;

    /** Tên gốc người dùng tải lên (chỉ để hiển thị) */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** Tên lưu trên đĩa: UUID + đuôi file */
    @Column(name = "stored_name", nullable = false, length = 80)
    private String storedName;

    /** Kiểu nội dung suy ra từ ĐUÔI file đã kiểm tra (không tin kiểu trình duyệt gửi lên) */
    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    private Boolean image = false;

    public boolean isPicture() {
        return Boolean.TRUE.equals(image);
    }

    /** VD "1,2 MB" / "350 KB" */
    public String getSizeLabel() {
        long b = sizeBytes == null ? 0 : sizeBytes;
        if (b >= 1024 * 1024) {
            return String.format("%.1f MB", b / 1024.0 / 1024.0).replace('.', ',');
        }
        return Math.max(1, Math.round(b / 1024.0)) + " KB";
    }

    /** Đuôi file in hoa để làm nhãn: PDF, DOCX... */
    public String getExt() {
        int i = storedName == null ? -1 : storedName.lastIndexOf('.');
        return i < 0 ? "" : storedName.substring(i + 1).toUpperCase();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Announcement getAnnouncement() { return announcement; }
    public void setAnnouncement(Announcement announcement) { this.announcement = announcement; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Boolean getImage() { return image; }
    public void setImage(Boolean image) { this.image = image; }
}
