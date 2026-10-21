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
import java.util.UUID;

/**
 * Lưu file HỒ SƠ NHÂN VIÊN ra ổ đĩa: hrm.upload-dir (mặc định ./data/uploads) + /employees.
 * Cùng quy tắc với file bảng tin (chỉ ảnh, PDF, Word, Excel..., tối đa 20 MB, kiểm tra ảnh thật).
 * KHÔNG để trong static: chỉ tải được qua /hr/employees/files/{id} có kiểm quyền.
 */
@Service
public class EmployeeFileStorage {

    private static final Logger log = LoggerFactory.getLogger(EmployeeFileStorage.class);

    private final Path root;
    private final NewsFileStorage rules;

    public EmployeeFileStorage(@Value("${hrm.upload-dir:./data/uploads}") String uploadDir, NewsFileStorage rules) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("employees");
        this.rules = rules;
    }

    /** lỗi (tiếng Việt) hoặc null */
    public String validate(MultipartFile f) {
        return rules.validate(f);
    }

    public NewsFileStorage.Stored save(MultipartFile f) throws IOException {
        String name = NewsFileStorage.cleanName(f.getOriginalFilename());
        String ext = NewsFileStorage.extOf(name);
        Files.createDirectories(root);
        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Đường dẫn file không hợp lệ");
        }
        try (InputStream in = f.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new NewsFileStorage.Stored(name, stored, NewsFileStorage.contentTypeOf(ext), Files.size(target),
                NewsFileStorage.isImageExt(ext));
    }

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
            log.warn("Không xoá được file hồ sơ {}: {}", storedName, e.getMessage());
        }
    }
}
