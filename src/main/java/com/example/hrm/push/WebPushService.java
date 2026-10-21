package com.example.hrm.push;

import com.example.hrm.entity.AppSetting;
import com.example.hrm.entity.NotifyPreference;
import com.example.hrm.entity.PushSubscription;
import com.example.hrm.repository.AppSettingRepository;
import com.example.hrm.repository.NotifyPreferenceRepository;
import com.example.hrm.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * THÔNG BÁO ĐẨY về điện thoại / máy tính (Web Push, app PWA cài trên màn hình chính).
 *   - Khoá VAPID tự tạo lần đầu, lưu trong bảng app_setting (không cần cấu hình).
 *   - Gửi nền, lỗi không ảnh hưởng việc chính; thiết bị đã gỡ / tắt quyền (404, 410) tự xoá.
 *
 * application.properties:
 *   hrm.push.enabled=true                    (false = tắt hẳn thông báo đẩy, chuông trong app vẫn có)
 *   hrm.push.subject=mailto:hcns@congty.vn   (email liên hệ gửi kèm, dịch vụ push yêu cầu)
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String K_PUBLIC = "vapid.public";
    private static final String K_PRIVATE = "vapid.private";

    private final AppSettingRepository settingRepository;
    private final PushSubscriptionRepository subscriptionRepository;
    private final NotifyPreferenceRepository preferenceRepository;

    @Value("${hrm.push.enabled:true}")
    private boolean enabled = true;

    @Value("${hrm.push.subject:mailto:hrm@localhost}")
    private String subject = "mailto:hrm@localhost";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "web-push");
        t.setDaemon(true);
        return t;
    });

    private volatile byte[] publicRaw;
    private volatile PrivateKey privateKey;

    public WebPushService(AppSettingRepository settingRepository, PushSubscriptionRepository subscriptionRepository,
                          NotifyPreferenceRepository preferenceRepository) {
        this.settingRepository = settingRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.preferenceRepository = preferenceRepository;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Khoá công khai (base64url) để trình duyệt đăng ký nhận thông báo */
    public synchronized String publicKey() {
        loadKeys();
        return WebPushCrypto.b64url(publicRaw);
    }

    private synchronized void loadKeys() {
        if (publicRaw != null && privateKey != null) {
            return;
        }
        try {
            String pub = settingRepository.findById(K_PUBLIC).map(AppSetting::getValue).orElse(null);
            String pri = settingRepository.findById(K_PRIVATE).map(AppSetting::getValue).orElse(null);
            if (pub == null || pri == null) {
                KeyPair kp = WebPushCrypto.generateKeyPair();
                pub = WebPushCrypto.b64url(WebPushCrypto.rawPublic(kp.getPublic()));
                pri = WebPushCrypto.b64url(kp.getPrivate().getEncoded());
                settingRepository.save(new AppSetting(K_PUBLIC, pub));
                settingRepository.save(new AppSetting(K_PRIVATE, pri));
                log.info("WEB PUSH: da tao khoa VAPID moi");
            }
            publicRaw = WebPushCrypto.unb64url(pub);
            privateKey = WebPushCrypto.privateFromPkcs8(WebPushCrypto.unb64url(pri));
        } catch (Exception e) {
            throw new IllegalStateException("Không tạo / đọc được khoá thông báo đẩy: " + e.getMessage(), e);
        }
    }

    public NotifyPreference preference(Long userId) {
        return preferenceRepository.findById(userId).orElseGet(() -> {
            NotifyPreference p = new NotifyPreference();
            p.setUserId(userId);
            return p;
        });
    }

    /** Đẩy 1 thông báo tới mọi thiết bị đã bật của người này (chạy nền) */
    public void pushToUser(Long userId, String type, String title, String body, String url) {
        if (!enabled || userId == null) {
            return;
        }
        executor.submit(() -> {
            try {
                if (!preference(userId).wants(type)) {
                    return;
                }
                List<PushSubscription> subs = subscriptionRepository.findByUserId(userId);
                for (PushSubscription s : subs) {
                    send(s, title, body, url, type);
                }
            } catch (Exception e) {
                log.warn("WEB PUSH loi (user {}): {}", userId, e.getMessage());
            }
        });
    }

    /** Gửi thử ngay (trả kết quả cho trang Thông báo) */
    public int pushTest(Long userId) {
        int ok = 0;
        for (PushSubscription s : subscriptionRepository.findByUserId(userId)) {
            if (send(s, "Thử thông báo SunClub", "Điện thoại / máy tính này đã nhận được thông báo.", "/notifications", "TEST")) {
                ok++;
            }
        }
        return ok;
    }

    /** Lý do lỗi gần nhất của các thiết bị (để báo trên trang) */
    public String lastErrors(Long userId) {
        return subscriptionRepository.findByUserId(userId).stream()
                .filter(s -> s.getLastError() != null)
                .map(s -> s.getDeviceLabel() + ": " + s.getLastError())
                .reduce((a, b) -> a + " | " + b).orElse("");
    }

    private boolean send(PushSubscription s, String title, String body, String url, String type) {
        try {
            loadKeys();
            String json = "{\"title\":" + q(title) + ",\"body\":" + q(body == null ? "" : body)
                    + ",\"url\":" + q(url == null ? "/notifications" : url) + ",\"tag\":" + q(type == null ? "hrm" : type) + "}";
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            if (payload.length > 3000) {
                json = "{\"title\":" + q(title) + ",\"body\":" + q(cut(body, 300)) + ",\"url\":" + q(url) + ",\"tag\":" + q(type) + "}";
                payload = json.getBytes(StandardCharsets.UTF_8);
            }
            byte[] enc = WebPushCrypto.encrypt(payload, WebPushCrypto.unb64url(s.getP256dh()), WebPushCrypto.unb64url(s.getAuth()));
            String auth = WebPushCrypto.vapidAuthorization(s.getEndpoint(), subject, privateKey, publicRaw,
                    System.currentTimeMillis() / 1000);
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.getEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", "86400")
                    .header("Urgency", "normal")
                    .header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(enc))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 404 || code == 410) {
                subscriptionRepository.delete(s);   // thiết bị đã gỡ app / tắt quyền
                return false;
            }
            if (code >= 200 && code < 300) {
                s.setLastOkAt(LocalDateTime.now(VN));
                s.setFailCount(0);
                s.setLastError(null);
                subscriptionRepository.save(s);
                return true;
            }
            log.warn("WEB PUSH {} tra ve {}: {}", host(s.getEndpoint()), code, cut(res.body(), 200));
            s.setLastError(cut("Dịch vụ " + host(s.getEndpoint()) + " trả lỗi " + code
                    + (code == 401 || code == 403 ? " (khoá VAPID / email hrm.push.subject không hợp lệ)" : "")
                    + ": " + cut(res.body(), 150), 300));
        } catch (Exception e) {
            log.warn("WEB PUSH gui loi {}: {}", host(s.getEndpoint()), e.getMessage());
            String m = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            s.setLastError(cut("Máy chủ HRM không gửi được tới " + host(s.getEndpoint())
                    + " (máy chủ không ra được Internet / bị tường lửa chặn?) - " + m, 300));
        }
        int f = s.getFailCount() == null ? 1 : s.getFailCount() + 1;
        s.setFailCount(f);
        if (f >= 20) {
            subscriptionRepository.delete(s);
        } else {
            subscriptionRepository.save(s);
        }
        return false;
    }

    private static String host(String endpoint) {
        try {
            return URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    /** Chuỗi JSON an toàn */
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
