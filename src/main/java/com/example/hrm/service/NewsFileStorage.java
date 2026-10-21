package com.example.hrm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lưu ảnh / file đính kèm của BẢNG TIN ra ổ đĩa.
 *   Thư mục: hrm.upload-dir (mặc định ./data/uploads) + /news
 *   Chỉ nhận các đuôi trong ALLOWED; kiểu nội dung lấy theo đuôi, không theo trình duyệt gửi lên.
 *   Ảnh kiểm tra thêm chữ ký đầu file (JPG/PNG/GIF/WEBP) để chặn file giả đuôi.
 */
@Service
public class NewsFileStorage {

    private static final Logger log = LoggerFactory.getLogger(NewsFileStorage.class);

    public static final long MAX_BYTES = 20L * 1024 * 1024;   // 20 MB / file
    public static final int MAX_FILES = 10;                    // tối đa 10 file / tin

    /** đuôi -> kiểu nội dung. SVG / HTML không nhận (có thể chứa mã chạy trên trình duyệt). */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain; charset=UTF-8"),
            Map.entry("zip", "application/zip"), Map.entry("rar", "application/vnd.rar"));

    public static final String ACCEPT = ".jpg,.jpeg,.png,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.zip,.rar";

    private final Path root;

    public NewsFileStorage(@Value("${hrm.upload-dir:./data/uploads}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("news");
    }

    /** Kết quả lưu 1 file */
    public record Stored(String originalName, String storedName, String contentType, long size, boolean image) {}

    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT).trim();
    }

    /** Kiểu nội dung theo đuôi file (null = không nhận) */
    public static String contentTypeOf(String ext) {
        return ALLOWED.get(ext);
    }

    public static boolean isImageExt(String ext) {
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("gif") || ext.equals("webp");
    }

    /** Kiểm tra trước khi lưu; trả về lỗi (tiếng Việt) hoặc null nếu hợp lệ */
    public String validate(MultipartFile f) {
        String name = cleanName(f.getOriginalFilename());
        String ext = extOf(name);
        if (!ALLOWED.containsKey(ext)) {
            return "\"" + name + "\": không nhận loại file này (chỉ ảnh, PDF, Word, Excel, PowerPoint, TXT, ZIP/RAR)";
        }
        if (f.getSize() > MAX_BYTES) {
            return "\"" + name + "\": quá 20 MB";
        }
        if (isImageExt(ext) && !looksLikeImage(f)) {
            return "\"" + name + "\": không phải file ảnh hợp lệ";
        }
        return null;
    }

    public Stored save(MultipartFile f) throws IOException {
        String name = cleanName(f.getOriginalFilename());
        String ext = extOf(name);
        Files.createDirectories(root);
        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Đường dẫn file không hợp lệ");
        }
        try (InputStream in = f.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Stored(name, stored, ALLOWED.get(ext), Files.size(target), isImageExt(ext));
    }

    /** File trên đĩa, null nếu tên lạ / không tồn tại */
    public Path resolve(String storedName) {
        if (storedName == null || !storedName.matches("[a-f0-9]{32}\\.[a-z0-9]{2,5}")) {
            return null;
        }
        Path p = root.resolve(storedName).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
    }

    public void delete(String storedName) {
        Path p = resolve(storedName);
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Không xoá được file bảng tin {}: {}", storedName, e.getMessage());
        }
    }

    /** Bỏ đường dẫn (trình duyệt cũ gửi C:\...\a.png), ký tự điều khiển, giới hạn 200 ký tự */
    public static String cleanName(String raw) {
        String n = raw == null ? "file" : raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (n.isEmpty()) {
            n = "file";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static boolean looksLikeImage(MultipartFile f) {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = f.getInputStream()) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            return false;
        }
        if (n < 4) {
            return false;
        }
        boolean jpg = (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
        boolean png = (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        boolean gif = h[0] == 'G' && h[1] == 'I' && h[2] == 'F';
        boolean webp = n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        return jpg || png || gif || webp;
    }
}
