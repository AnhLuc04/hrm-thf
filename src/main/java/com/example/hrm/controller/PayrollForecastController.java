package com.example.hrm.controller;

import com.example.hrm.entity.User;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.service.PayrollCalculationService;
import com.example.hrm.service.PayrollCalculationService.EmployeeCalc;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.time.YearMonth;
import java.util.Map;

/**
 * XEM PHIẾU LƯƠNG DỰ KIẾN (tính ngay theo bảng công đến hôm nay, KHÔNG lưu DB),
 * hiển thị bằng đúng mẫu thẻ lương đang dùng (templates/payroll-detail.html).
 *
 *   GET /salary-forecast                                    nhân viên xem phiếu dự kiến THÁNG NÀY của chính mình
 *   GET /salary-forecast/slip?userId=&month=&year=          Kế toán / HR xem phiếu dự kiến của 1 người bất kỳ
 *
 * KHÔNG dùng tiền tố /payroll ở mức lớp để không trùng với PayrollController có sẵn.
 */
@Controller
public class PayrollForecastController {

    private final PayrollCalculationService calculationService;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.hrm.service.UnitScope unitScope;

    public PayrollForecastController(PayrollCalculationService calculationService, UserRepository userRepository) {
        this.calculationService = calculationService;
        this.userRepository = userRepository;
    }

    /** Nhân viên: phiếu lương dự kiến tháng này của chính mình */
    @GetMapping("/salary-forecast")
    public String myForecast(@RequestParam(required = false) Integer month,
                             @RequestParam(required = false) Integer year,
                             Principal principal,
                             Model model) {

        User me = currentUser(principal);
        if (me == null) {
            return "redirect:/login";
        }

        // Chỉ cho xem tháng này (và tháng trước) - không xem trước tháng tương lai
        YearMonth now = YearMonth.from(calculationService.today());
        YearMonth ym = (month != null && year != null) ? YearMonth.of(year, month) : now;
        if (ym.isAfter(now) || ym.isBefore(now.minusMonths(1))) {
            ym = now;
        }

        return render(me.getId(), ym, model, false);
    }

    /** Kế toán / HR: phiếu dự kiến của 1 nhân viên (nhớ giới hạn quyền ADMIN / HR cho /salary-forecast/slip) */
    @GetMapping("/salary-forecast/slip")
    public String slip(@RequestParam Long userId,
                       @RequestParam(required = false) Integer month,
                       @RequestParam(required = false) Integer year,
                       Model model) {
        // 🔒 ĐƠN VỊ: Kế toán / HR chỉ xem phiếu dự kiến nhân viên đơn vị mình
        User target = userRepository.findById(userId).orElse(null);
        if (target == null || !unitScope.inScope(target)) {
            return "redirect:/salary-config";
        }
        YearMonth ym = (month != null && year != null) ? YearMonth.of(year, month)
                : YearMonth.from(calculationService.today());   // không truyền tháng = tháng này
        return render(userId, ym, model, true);
    }

    private String render(Long userId, YearMonth ym, Model model, boolean forHr) {

        EmployeeCalc calc = calculationService.trace(userId, ym.getMonthValue(), ym.getYear());

        if (calc.getError() != null) {
            model.addAttribute("calc", calc);
            model.addAttribute("month", ym.getMonthValue());
            model.addAttribute("year", ym.getYear());
            model.addAttribute("forHr", forHr);
            return "salary/forecast-empty";
        }

        model.addAttribute("payroll", calc.getPayroll());
        model.addAttribute("user", calc.getUser());
        model.addAttribute("dayInfo", Map.of("duCong", PayrollCalculationService.workingDaysNumber(ym, unitScope.unitOf(calc.getUser()))));
        model.addAttribute("autoInfo", calculationService.previewMark(ym));
        model.addAttribute("preview", true);
        return "payroll-detail";
    }

    /** Người đang đăng nhập (principal là username hoặc mã nhân viên) - giống PayrollController */
    private User currentUser(Principal principal) {
        if (principal == null) {
            return null;
        }
        String login = principal.getName();
        return userRepository.findByUsername(login)
                .orElseGet(() -> userRepository.findByEmployeeNo(login).orElse(null));
    }
}