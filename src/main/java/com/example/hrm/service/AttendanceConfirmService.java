package com.example.hrm.service;

import com.example.hrm.entity.Attendance;
import com.example.hrm.dto.AttendanceConfirmViewDTO;
import com.example.hrm.entity.LeaveRequest;
import com.example.hrm.repository.AttendanceRepository;
import com.example.hrm.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class    AttendanceConfirmService {

    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public List<AttendanceConfirmViewDTO> getConfirmList(String employeeNo,
                                                         LocalDate from,
                                                         LocalDate to) {

        List<Attendance> attendances =
                attendanceRepository.findByUser_EmployeeNoAndDateBetween(employeeNo, from, to);

        List<LeaveRequest> leaves =
                leaveRequestRepository.findByEmployeeNoAndStatus(employeeNo, "APPROVED");

        List<AttendanceConfirmViewDTO> result = new ArrayList<>();

        LocalDate date = from;

        while (!date.isAfter(to)) {

            LocalDate currentDate = date;

            DayOfWeek dow = currentDate.getDayOfWeek();

            // bỏ CN
            if (dow == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
                continue;
            }

            Attendance attendance = attendances.stream()
                    .filter(a -> a.getDate().equals(currentDate))
                    .findFirst()
                    .orElse(null);

            LeaveRequest leave = leaves.stream()
                    .filter(l ->
                            !currentDate.isBefore(l.getFromDate()) &&
                                    !currentDate.isAfter(l.getToDate())
                    )
                    .findFirst()
                    .orElse(null);

            AttendanceConfirmViewDTO dto =
                    new AttendanceConfirmViewDTO();

            dto.setDate(currentDate);

            if (leave != null) {

                dto.setStatus("LEAVE");
                dto.setReason("Xin nghỉ phép");

            } else if (attendance == null) {

                dto.setStatus("ABSENT");
                dto.setReason("Không check-in");

            } else if (attendance.getCheckInTime() != null &&
                    attendance.getCheckInTime()
                            .toLocalTime()
                            .isAfter(LocalTime.of(8, 35))) {

                dto.setStatus("LATE");

                dto.setCheckIn(
                        attendance.getCheckInTime().toLocalTime()
                );

            } else {

                dto.setStatus("OK");

                if (attendance.getCheckInTime() != null) {
                    dto.setCheckIn(
                            attendance.getCheckInTime().toLocalTime()
                    );
                }

                if (attendance.getCheckOutTime() != null) {
                    dto.setCheckOut(
                            attendance.getCheckOutTime().toLocalTime()
                    );
                }
            }

            result.add(dto);

            date = date.plusDays(1);
        }

        return result;
    }
}