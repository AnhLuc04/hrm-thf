package com.example.hrm.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceConfirmViewDTO {

    private LocalDate date;

    // LATE / ABSENT / LEAVE / OK
    private String status;

    private LocalTime checkIn;

    private LocalTime checkOut;

    private String reason;
}