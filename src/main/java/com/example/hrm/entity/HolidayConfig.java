package com.example.hrm.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "holiday_config")
public class HolidayConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate holidayDate;

    private String description;
    // BỔ SUNG: Hệ số nhân công ngày lễ (Ví dụ: 3.0 cho 300%, 4.0 cho 400%)
    @Column(nullable = false, columnDefinition = "DOUBLE DEFAULT 3.0")
    private Double wageMultiplier;
}