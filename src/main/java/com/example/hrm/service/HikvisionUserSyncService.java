package com.example.hrm.service;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.repository.CompanyUnitRepository;
import com.example.hrm.repository.UserRepository;
import com.example.hrm.util.HikvisionApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HikvisionUserSyncService {

    private final HikvisionApiClient hikvisionApiClient;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CompanyUnitRepository unitRepository;   // ĐƠN VỊ: mỗi đơn vị 1 máy
    private final UnitScope unitScope;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Đồng bộ nhân viên từ MỌI máy chấm công (mỗi đơn vị 1 máy). Chưa có đơn vị nào: máy cũ như trước.
     */
    @Transactional
    public void syncUsers() {
        List<CompanyUnit> units = unitRepository.findAllByOrderByCodeAsc().stream()
                .filter(u -> u.isEnabled() && u.isHasDevice()).toList();
        if (units.isEmpty()) {
            syncUsers(null);
            return;
        }
        for (CompanyUnit unit : units) {
            syncUsers(unit);
        }
    }

    /** Đồng bộ nhân viên 1 đơn vị (null = máy cũ). Trả số nhân viên đã cập nhật. */
    @Transactional
    public int syncUsers(CompanyUnit unit) {
        int count = 0;
        int skipped = 0;   // người đã có trên phần mềm: giữ nguyên
        if (unit != null) {
            HikvisionApiClient.use(unit.getDeviceUrl(), unit.getDeviceUsername(), unit.getDevicePassword());
        }

        try {

            // ================= ORG MAP =================
            Map<String, String> orgMap = loadOrgMap();

            log.info("ORG SIZE = {}", orgMap.size());

            int position = 0;
            int maxResults = 10;
            int totalMatches = 0;
            String searchID = "1";

            while (true) {

                String json = hikvisionApiClient.fetchUsers(searchID, position, maxResults);
                if (json == null || json.isBlank()) break;

                JsonNode root = mapper.readTree(json.getBytes(StandardCharsets.UTF_8));

                JsonNode search = root.path("UserInfoSearch");
                if (search.isMissingNode())
                    search = root.path("UserInfoSearchRet");

                if (search.isMissingNode()) break;

                searchID = search.path("searchID").asText();
                totalMatches = search.path("totalMatches").asInt();

                JsonNode users = search.path("UserInfo");
                if (!users.isArray())
                    users = search.path("UserInfoList");

                int size = users.size();

                for (JsonNode node : users) {

                    String employeeNo = node.path("employeeNo").asText();
                    if (employeeNo.isBlank()) continue;

                    String fullName = node.path("name").asText();
                    if (fullName.isBlank())
                        fullName = "Hikvision_" + employeeNo;

                    log.info("USER RAW = {}", node.toPrettyString());

                    // ================= ORG ID =================
                    String orgId = resolveOrgId(node);

                    log.info("USER = {} | ORG_ID = {}", employeeNo, orgId);

                    // ================= DEPARTMENT =================
                    String department = orgMap.getOrDefault(orgId, "CHƯA PHÒNG");

                    // ĐƠN VỊ: đơn vị gốc giữ mã như máy; đơn vị khác = mã đơn vị + mã trên máy (SG41)
                    String hrmNo = unit == null ? employeeNo : unit.employeeNoFor(employeeNo);
                    User existing = userRepository.findByEmployeeNo(hrmNo).orElse(null);
                    if (existing != null && unit != null && existing.getUnit() != null
                            && !existing.getUnit().getId().equals(unit.getId())) {
                        log.warn("BO QUA [{}] ma {} dang thuoc don vi {}", unit.getCode(), hrmNo, existing.getUnit().getCode());
                        continue;
                    }
                    // NHÂN VIÊN ĐÃ CÓ: BỎ QUA HOÀN TOÀN (không đụng tên, phòng ban, chức vụ, loại NV, phép năm...)
                    // Kéo nhân viên chỉ THÊM người mới có trên máy mà phần mềm chưa có.
                    if (existing != null) {
                        skipped++;
                        continue;
                    }
                    User user = User.builder()
                                    .username(hrmNo)
                                    .employeeNo(hrmNo)
                                    .attendanceCode(employeeNo)
                                    .unit(unit != null ? unit : unitScope.legacyUnit())
                                    .password(passwordEncoder.encode("123456"))
                                    .role("USER")                   // 🔥 Quyền mặc định
                                    .employeeType("THU_VIEC")       // 🔥 THÊM MỚI: Mặc định luôn là THỬ VIỆC cho nhân sự mới quét về
                                    .departmentManager(false)       // 🔥 THÊM MỚI: Mặc định là nhân viên thường, không phải sếp
                                    .build();
                    if (user.getUnit() == null) {
                        user.setUnit(unit != null ? unit : unitScope.legacyUnit());
                    }

                    // Người MỚI: lấy tên + phòng theo máy (HCNS chia lại phòng / chức vụ ở trang Chức vụ & phòng ban)
                    user.setFullName(fullName);
                    user.setDepartment(department);

// Đảm bảo update role luôn là USER (tránh ghi đè quyền ADMIN/MANAGER nếu có)
                    if (user.getRole() == null || user.getRole().isBlank()) {
                        user.setRole("USER");
                    }

                    userRepository.save(user);

                    count++;
                    log.info("SYNC OK => {} | {}", hrmNo, department);
                }

                position += size;

                if (position >= totalMatches || size < maxResults) break;
            }

        } catch (Exception ex) {
            log.error("SYNC FAILED", ex);
        } finally {
            HikvisionApiClient.clear();
        }
        lastSkipped = skipped;
        log.info("KEO NHAN VIEN: them moi {}, bo qua {} nguoi da co", count, skipped);
        return count;
    }
    /** Số người đã có (bỏ qua) ở lần kéo gần nhất, để báo trên trang Đơn vị */
    private volatile int lastSkipped;

    public int getLastSkipped() {
        return lastSkipped;
    }

    // ================= ORG MAP =================
    private Map<String, String> loadOrgMap() {

        Map<String, String> map = new HashMap<>();

        try {

            String json = hikvisionApiClient.fetchOrganizations("1", 0, 500);
            if (json == null || json.isBlank()) return map;

            JsonNode root = mapper.readTree(json);

            JsonNode search = root.path("OrgInfoSearch");
            if (search.isMissingNode()) return map;

            JsonNode list = search.path("OrgInfo");
            if (!list.isArray()) list = search.path("OrgInfoList");

            for (JsonNode org : list) {

                String id =
                        org.path("indexCode").asText();

                if (id.isBlank()) id = org.path("orgIndexCode").asText();

                String name =
                        org.path("name").asText();

                if (name.isBlank()) name = org.path("orgName").asText();

                if (!id.isBlank()) {
                    map.put(id.trim(), name.trim());
                }
            }

            log.info("ORG MAP = {}", map);

        } catch (Exception ex) {
            log.error("ORG LOAD ERROR", ex);
        }

        return map;
    }

    // ================= ORG RESOLVE =================
    private String resolveOrgId(JsonNode node) {

        String orgId = node.path("orgIndexCode").asText();

        if (orgId.isBlank()) orgId = node.path("departmentNo").asText();
        if (orgId.isBlank()) orgId = node.path("belongGroup").asText();
        if (orgId.isBlank()) orgId = node.path("orgNo").asText();
        if (orgId.isBlank()) orgId = node.path("organizationIndexCode").asText();

        return orgId;
    }
}