package com.example.hrm.push;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * APP CÀI TRÊN ĐIỆN THOẠI (PWA): manifest, service worker, biểu tượng, trang mất mạng.
 * Phục vụ bằng controller (không qua static) vì static của dự án chỉ mở /uploads/**, và service worker phải nằm ở gốc "/".
 * File nằm trong src/main/resources/pwa/.
 */
@Controller
public class PwaController {

    @GetMapping(value = "/manifest.webmanifest")
    public ResponseEntity<byte[]> manifest() throws IOException {
        return file("pwa/manifest.webmanifest", MediaType.parseMediaType("application/manifest+json"), Duration.ofHours(1));
    }

    @GetMapping("/sw.js")
    public ResponseEntity<byte[]> serviceWorker() throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript; charset=UTF-8"))
                .cacheControl(CacheControl.noCache())          // luôn kiểm tra bản mới
                .header("Service-Worker-Allowed", "/")
                .body(read("pwa/sw.js"));
    }

    @GetMapping("/offline.html")
    public ResponseEntity<byte[]> offline() throws IOException {
        return file("pwa/offline.html", MediaType.parseMediaType("text/html; charset=UTF-8"), Duration.ofHours(1));
    }

    /**
     * Đường dẫn gốc điện thoại / trình duyệt tự tìm khi không đọc được thẻ link
     * (iPhone tìm /apple-touch-icon.png, Chrome tạo lối tắt dùng /favicon.ico).
     * Thiếu thì điện thoại tự chụp trang làm biểu tượng hoặc hiện chữ cái thay cho biểu tượng SunClub.
     */
    @GetMapping({"/apple-touch-icon.png", "/apple-touch-icon-precomposed.png"})
    public ResponseEntity<byte[]> appleTouchIcon() throws IOException {
        return file("pwa/sunclub-apple-180.png", MediaType.IMAGE_PNG, Duration.ofDays(7));
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<byte[]> favicon() throws IOException {
        return file("pwa/favicon.ico", MediaType.parseMediaType("image/x-icon"), Duration.ofDays(7));
    }

    /** File chứng chỉ gốc (CA) tự tạo, để điện thoại / máy tính cài "tin cậy" (xem HUONG-DAN-CHUNG-CHI.md) */
    @org.springframework.beans.factory.annotation.Value("${hrm.ssl.ca-cert-file:sunclub-ca.cer}")
    private String caCertFile;

    /** Đọc chứng chỉ gốc SunClub CA (file cạnh nơi chạy phần mềm, hoặc resources/pwa/sunclub-ca.cer), trả về dạng DER */
    private byte[] caCertDer() throws IOException {
        byte[] b = null;
        for (java.nio.file.Path f : caCandidates()) {
            if (java.nio.file.Files.isRegularFile(f)) {
                b = java.nio.file.Files.readAllBytes(f);
                break;
            }
        }
        if (b == null) {
            b = read("pwa/sunclub-ca.cer");
        }
        if (b == null) {
            return null;
        }
        String t = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        int s0 = t.indexOf("-----BEGIN CERTIFICATE-----");
        if (s0 >= 0) {   // keytool -rfc tạo dạng PEM (chữ) -> đổi sang DER (nhị phân) cho iPhone / Android đọc chắc chắn
            int e0 = t.indexOf("-----END CERTIFICATE-----", s0);
            String body = t.substring(s0 + 27, e0 < 0 ? t.length() : e0).replaceAll("\\s", "");
            b = java.util.Base64.getDecoder().decode(body);
        }
        return b;
    }

    /** Các chỗ tìm sunclub-ca.cer (không phân biệt chạy từ IntelliJ, Gradle hay file jar) */
    private java.util.List<java.nio.file.Path> caCandidates() {
        java.util.List<java.nio.file.Path> l = new java.util.ArrayList<>();
        for (String n : new String[]{caCertFile, "sunclub-ca.cer", "chung-chi/sunclub-ca.cer",
                "src/main/resources/pwa/sunclub-ca.cer", "hrm/sunclub-ca.cer", "hrm/chung-chi/sunclub-ca.cer",
                "../sunclub-ca.cer", "../chung-chi/sunclub-ca.cer"}) {
            if (n != null && !n.isBlank()) {
                java.nio.file.Path p = java.nio.file.Path.of(n).toAbsolutePath().normalize();
                if (!l.contains(p)) l.add(p);
            }
        }
        return l;
    }

    private ResponseEntity<byte[]> noCa() {
        StringBuilder sb = new StringBuilder("Chưa có file sunclub-ca.cer trên máy chủ. Đã tìm ở:\n");
        for (java.nio.file.Path p : caCandidates()) sb.append("  ").append(p).append('\n');
        sb.append("  (và src/main/resources/pwa/sunclub-ca.cer trong phần mềm)\n");
        sb.append("Chép sunclub-ca.cer vào 1 trong các chỗ trên rồi tải lại trang (không cần chạy lại phần mềm).");
        return ResponseEntity.status(404).contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Android / máy tính: https://<máy chủ>:8081/sunclub-ca.cer  (chứng chỉ gốc dạng DER)
     */
    @GetMapping("/sunclub-ca.cer")
    public ResponseEntity<byte[]> caCert() throws IOException {
        byte[] der = caCertDer();
        if (der == null) {
            return noCa();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-x509-ca-cert"))
                .header("Content-Disposition", "inline; filename=\"sunclub-ca.cer\"")
                .cacheControl(CacheControl.noCache())
                .body(der);
    }

    /**
     * iPhone / iPad: https://<máy chủ>:8081/sunclub.mobileconfig
     * Hồ sơ cấu hình chuẩn Apple chứa chứng chỉ gốc SunClub CA (tránh lỗi "Hồ sơ không hợp lệ" khi tải file .cer).
     */
    @GetMapping("/sunclub.mobileconfig")
    public ResponseEntity<byte[]> iosProfile(jakarta.servlet.http.HttpServletRequest request) throws IOException {
        byte[] der = caCertDer();
        if (der == null) {
            return noCa();
        }
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        String u1 = java.util.UUID.nameUUIDFromBytes(("sunclub-ca-cert" + b64).getBytes()).toString().toUpperCase();
        String u2 = java.util.UUID.nameUUIDFromBytes(("sunclub-ca-profile" + b64).getBytes()).toString().toUpperCase();
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>PayloadContent</key>
                  <array>
                    <dict>
                      <key>PayloadCertificateFileName</key><string>sunclub-ca.cer</string>
                      <key>PayloadContent</key>
                      <data>
                %s
                      </data>
                      <key>PayloadDescription</key><string>Chứng chỉ gốc SunClub CA</string>
                      <key>PayloadDisplayName</key><string>SunClub CA</string>
                      <key>PayloadIdentifier</key><string>vn.thanhhoaford.sunclub.ca.cert</string>
                      <key>PayloadType</key><string>com.apple.security.root</string>
                      <key>PayloadUUID</key><string>%s</string>
                      <key>PayloadVersion</key><integer>1</integer>
                    </dict>
                %s
                  </array>
                  <key>PayloadDescription</key><string>Cài chứng chỉ máy chủ SunClub và biểu tượng app trên màn hình chính.</string>
                  <key>PayloadDisplayName</key><string>SunClub - Chứng chỉ</string>
                  <key>PayloadIdentifier</key><string>vn.thanhhoaford.sunclub.ca</string>
                  <key>PayloadOrganization</key><string>Thanh Hoa Ford</string>
                  <key>PayloadRemovalDisallowed</key><false/>
                  <key>PayloadType</key><string>Configuration</string>
                  <key>PayloadUUID</key><string>%s</string>
                  <key>PayloadVersion</key><integer>1</integer>
                </dict>
                </plist>
                """.formatted(b64, u1, webClip(request, b64), u2);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-apple-aspen-config"))
                .header("Content-Disposition", "inline; filename=\"sunclub.mobileconfig\"")   // inline: Safari hiện ngay "tải hồ sơ cấu hình", không lưu vào Tệp
                .cacheControl(CacheControl.noCache())
                .body(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Địa chỉ app cho biểu tượng màn hình chính (mặc định lấy theo địa chỉ điện thoại đang mở) */
    @org.springframework.beans.factory.annotation.Value("${hrm.app.url:}")
    private String appUrl;

    /**
     * true (mặc định) = hồ sơ iPhone cài luôn 1 biểu tượng app (Web Clip, mở toàn màn hình).
     * Hồ sơ có mã cố định nên cài lại sẽ THAY bản cũ, không sinh thêm biểu tượng.
     * Dùng cách này thì KHÔNG "Thêm vào MH chính" từ Safari nữa (tránh 2 biểu tượng).
     */
    @org.springframework.beans.factory.annotation.Value("${hrm.ios.webclip:true}")
    private boolean iosWebClip;

    /** Tên biểu tượng app trên màn hình iPhone */
    @org.springframework.beans.factory.annotation.Value("${hrm.ios.webclip-label:SunClub}")
    private String webClipLabel;

    /**
     * Biểu tượng SunClub trên màn hình chính (Web Clip) đi kèm hồ sơ:
     * cài hồ sơ xong là có luôn biểu tượng, bấm vào mở toàn màn hình như app (không cần "Thêm vào MH chính").
     */
    private static String xmlEsc(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String webClip(jakarta.servlet.http.HttpServletRequest request, String seed) throws IOException {
        if (!iosWebClip) {
            return "";   // mặc định KHÔNG cài kèm biểu tượng: tránh 2 biểu tượng / 2 app trên iPhone
        }
        String base = appUrl == null ? "" : appUrl.trim();
        if (base.isEmpty()) {
            String host = request == null ? null : request.getHeader("Host");
            base = host == null || host.isBlank() ? "https://thfgroup.ddns.net:8081" : "https://" + host.trim();
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/";
        byte[] icon = read("pwa/sunclub-apple-180.png");
        String iconXml = icon == null ? "" : "      <key>Icon</key>\n      <data>\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(icon) + "\n      </data>\n";
        String uuid = java.util.UUID.nameUUIDFromBytes(("sunclub-webclip" + url).getBytes()).toString().toUpperCase();
        return "    <dict>\n"
                + "      <key>FullScreen</key><true/>\n"
                + "      <key>IgnoreManifestScope</key><true/>\n"   // không hiện thanh Safari khi chuyển trang (đăng nhập, chuyển hướng)
                + iconXml
                + "      <key>IsRemovable</key><true/>\n"
                + "      <key>Label</key><string>" + xmlEsc(webClipLabel == null || webClipLabel.isBlank() ? "SunClub" : webClipLabel.trim()) + "</string>\n"
                + "      <key>PayloadDescription</key><string>Biểu tượng app SunClub</string>\n"
                + "      <key>PayloadDisplayName</key><string>SunClub</string>\n"
                + "      <key>PayloadIdentifier</key><string>vn.thanhhoaford.sunclub.webclip</string>\n"
                + "      <key>PayloadType</key><string>com.apple.webClip.managed</string>\n"
                + "      <key>PayloadUUID</key><string>" + uuid + "</string>\n"
                + "      <key>PayloadVersion</key><integer>1</integer>\n"
                + "      <key>Precomposed</key><true/>\n"
                + "      <key>URL</key><string>" + url.replace("&", "&amp;") + "</string>\n"
                + "    </dict>";
    }

    @GetMapping("/pwa/{name:[a-z0-9-]+\\.png}")
    public ResponseEntity<byte[]> icon(@PathVariable String name) throws IOException {
        return file("pwa/" + name, MediaType.IMAGE_PNG, Duration.ofDays(7));
    }

    private ResponseEntity<byte[]> file(String path, MediaType type, Duration cache) throws IOException {
        byte[] b = read(path);
        if (b == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(cache).cachePublic()).body(b);
    }

    private static byte[] read(String path) throws IOException {
        ClassPathResource r = new ClassPathResource(path);
        if (!r.exists()) {
            return null;
        }
        try (InputStream in = r.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        }
    }
}
