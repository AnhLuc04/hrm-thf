package com.example.hrm.dto;

import com.example.hrm.entity.AttendanceConfirmation;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ManagerDetailRequestDTO {
    private AttendanceConfirmation confirmation;
    private String employeeName; // Trường bổ sung để hiển thị trên UI
}