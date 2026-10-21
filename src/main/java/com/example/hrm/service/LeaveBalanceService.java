package com.example.hrm.service;

import com.example.hrm.entity.LeaveBalance;
import com.example.hrm.entity.User;
import com.example.hrm.repository.LeaveBalanceRepository;
import com.example.hrm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaveBalanceService {

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final UserRepository userRepository;
    private final UnitScope unitScope;   // NGÀY CỘNG PHÉP theo đơn vị (HCNS cấu hình)


    // =========================================================
    // 1. TỰ ĐỘNG KIỂM TRA MỖI NGÀY
    // =========================================================
    //
    // Nếu ngày 01 mất điện:
    //   ngày 02 server bật -> tự kiểm tra
    //
    // Nếu server nghỉ nhiều ngày:
    //   ngày server bật -> tự kiểm tra
    //
    // Nếu bị thiếu nhiều tháng:
    //   hàm bên dưới sẽ bù đủ số tháng.
    //
    // =========================================================

    @Scheduled(
            cron = "0 5 0 * * *",
            zone = "Asia/Ho_Chi_Minh"
    )
    public void scheduledAccrual() {

        System.out.println("=================================================");
        System.out.println("SCHEDULER: KIỂM TRA PHÉP NĂM");
        System.out.println("=================================================");

        try {

            accrueMonthlyLeave();

        } catch (Exception e) {

            System.err.println("LỖI SCHEDULER CỘNG PHÉP:");
            e.printStackTrace();
        }
    }


    // =========================================================
    // 2. HÀM CỘNG PHÉP
    // =========================================================

    @Transactional
    public void accrueMonthlyLeave() {

        YearMonth nowMonth = YearMonth.now();
        java.time.LocalDate today = java.time.LocalDate.now();

        String nowMonthString =
                nowMonth.toString();

        System.out.println("=================================================");
        System.out.println(
                "BẮT ĐẦU ĐỒNG BỘ PHÉP THÁNG "
                        + nowMonthString
        );
        System.out.println("=================================================");


        // =====================================================
        // LẤY TOÀN BỘ NHÂN VIÊN
        // =====================================================

        List<User> allUsers =
                userRepository.findAll();

        List<LeaveBalance> balancesToSave =
                new ArrayList<>();


        int createdCount = 0;
        int accruedCount = 0;
        int skippedCount = 0;
        int probationCount = 0;


        // =====================================================
        // DUYỆT NHÂN VIÊN
        // =====================================================

        for (User user : allUsers) {

            String employeeNo =
                    user.getEmployeeNo();


            // -------------------------------------------------
            // BỎ QUA NHÂN VIÊN KHÔNG CÓ MÃ
            // -------------------------------------------------

            if (employeeNo == null
                    || employeeNo.trim().isEmpty()) {

                skippedCount++;

                continue;
            }


            employeeNo = employeeNo.trim();

            // NGÀY CỘNG PHÉP của đơn vị (HCNS cấu hình, mặc định ngày 1 như cũ):
            // chưa tới ngày cộng thì tháng này CHƯA được cộng, chỉ tính đến tháng trước
            int accrualDay = com.example.hrm.entity.CompanyUnit.leaveAccrualDayOf(unitScope.unitOf(user));
            YearMonth currentMonth = today.getDayOfMonth() >= accrualDay ? nowMonth : nowMonth.minusMonths(1);
            String currentMonthString = currentMonth.toString();


            // =================================================
            // KIỂM TRA LOẠI NHÂN VIÊN
            // =================================================

            String empType =
                    user.getEmployeeType();


            if (empType != null
                    && !empType.trim().isEmpty()) {

                String upperType =
                        empType
                                .trim()
                                .toUpperCase();


                // ---------------------------------------------
                // THỬ VIỆC
                // ---------------------------------------------

                if (upperType.contains("THU_VIEC")
                        || upperType.contains("THU VIEC")
                        || upperType.contains("THỬ VIỆC")
                        || upperType.contains("THU-VIEC")) {

                    System.out.println(
                            "-> BỎ QUA THỬ VIỆC: "
                                    + employeeNo
                                    + " | "
                                    + empType
                    );

                    probationCount++;

                    continue;
                }


                // ---------------------------------------------
                // HỌC VIỆC
                // ---------------------------------------------

                if (upperType.contains("HOC_VIEC")
                        || upperType.contains("HOC VIEC")
                        || upperType.contains("HỌC VIỆC")
                        || upperType.contains("HOC-VIEC")) {

                    System.out.println(
                            "-> BỎ QUA HỌC VIỆC: "
                                    + employeeNo
                                    + " | "
                                    + empType
                    );

                    probationCount++;

                    continue;
                }
            }


            // =================================================
            // TÌM LEAVE BALANCE
            // =================================================

            LeaveBalance balance =
                    leaveBalanceRepository
                            .findByEmployeeNo(employeeNo);


            // =================================================
            // TRƯỜNG HỢP 1:
            // CHƯA CÓ LEAVE BALANCE
            // =================================================

            if (balance == null) {

                System.out.println(
                        "-> TẠO PHÉP MỚI: "
                                + employeeNo
                );


                balance =
                        new LeaveBalance();


                balance.setEmployeeNo(
                        employeeNo
                );


                // ---------------------------------------------
                // MỖI THÁNG 1 NGÀY
                // ---------------------------------------------

                balance.setAccrualPerMonth(
                        1.0
                );


                // ---------------------------------------------
                // KHỞI TẠO PHÉP
                // ---------------------------------------------

                balance.setTotalAnnualLeave(
                        1.0
                );


                balance.setRemaining(
                        1.0
                );


                balance.setLastAccrualMonth(
                        currentMonthString
                );


                balancesToSave.add(
                        balance
                );


                createdCount++;

                continue;
            }


            // =================================================
            // TRƯỜNG HỢP 2:
            // ĐÃ CỘNG THÁNG HIỆN TẠI
            // =================================================

            String lastMonth =
                    balance.getLastAccrualMonth();


            if (currentMonthString.equals(lastMonth)) {

                skippedCount++;

                continue;
            }


            // =================================================
            // TRƯỜNG HỢP 3:
            // CHƯA CỘNG / BỊ THIẾU THÁNG
            // =================================================

            double accrualPerMonth =
                    balance.getAccrualPerMonth();


            if (accrualPerMonth <= 0) {

                // Nếu dữ liệu cũ bị 0/null logic
                // thì mặc định 1 ngày/tháng

                accrualPerMonth = 1.0;

                balance.setAccrualPerMonth(
                        accrualPerMonth
                );
            }


            // =================================================
            // XÁC ĐỊNH THÁNG BẮT ĐẦU CẦN CỘNG
            // =================================================

            YearMonth startMonth;


            if (lastMonth == null
                    || lastMonth.trim().isEmpty()) {

                /*
                 * Nếu chưa có lastAccrualMonth:
                 *
                 * Không biết chính xác đã cộng tới đâu.
                 *
                 * Để an toàn, chỉ cộng tháng hiện tại.
                 */

                startMonth =
                        currentMonth;

            } else {

                try {

                    YearMonth lastAccrual =
                            YearMonth.parse(
                                    lastMonth
                            );

                    startMonth =
                            lastAccrual.plusMonths(1);

                } catch (Exception e) {

                    /*
                     * Dữ liệu lastAccrualMonth không hợp lệ.
                     * Không tự cộng nhiều tháng để tránh cộng sai.
                     */

                    System.err.println(
                            "WARNING: lastAccrualMonth không hợp lệ của "
                                    + employeeNo
                                    + ": "
                                    + lastMonth
                    );

                    startMonth =
                            currentMonth;
                }
            }


            // =================================================
            // TÍNH SỐ THÁNG CẦN BÙ
            // =================================================

            long monthsMissing =
                    java.time.temporal.ChronoUnit.MONTHS.between(
                            startMonth,
                            currentMonth
                    ) + 1;


            if (monthsMissing <= 0) {

                skippedCount++;

                continue;
            }


            // =================================================
            // CỘNG PHÉP BÙ
            // =================================================

            double totalAccrual =
                    monthsMissing * accrualPerMonth;


            double oldTotal =
                    balance.getTotalAnnualLeave();


            double oldRemaining =
                    balance.getRemaining();


            double newTotal =
                    oldTotal + totalAccrual;


            double newRemaining =
                    oldRemaining + totalAccrual;


            balance.setTotalAnnualLeave(
                    newTotal
            );


            balance.setRemaining(
                    newRemaining
            );


            balance.setLastAccrualMonth(
                    currentMonthString
            );


            balancesToSave.add(
                    balance
            );


            accruedCount++;


            System.out.println(
                    "-> CỘNG PHÉP: "
                            + employeeNo
                            + " | "
                            + "Tháng thiếu: "
                            + monthsMissing
                            + " | "
                            + "Cộng: "
                            + totalAccrual
                            + " | "
                            + "Cũ: "
                            + oldRemaining
                            + " | "
                            + "Mới: "
                            + newRemaining
            );
        }


        // =====================================================
        // SAVE
        // =====================================================

        if (!balancesToSave.isEmpty()) {

            leaveBalanceRepository.saveAll(
                    balancesToSave
            );
        }


        // =====================================================
        // LOG KẾT QUẢ
        // =====================================================

        System.out.println("=================================================");
        System.out.println("ĐỒNG BỘ PHÉP NĂM HOÀN TẤT");
        System.out.println("=================================================");

        System.out.println(
                "Tổng nhân viên: "
                        + allUsers.size()
        );

        System.out.println(
                "Tạo mới: "
                        + createdCount
        );

        System.out.println(
                "Đã cộng/bù: "
                        + accruedCount
        );

        System.out.println(
                "Bỏ qua thử việc/học việc: "
                        + probationCount
        );

        System.out.println(
                "Không cần thay đổi: "
                        + skippedCount
        );

        System.out.println("=================================================");
    }
}