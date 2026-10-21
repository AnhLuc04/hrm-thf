package com.example.hrm.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

@Slf4j
@Component
public class HikvisionApiClient {

    /** Máy mặc định (máy cũ) - dùng khi không chọn máy của đơn vị nào */
    private static final String DEFAULT_URL = "http://172.16.3.251";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "Admin@36";

    /** Máy đang làm việc trong luồng hiện tại (mỗi đơn vị 1 máy). Đặt bằng use(...), xoá bằng clear(). */
    private static final ThreadLocal<String[]> TARGET = new ThreadLocal<>();

    /** Chọn máy chấm công cho các lệnh tiếp theo trong luồng này */
    public static void use(String url, String user, String password) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        TARGET.set(new String[]{u.isEmpty() ? DEFAULT_URL : u,
                user == null || user.isBlank() ? DEFAULT_USER : user.trim(),
                password == null ? DEFAULT_PASSWORD : password});
    }

    public static void clear() {
        TARGET.remove();
    }

    public static String currentUrl() {
        String[] t = TARGET.get();
        return t == null ? DEFAULT_URL : t[0];
    }

    private String baseUrl() {
        return currentUrl();
    }

    /*
     * =============================
     * FETCH ORGANIZATIONS (PHÒNG BAN)
     * =============================
     */
    public String fetchOrganizations(String searchID, int position, int maxResults) {

        try (CloseableHttpClient client = buildHttpClient()) {

            String url = baseUrl() + "/ISAPI/AccessControl/OrgInfo/Search?format=json";

            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");

            String body = """
        {
          "OrgInfoSearchCond": {
            "searchID": "%s",
            "searchResultPosition": %d,
            "maxResults": %d
          }
        }
        """.formatted(searchID, position, maxResults);

            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {

                return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            }

        } catch (Exception ex) {
            log.error("FETCH ORG ERROR", ex);
            return null;
        }
    }



    public String fetchUserDetail(String employeeNo) {

        try (CloseableHttpClient client = buildHttpClient()) {

            String url = baseUrl() + "/ISAPI/AccessControl/UserInfo/Detail/Search?format=json";

            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");

            String body = """
        {
          "UserInfoDetailSearchCond": {
            "employeeNo": "%s"
          }
        }
        """.formatted(employeeNo);

            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {
                return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            }

        } catch (Exception ex) {
            log.error("FETCH USER DETAIL ERROR", ex);
            return null;
        }
    }
    /*
     * =============================
     * FETCH USERS
     * =============================
     */
    public String fetchUsers(String searchID, int position, int maxResults) {
        try (CloseableHttpClient client = buildHttpClient()) {
            String url = baseUrl() + "/ISAPI/AccessControl/UserInfo/Search?format=json";

            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");

            String body = """
            {
              "UserInfoSearchCond": {
                "searchID": "%s",
                "searchResultPosition": %d,
                "maxResults": %d
              }
            }
            """.formatted(searchID, position, maxResults);

            log.info("FETCH USER BODY = {}", body);

            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String result = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                log.info("FETCH USERS STATUS = {}", status);
                log.info("FETCH USERS RESPONSE = {}", result);

                return result;
            }
        } catch (Exception ex) {
            log.error("FETCH USERS ERROR", ex);
            return null;
        }
    }

    /*
     * =============================
     * FETCH ATTENDANCE
     * =============================
     */
    public String fetchAttendance(String startTime, String endTime, int position, int maxResults) {
        try (CloseableHttpClient client = buildHttpClient()) {
            String url = baseUrl() + "/ISAPI/AccessControl/AcsEvent?format=json";

            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");

            String body = """
            {
              "AcsEventCond": {
                "searchID": "1",
                "searchResultPosition": %d,
                "maxResults": %d,
                "major": 5,
                "minor": 75,
                "startTime": "%s",
                "endTime": "%s"
              }
            }
            """.formatted(position, maxResults, startTime, endTime);

            log.info("FETCH ATTENDANCE BODY = {}", body);

            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String result = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                log.info("FETCH ATTENDANCE STATUS = {}", status);
                log.info("FETCH ATTENDANCE RESPONSE = {}", result);

                return result;
            }
        } catch (Exception ex) {
            log.error("FETCH ATTENDANCE ERROR", ex);
            return null;
        }
    }

    /*
     * =============================
     * GHI NHÂN VIÊN XUỐNG MÁY (thêm / sửa / xoá người, thẻ, ảnh khuôn mặt)
     * Vân tay vẫn phải quẹt trực tiếp trên máy.
     * =============================
     */

    /** Kết quả 1 lệnh ghi xuống máy: HTTP status + nội dung máy trả về */
    public record DeviceResult(int status, String body) {
        public boolean ok() {
            String b = body == null ? "" : body.replace(" ", "").replace("\n", "").replace("\r", "");
            return status >= 200 && status < 300 && (!b.contains("\"statusCode\"")
                    || b.contains("\"statusCode\":1,") || b.contains("\"statusCode\":1}"));
        }

        /** Mã lỗi Hikvision (VD employeeNoAlreadyExist), rỗng nếu không có */
        public String subStatus() {
            if (body == null) return "";
            int i = body.indexOf("\"subStatusCode\"");
            if (i < 0) return "";
            int a = body.indexOf('"', body.indexOf(':', i) + 1);
            int b = a < 0 ? -1 : body.indexOf('"', a + 1);
            return a < 0 || b < 0 ? "" : body.substring(a + 1, b);
        }
    }

    private DeviceResult send(HttpEntityEnclosingRequestBase req) {
        try (CloseableHttpClient client = buildHttpClient();
             CloseableHttpResponse response = client.execute(req)) {
            int status = response.getStatusLine().getStatusCode();
            String result = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            log.info("DEVICE {} {} -> {} {}", req.getMethod(), req.getURI().getPath(), status, result);
            return new DeviceResult(status, result);
        } catch (Exception ex) {
            log.error("DEVICE {} {} ERROR", req.getMethod(), req.getURI(), ex);
            return new DeviceResult(0, ex.getMessage());
        }
    }

    private DeviceResult json(HttpEntityEnclosingRequestBase req, String body) {
        req.setHeader("Content-Type", "application/json");
        req.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        return send(req);
    }

    static String js(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static String userBody(String deviceNo, String name, String gender) {
        return """
        {
          "UserInfo": {
            "employeeNo": "%s",
            "name": "%s",
            "userType": "normal",
            %s
            "Valid": { "enable": true, "beginTime": "2020-01-01T00:00:00", "endTime": "2037-12-31T23:59:59", "timeType": "local" },
            "doorRight": "1",
            "RightPlan": [ { "doorNo": 1, "planTemplateNo": "1" } ]
          }
        }
        """.formatted(js(deviceNo), js(name), gender == null ? "" : "\"gender\": \"" + gender + "\",");
    }

    /** Thêm người lên máy (gender: male / female / null) */
    public DeviceResult addUser(String deviceNo, String name, String gender) {
        return json(new HttpPost(baseUrl() + "/ISAPI/AccessControl/UserInfo/Record?format=json"), userBody(deviceNo, name, gender));
    }

    /** Sửa tên người đã có trên máy */
    public DeviceResult modifyUser(String deviceNo, String name, String gender) {
        return json(new HttpPut(baseUrl() + "/ISAPI/AccessControl/UserInfo/Modify?format=json"), userBody(deviceNo, name, gender));
    }

    /** Xoá người khỏi máy (kèm thẻ, vân tay, khuôn mặt của người đó) */
    public DeviceResult deleteUser(String deviceNo) {
        return json(new HttpPut(baseUrl() + "/ISAPI/AccessControl/UserInfoDetail/Delete?format=json"), """
        { "UserInfoDetail": { "mode": "byEmployeeNo", "EmployeeNoList": [ { "employeeNo": "%s" } ] } }
        """.formatted(js(deviceNo)));
    }

    /** Gán thẻ từ cho người trên máy */
    public DeviceResult addCard(String deviceNo, String cardNo) {
        return json(new HttpPost(baseUrl() + "/ISAPI/AccessControl/CardInfo/Record?format=json"), """
        { "CardInfo": { "employeeNo": "%s", "cardNo": "%s", "cardType": "normalCard" } }
        """.formatted(js(deviceNo), js(cardNo)));
    }

    /** Tải ảnh khuôn mặt (JPG, nên ≤ 200 KB) cho người trên máy */
    public DeviceResult uploadFace(String deviceNo, byte[] jpeg) {
        String boundary = "----hrmFace" + System.nanoTime();
        String meta = "{\"faceLibType\":\"blackFD\",\"FDID\":\"1\",\"FPID\":\"" + js(deviceNo) + "\"}";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"FaceDataRecord\"\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + meta.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n"
                    + meta + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"img\"; filename=\"face.jpg\"\r\n"
                    + "Content-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(jpeg);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return new DeviceResult(0, e.getMessage());
        }
        HttpPost post = new HttpPost(baseUrl() + "/ISAPI/Intelligent/FDLib/FaceDataRecord?format=json");
        post.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
        post.setEntity(new ByteArrayEntity(out.toByteArray()));
        return send(post);
    }

    /*
     * =============================
     * HTTP CLIENT CONFIGURATION
     * =============================
     */
    private CloseableHttpClient buildHttpClient() throws Exception {
        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true)
                .build();

        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(
                        TARGET.get() == null ? DEFAULT_USER : TARGET.get()[1],
                        TARGET.get() == null ? DEFAULT_PASSWORD : TARGET.get()[2])
        );

        return HttpClients.custom()
                .setSSLContext(sslContext)
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .setDefaultCredentialsProvider(provider)
                .build();
    }
}