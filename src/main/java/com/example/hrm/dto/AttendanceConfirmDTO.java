package com.example.hrm.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceConfirmDTO {

    private String employeeNo;

    private LocalDate date;

    // YES / NO / HALF_CONFIRM
    private String confirmStatus;

    private String note;
}