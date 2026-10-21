package com.example.hrm.controller;

import com.example.hrm.entity.AppNotification;
import com.example.hrm.entity.User;
import com.example.hrm.service.NotificationService;
import com.example.hrm.service.UnitScope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/** 🔔 THÔNG BÁO của người đang đăng nhập (chuông trên menu) */
@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final UnitScope unitScope;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.push.WebPushService webPush;                       // thông báo đẩy về điện thoại
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.repository.PushSubscriptionRepository pushRepository;

    public NotificationController(NotificationService notificationService, UnitScope unitScope) {
        this.notificationService = notificationService;
        this.unitScope = unitScope;
    }

    @GetMapping
    public String list(Model model) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        List<AppNotification> items = notificationService.latest(me.getId());
        model.addAttribute("items", items);
        model.addAttribute("unread", items.stream().filter(AppNotification::isUnread).count());
        // BẬT / TẮT THÔNG BÁO ĐẨY về điện thoại / máy tính
        com.example.hrm.entity.NotifyPreference pref = webPush.preference(me.getId());
        model.addAttribute("pushAvailable", webPush.isEnabled());
        model.addAttribute("pushOn", pref.isPushOn());
        model.addAttribute("muted", pref.muted());
        model.addAttribute("pushTypes", com.example.hrm.entity.NotifyPreference.TYPES);
        model.addAttribute("devices", pushRepository.findByUserId(me.getId()));
        return "notifications";
    }

    /** Bấm 1 thông báo: đánh dấu đã đọc rồi mở trang liên quan (chỉ đường dẫn nội bộ) */
    @GetMapping("/{id}/open")
    public String open(@PathVariable Long id) {
        User me = unitScope.currentUser();
        if (me == null) {
            return "redirect:/login";
        }
        String link = notificationService.open(me.getId(), id);
        boolean safe = link != null && link.startsWith("/") && !link.startsWith("//") && link.matches("/[A-Za-z0-9/_\\-.?=&%]*");
        return "redirect:" + (safe ? link : "/notifications");
    }

    @PostMapping("/read-all")
    public String readAll() {
        User me = unitScope.currentUser();
        if (me != null) {
            notificationService.readAll(me.getId());
        }
        return "redirect:/notifications";
    }

    /** Số chưa đọc (menu tự cập nhật mỗi phút, không cần tải lại trang) */
    @GetMapping("/count")
    @ResponseBody
    public Map<String, Long> count() {
        User me = unitScope.currentUser();
        return Map.of("unread", me == null ? 0L : notificationService.unread(me.getId()));
    }
}
