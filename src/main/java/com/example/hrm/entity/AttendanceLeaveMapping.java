package com.example.hrm.entity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;


@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AttendanceLeaveMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeNo;

    private LocalDate date;

    // WORK / LEAVE / HALF_LEAVE
    private String status;

    private String sessionType;
}