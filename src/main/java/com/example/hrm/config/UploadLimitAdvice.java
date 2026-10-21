package com.example.hrm.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;

/**
 * File tải lên vượt giới hạn (spring.servlet.multipart.*): quay lại trang trước kèm thông báo,
 * thay vì trang lỗi trắng.
 */
@ControllerAdvice
public class UploadLimitAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request) {
        FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
        flash.put("error", "File quá lớn: mỗi file tối đa 20 MB, cả lần gửi tối đa 100 MB");
        return "redirect:" + backPath(request);
    }

    /** Chỉ lấy PHẦN ĐƯỜNG DẪN của Referer (không chuyển hướng ra trang ngoài) */
    static String backPath(HttpServletRequest request) {
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        try {
            String ref = request.getHeader("Referer");
            String path = ref == null ? null : URI.create(ref).getPath();
            if (path != null && path.startsWith("/") && !path.startsWith("//")) {
                String p = path.startsWith(ctx) ? path.substring(ctx.length()) : path;
                return p.isEmpty() ? "/" : p;
            }
        } catch (RuntimeException ignore) {
            // Referer lỗi -> về trang chủ
        }
        return "/";
    }
}
