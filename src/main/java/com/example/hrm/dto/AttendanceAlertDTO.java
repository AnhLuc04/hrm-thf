package com.example.hrm.dto;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceAlertDTO {
    private String employeeNo;
    private LocalDate date;
    private String issueType;   // LATE, ABSENT, NO_CHECKOUT, LATE_EARLY, EARLY_LEAVE
    private LocalTime checkIn;
    private LocalTime checkOut;
    private String description;
    private String sessionType;  // MORNING, AFTERNOON, FULL_DAY
    private String confirmStatus; // null, PENDING, APPROVED, REJECTED

    // HẠN XÁC NHẬN CÔNG (ConfirmDeadlineService.annotate)
    private LocalDate deadline;   // gửi được đến hết ngày này; null = không giới hạn
    private boolean locked;       // quá hạn: không gửi được nữa
    private Integer daysLeft;     // còn bao nhiêu ngày (0 = hôm nay là hạn chót)
    private boolean extended;     // HCNS đã mở lại
}