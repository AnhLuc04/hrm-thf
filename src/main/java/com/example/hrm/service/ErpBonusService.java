package com.example.hrm.service;

import com.example.hrm.entity.MonthlyItemType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * =====================================================================
 *  LẤY THƯỞNG CHÍNH SÁCH TỪ SQL SERVER (Cyber ERP) - MẶC ĐỊNH TẮT
 * =====================================================================
 *  Kết nối RIÊNG (không đụng DataSource MySQL chính của HRM), khai báo trong application.properties:
 *
 *    payroll.erp.enabled=true
 *    payroll.erp.url=jdbc:sqlserver://10.0.0.5:1433;databaseName=CYBER;encrypt=false
 *    payroll.erp.username=...
 *    payroll.erp.password=...
 *    payroll.erp.amount-unit=1            # 1 = câu SQL trả về ĐỒNG, 1000 = trả về nghìn đồng
 *    payroll.erp.cache-minutes=10
 *
 *    # Mỗi loại thưởng 1 câu SQL, tham số :year :month (:from :to = ngày đầu / cuối tháng),
 *    # trả về 2 cột: ma_nv, so_tien (cột 1 = mã NV khớp users.employee_no, không khớp thì thử salary_code)
 *    payroll.erp.sql.THUONG_DICH_VU=EXEC CP_THUONG_DV @Nam=:year, @Thang=:month
 *    payroll.erp.sql.THUONG_CHINH_SACH=SELECT ma_nv, so_tien FROM v_thuong_cs WHERE nam=:year AND thang=:month
 *    payroll.erp.sql.THUONG_TVBH=...
 *    payroll.erp.sql.THUONG_KHAC=...
 *
 *  Cần thêm driver vào build.gradle:  runtimeOnly 'com.microsoft.sqlserver:mssql-jdbc'
 *
 *  Loại thưởng CÓ câu SQL -> lấy từ ERP (ưu tiên hơn số nhập tay ở "Khoản theo tháng").
 *  Lỗi kết nối -> ghi log, dùng số nhập tay, hiện cảnh báo trên trang Tính lương.
 */
@Service
public class ErpBonusService {

    private static final Logger log = LoggerFactory.getLogger(ErpBonusService.class);

    private final Environment env;

    @Value("${payroll.erp.enabled:false}")
    private boolean enabled;

    @Value("${payroll.erp.url:}")
    private String url;

    @Value("${payroll.erp.username:}")
    private String username;

    @Value("${payroll.erp.password:}")
    private String password;

    @Value("${payroll.erp.driver:com.microsoft.sqlserver.jdbc.SQLServerDriver}")
    private String driver;

    @Value("${payroll.erp.amount-unit:1}")
    private double amountUnit;

    @Value("${payroll.erp.cache-minutes:10}")
    private long cacheMinutes;

    private volatile NamedParameterJdbcTemplate jdbc;

    private record CacheEntry(long at, Map<String, Double> data) {
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Lỗi gần nhất (hiện trên trang) */
    private volatile String lastError;

    public ErpBonusService(Environment env) {
        this.env = env;
    }

    public boolean isEnabled() {
        return enabled && url != null && !url.isBlank();
    }

    public String getLastError() {
        return lastError;
    }

    public String getUsername() {
        return username;
    }

    public double getAmountUnit() {
        return amountUnit;
    }

    public long getCacheMinutes() {
        return cacheMinutes;
    }

    public boolean isFlagEnabled() {
        return enabled;
    }

    public String getUrlMasked() {
        return url == null ? "" : url.replaceAll("(?i)(password=)[^;]*", "$1***");
    }

    /** Câu SQL cấu hình cho 1 loại (null nếu không cấu hình) */
    public String sqlFor(MonthlyItemType type) {
        String s = env.getProperty("payroll.erp.sql." + type.name());
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Các loại thưởng đang lấy từ ERP */
    public Set<MonthlyItemType> erpTypes() {
        Set<MonthlyItemType> out = EnumSet.noneOf(MonthlyItemType.class);
        if (!isEnabled()) {
            return out;
        }
        for (MonthlyItemType t : MonthlyItemType.values()) {
            if (t.isErp() && sqlFor(t) != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Thưởng từ ERP của 1 tháng.
     * @return mã NV (chuẩn hoá) -> (loại -> số tiền ĐỒNG). Rỗng nếu tắt / lỗi.
     */
    public Map<String, Map<MonthlyItemType, Double>> fetchMonth(YearMonth ym) {
        Map<String, Map<MonthlyItemType, Double>> out = new HashMap<>();
        for (MonthlyItemType t : erpTypes()) {
            Map<String, Double> byEmp = fetch(t, ym, false);
            byEmp.forEach((emp, amt) -> out.computeIfAbsent(emp, k -> new EnumMap<>(MonthlyItemType.class)).put(t, amt));
        }
        return out;
    }

    /** Chạy 1 câu SQL (có cache). force = bỏ qua cache (nút "Thử" trên trang kết nối). */
    public Map<String, Double> fetch(MonthlyItemType type, YearMonth ym, boolean force) {
        String sql = sqlFor(type);
        if (!isEnabled() || sql == null) {
            return Map.of();
        }
        String key = type + "|" + ym;
        CacheEntry c = cache.get(key);
        if (!force && c != null && System.currentTimeMillis() - c.at() < cacheMinutes * 60_000) {
            return c.data();
        }
        try {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("year", ym.getYear())
                    .addValue("month", ym.getMonthValue())
                    .addValue("from", java.sql.Date.valueOf(ym.atDay(1)))
                    .addValue("to", java.sql.Date.valueOf(ym.atEndOfMonth()));
            Map<String, Double> data = new HashMap<>();
            template().query(sql, p, rs -> {
                String emp = normEmp(rs.getString(1));
                double amt = rs.getDouble(2);
                if (!emp.isEmpty()) {
                    data.merge(emp, amt * (amountUnit > 0 ? amountUnit : 1), Double::sum);
                }
            });
            cache.put(key, new CacheEntry(System.currentTimeMillis(), data));
            lastError = null;
            return data;
        } catch (RuntimeException e) {
            lastError = type + " " + ym + ": " + e.getMessage();
            log.warn("LOI LAY THUONG ERP {}", lastError);
            return c != null ? c.data() : Map.of();   // lỗi thì dùng dữ liệu cache cũ nếu có
        }
    }

    public void clearCache() {
        cache.clear();
    }

    private NamedParameterJdbcTemplate template() {
        NamedParameterJdbcTemplate t = jdbc;
        if (t == null) {
            synchronized (this) {
                if (jdbc == null) {
                    DriverManagerDataSource ds = new DriverManagerDataSource();
                    ds.setDriverClassName(driver);
                    ds.setUrl(url);
                    ds.setUsername(username);
                    ds.setPassword(password);
                    jdbc = new NamedParameterJdbcTemplate(ds);
                }
                t = jdbc;
            }
        }
        return t;
    }

    /** Chuẩn hoá mã NV để so khớp (bỏ khoảng trắng, không phân biệt hoa thường) */
    public static String normEmp(String s) {
        return s == null ? "" : s.trim().replace(" ", "").toUpperCase(Locale.ROOT);
    }
}