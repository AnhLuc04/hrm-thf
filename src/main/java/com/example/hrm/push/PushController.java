package com.example.hrm.push;

import com.example.hrm.entity.NotifyPreference;
import com.example.hrm.entity.PushSubscription;
import com.example.hrm.entity.User;
import com.example.hrm.repository.NotifyPreferenceRepository;
import com.example.hrm.repository.PushSubscriptionRepository;
import com.example.hrm.service.UnitScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Bật / tắt thông báo đẩy về điện thoại, đăng ký thiết bị, gửi thử */
@Controller
public class PushController {

    private final WebPushService webPush;
    private final PushSubscriptionRepository subscriptionRepository;
    private final NotifyPreferenceRepository preferenceRepository;
    private final UnitScope unitScope;

    public PushController(WebPushService webPush, PushSubscriptionRepository subscriptionRepository,
                          NotifyPreferenceRepository preferenceRepository, UnitScope unitScope) {
        this.webPush = webPush;
        this.subscriptionRepository = subscriptionRepository;
        this.preferenceRepository = preferenceRepository;
        this.unitScope = unitScope;
    }

    @GetMapping("/push/key")
    @ResponseBody
    public Map<String, Object> key() {
        return Map.of("enabled", webPush.isEnabled(), "key", webPush.isEnabled() ? webPush.publicKey() : "");
    }

    /** Trình duyệt gửi PushSubscription.toJSON(): {endpoint, keys:{p256dh, auth}} */
    @PostMapping("/push/subscribe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User me = unitScope.currentUser();
        if (me == null || !webPush.isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("ok", false));
        }
        String endpoint = str(body.get("endpoint"));
        Object keysObj = body.get("keys");
        Map<?, ?> keys = keysObj instanceof Map<?, ?> m ? m : Map.of();
        String p256dh = str(keys.get("p256dh"));
        String auth = str(keys.get("auth"));
        if (!endpoint.startsWith("https://") || endpoint.length() > 700 || p256dh.length() < 80 || p256dh.length() > 200
                || auth.length() < 16 || auth.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Dữ liệu đăng ký không hợp lệ"));
        }
        PushSubscription s = subscriptionRepository.findByEndpoint(endpoint).orElseGet(PushSubscription::new);
        s.setUserId(me.getId());                    // thiết bị đổi người đăng nhập: chuyển sang người mới
        s.setEndpoint(endpoint);
        s.setP256dh(p256dh);
        s.setAuth(auth);
        String ua = request.getHeader("User-Agent");
        s.setUserAgent(ua == null ? null : ua.length() > 300 ? ua.substring(0, 300) : ua);
        if (s.getCreatedAt() == null) {
            s.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        }
        s.setFailCount(0);
        subscriptionRepository.save(s);
        // bật lại thông báo đẩy khi người dùng chủ động bấm "Bật"
        NotifyPreference p = webPush.preference(me.getId());
        if (!p.isPushOn()) {
            p.setPushEnabled(true);
            preferenceRepository.save(p);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/push/unsubscribe")
    @ResponseBody
    public Map<String, Object> unsubscribe(@RequestBody Map<String, Object> body) {
        User me = unitScope.currentUser();
        String endpoint = str(body.get("endpoint"));
        if (me != null) {
            subscriptionRepository.findByEndpoint(endpoint)
                    .filter(s -> s.getUserId().equals(me.getId()))
                    .ifPresent(subscriptionRepository::delete);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/push/test")
    public String test(RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (me != null) {
            int n = webPush.pushTest(me.getId());
            String why = webPush.lastErrors(me.getId());
            ra.addFlashAttribute(n > 0 ? "message" : "error", n > 0
                    ? "Máy chủ đã gửi thử tới " + n + " thiết bị. Không thấy hiện trên điện thoại thì xem mục Kiểm tra bên dưới"
                    + (why.isEmpty() ? "" : ". Thiết bị lỗi: " + why)
                    : "Chưa gửi được" + (why.isEmpty() ? ": thiết bị này chưa bấm \"Bật trên thiết bị này\"" : ": " + why));
        }
        return "redirect:/notifications";
    }

    /** Bật / tắt đẩy về điện thoại + chọn loại thông báo */
    @PostMapping("/notifications/settings")
    public String settings(@RequestParam(required = false) String pushEnabled,
                           @RequestParam(value = "types", required = false) java.util.List<String> types,
                           RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        NotifyPreference p = webPush.preference(me.getId());
        p.setPushEnabled(pushEnabled != null);
        Set<String> muted = new LinkedHashSet<>(NotifyPreference.TYPES.keySet());
        if (types != null) {
            types.forEach(muted::remove);
        }
        p.setMutedTypes(muted.isEmpty() ? null : String.join(",", muted));
        preferenceRepository.save(p);
        ra.addFlashAttribute("message", p.isPushOn() ? "Đã lưu: nhận thông báo trên điện thoại / máy tính" : "Đã TẮT thông báo trên điện thoại / máy tính (chuông trong app vẫn có)");
        return "redirect:/notifications";
    }

    @PostMapping("/push/devices/{id}/delete")
    public String deleteDevice(@org.springframework.web.bind.annotation.PathVariable Long id, RedirectAttributes ra) {
        User me = unitScope.currentUser();
        if (me != null) {
            subscriptionRepository.findById(id).filter(s -> s.getUserId().equals(me.getId())).ifPresent(subscriptionRepository::delete);
            ra.addFlashAttribute("message", "Đã gỡ thiết bị");
        }
        return "redirect:/notifications";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
