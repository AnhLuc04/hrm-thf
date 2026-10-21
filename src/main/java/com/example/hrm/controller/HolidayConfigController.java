package com.example.hrm.controller;

import com.example.hrm.entity.HolidayConfig;
import com.example.hrm.entity.User;
import com.example.hrm.repository.HolidayConfigRepository;
import com.example.hrm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/holiday")
public class HolidayConfigController {

    private final HolidayConfigRepository holidayConfigRepository;
    private final UserRepository userRepository;

    private boolean isHrDepartment(Principal principal) {
        if (principal == null) return false;
        User currentUser = userRepository.findByUsername(principal.getName()).orElse(null);
        if (currentUser == null) return false;
        String dept = currentUser.getDepartment();
        return dept != null && (dept.equalsIgnoreCase("HCNS") || dept.contains("Hành chính"));
    }

    private static String back(int year) {
        return "redirect:/holiday/config?year=" + year;
    }

    @GetMapping("/config")
    public String viewConfigPage(Principal principal,
                                 @RequestParam(required = false) Integer year,
                                 Model model) {
        if (!isHrDepartment(principal)) {
            return "redirect:/?error=no-permission";
        }

        LocalDate today = LocalDate.now();
        int thisYear = today.getYear();
        int y = (year == null || year < 2000 || year > 2100) ? thisYear : year;

        List<HolidayConfig> holidays = holidayConfigRepository
                .findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));

        List<HolidayConfig> upcoming = holidays.stream()
                .filter(h -> h.getHolidayDate() != null && !h.getHolidayDate().isBefore(today))
                .toList();
        HolidayConfig next = upcoming.isEmpty() ? null : upcoming.get(0);
        long weekendCount = holidays.stream()
                .filter(h -> h.getHolidayDate() != null && h.getHolidayDate().getDayOfWeek().getValue() >= 6)
                .count();

        model.addAttribute("holidays", holidays);
        model.addAttribute("currentYear", y);          // năm đang xem (giữ tên cũ để tương thích)
        model.addAttribute("thisYear", thisYear);      // năm hiện tại thực
        model.addAttribute("today", today);
        model.addAttribute("upcomingCount", upcoming.size());
        model.addAttribute("weekendCount", weekendCount);
        model.addAttribute("nextHoliday", next);
        model.addAttribute("daysUntilNext", next == null ? null : ChronoUnit.DAYS.between(today, next.getHolidayDate()));
        return "holiday-config";
    }

    /** Thêm mới (không có id) hoặc cập nhật (có id) một ngày nghỉ lễ */
    @PostMapping("/save")
    public String saveHoliday(Principal principal,
                              @RequestParam(required = false) Long id,
                              @RequestParam String holidayDate,
                              @RequestParam String description,
                              @RequestParam(defaultValue = "3.0") Double wageMultiplier,
                              RedirectAttributes ra) {
        if (!isHrDepartment(principal)) {
            return "redirect:/?error=no-permission";
        }

        LocalDate date;
        try {
            date = LocalDate.parse(holidayDate);
        } catch (DateTimeParseException ex) {
            ra.addFlashAttribute("error", "Ngày không hợp lệ.");
            return "redirect:/holiday/config";
        }

        String desc = description == null ? "" : description.trim();
        if (desc.isEmpty()) {
            ra.addFlashAttribute("error", "Vui lòng nhập tên dịp lễ.");
            return back(date.getYear());
        }
        if (wageMultiplier == null || wageMultiplier < 1 || wageMultiplier > 10) {
            ra.addFlashAttribute("error", "Hệ số lương phải từ 1 đến 10.");
            return back(date.getYear());
        }

        String dd = String.format("%02d/%02d/%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());

        if (id != null) {
            HolidayConfig old = holidayConfigRepository.findById(id).orElse(null);
            if (old == null) {
                ra.addFlashAttribute("error", "Không tìm thấy ngày lễ cần sửa.");
                return back(date.getYear());
            }
            if (!date.equals(old.getHolidayDate()) && holidayConfigRepository.existsByHolidayDate(date)) {
                ra.addFlashAttribute("error", "Ngày " + dd + " đã được cấu hình là ngày lễ khác.");
                return back(date.getYear());
            }
            holidayConfigRepository.save(HolidayConfig.builder()
                    .id(id)
                    .holidayDate(date)
                    .description(desc)
                    .wageMultiplier(wageMultiplier)
                    .build());
            ra.addFlashAttribute("message", "Đã cập nhật ngày lễ " + dd + ".");
            return back(date.getYear());
        }

        if (holidayConfigRepository.existsByHolidayDate(date)) {
            ra.addFlashAttribute("error", "Ngày " + dd + " đã có trong danh sách.");
            return back(date.getYear());
        }

        holidayConfigRepository.save(HolidayConfig.builder()
                .holidayDate(date)
                .description(desc)
                .wageMultiplier(wageMultiplier)
                .build());
        ra.addFlashAttribute("message", "Đã thêm ngày lễ " + dd + " – " + desc + ".");
        return back(date.getYear());
    }

    @GetMapping("/delete/{id}")
    public String deleteHoliday(Principal principal,
                                @PathVariable Long id,
                                @RequestParam(required = false) Integer year,
                                RedirectAttributes ra) {
        if (!isHrDepartment(principal)) {
            return "redirect:/?error=no-permission";
        }

        int y = year != null ? year : LocalDate.now().getYear();
        if (holidayConfigRepository.existsById(id)) {
            holidayConfigRepository.deleteById(id);
            ra.addFlashAttribute("message", "Đã xóa ngày lễ.");
        } else {
            ra.addFlashAttribute("error", "Ngày lễ không tồn tại hoặc đã bị xóa.");
        }
        return back(y);
    }
}