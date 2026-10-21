package com.example.hrm.service;

import com.example.hrm.entity.AppNotification;
import com.example.hrm.entity.User;
import com.example.hrm.repository.AppNotificationRepository;
import com.example.hrm.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

/**
 * THÔNG BÁO (chuông trên menu). Gửi trong giao dịch RIÊNG và nuốt lỗi:
 * gửi thông báo hỏng không bao giờ làm hỏng việc chính (lưu đơn, duyệt đơn, chốt lương...).
 *
 * type: ATTENDANCE (lỗi chấm công / nhắc hạn), CONFIRM (đơn giải trình), LEAVE, PAYROLL, NEWS
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AppNotificationRepository repository;
    private final UserRepository userRepository;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.hrm.push.WebPushService webPush;   // 🔔 đẩy về điện thoại (PWA)

    public NotificationService(AppNotificationRepository repository, UserRepository userRepository,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param refKey khác null thì không gửi lại nếu người đó đã có thông báo cùng khoá (VD nhắc 1 lần / ngày lỗi)
     * @return true nếu đã gửi
     */
    public boolean send(Long userId, String type, String title, String body, String link, String refKey) {
        if (userId == null) {
            return false;
        }
        try {
            Boolean ok = tx.execute(s -> {
                if (refKey != null && repository.existsByUserIdAndRefKey(userId, refKey)) {
                    return false;
                }
                AppNotification n = new AppNotification();
                n.setUserId(userId);
                n.setType(type);
                n.setTitle(cut(title, 200));
                n.setBody(body == null ? null : cut(body, 1000));
                n.setLink(link);
                n.setRefKey(refKey == null ? null : cut(refKey, 100));
                n.setCreatedAt(LocalDateTime.now(VN));
                repository.save(n);
                return true;
            });
            if (Boolean.TRUE.equals(ok) && webPush != null) {
                webPush.pushToUser(userId, type, title, body, link);   // chạy nền, bật / tắt theo từng người
            }
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.warn("KHONG GUI DUOC THONG BAO cho user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /** Gửi theo mã nhân viên (VD người duyệt đơn); không có tài khoản thì bỏ qua */
    public boolean sendToEmployeeNo(String employeeNo, String type, String title, String body, String link, String refKey) {
        if (employeeNo == null || employeeNo.isBlank()) {
            return false;
        }
        try {
            return userRepository.findByEmployeeNo(employeeNo.trim())
                    .map(u -> send(u.getId(), type, title, body, link, refKey))
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("KHONG GUI DUOC THONG BAO cho {}: {}", employeeNo, e.getMessage());
            return false;
        }
    }

    public int sendAll(Collection<User> users, String type, String title, String body, String link, String refKey) {
        int n = 0;
        for (User u : users) {
            if (send(u.getId(), type, title, body, link, refKey)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------- đọc

    public List<AppNotification> latest(Long userId) {
        return userId == null ? List.of() : repository.findTop60ByUserIdOrderByCreatedAtDesc(userId);
    }

    public long unread(Long userId) {
        try {
            return userId == null ? 0 : repository.countByUserIdAndReadAtIsNull(userId);
        } catch (RuntimeException e) {
            return 0;   // bảng chưa tạo / lỗi DB: không làm hỏng menu
        }
    }

    /** Đánh dấu đã đọc, trả về link để mở (chỉ thông báo của chính người đó) */
    public String open(Long userId, Long id) {
        return tx.execute(s -> repository.findById(id)
                .filter(n -> n.getUserId().equals(userId))
                .map(n -> {
                    if (n.getReadAt() == null) {
                        n.setReadAt(LocalDateTime.now(VN));
                        repository.save(n);
                    }
                    return n.getLink();
                })
                .orElse(null));
    }

    public int readAll(Long userId) {
        Integer n = tx.execute(s -> repository.markAllRead(userId, LocalDateTime.now(VN)));
        return n == null ? 0 : n;
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
