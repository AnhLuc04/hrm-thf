package com.example.hrm.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Trang tạm cho các chức năng đang phát triển (có sẵn đường dẫn để menu & trang chủ không bị lỗi 404).
 * Khi làm xong chức năng thật: xóa đường dẫn tương ứng khỏi @GetMapping bên dưới.
 */
@Controller
public class ComingSoonController {

    private record Soon(String key, String title, String icon, String desc, List<String> features) {}

    @GetMapping({"/contract/my", "/proposal", "/proposal/salary", "/proposal/manage"})
    public String comingSoon(HttpServletRequest request, Model model) {
        String path = request.getServletPath();

        Soon s = switch (path) {
            case "/news" -> new Soon("news", "Bảng tin", "fa-regular fa-newspaper",
                    "Nơi đăng thông báo nội bộ, tin tức và lịch sự kiện của công ty.",
                    List.of("Thông báo từ Ban giám đốc, phòng HCNS", "Ghim tin quan trọng lên đầu",
                            "Đánh dấu đã đọc, nhắc tin mới trên trang chủ"));
            case "/contract/my" -> new Soon("contract", "Hợp đồng lao động", "fa-solid fa-file-signature",
                    "Xem, tải và ký điện tử hợp đồng lao động, phụ lục hợp đồng.",
                    List.of("Danh sách hợp đồng & phụ lục của bạn", "Ký điện tử, lưu vết thời gian ký",
                            "Nhắc hạn hợp đồng sắp hết"));
            case "/proposal/salary" -> new Soon("proposal", "Đề xuất tăng lương", "fa-solid fa-arrow-trend-up",
                    "Gửi đề xuất điều chỉnh lương kèm lý do, theo dõi trạng thái duyệt.",
                    List.of("Tạo đề xuất: mức hiện tại, mức đề xuất, lý do", "Trưởng phòng → Ban giám đốc duyệt",
                            "Nhận kết quả và ghi chú của người duyệt"));
            case "/proposal/manage" -> new Soon("proposalmgr", "Duyệt đề xuất", "fa-solid fa-list-check",
                    "Nơi trưởng phòng / quản trị xét duyệt các đề xuất của nhân viên.",
                    List.of("Lọc theo loại đề xuất, phòng ban, trạng thái", "Duyệt, từ chối hoặc trả lại kèm ghi chú",
                            "Duyệt hàng loạt"));
            default -> new Soon("proposal", "Đơn từ & đề xuất", "fa-solid fa-file-pen",
                    "Tạm ứng lương, đi công tác, cấp thiết bị và các đề nghị khác.",
                    List.of("Mẫu đơn có sẵn theo từng loại", "Theo dõi trạng thái xử lý",
                            "Lịch sử các đơn đã gửi"));
        };

        model.addAttribute("pageKey", s.key());
        model.addAttribute("pageTitle", s.title());
        model.addAttribute("pageIcon", s.icon());
        model.addAttribute("pageDesc", s.desc());
        model.addAttribute("pageFeatures", s.features());
        return "coming-soon";
    }
}