package com.example.hrm.service;

import com.example.hrm.entity.CompanyUnit;
import com.example.hrm.entity.User;
import com.example.hrm.util.HikvisionApiClient;
import com.example.hrm.util.HikvisionApiClient.DeviceResult;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.function.Function;

/**
 * GHI NHÂN VIÊN XUỐNG MÁY CHẤM CÔNG của đơn vị (Hikvision ISAPI): thêm / sửa tên, xoá, gán thẻ, ảnh khuôn mặt.
 * Mã trên máy: đơn vị gốc = mã nhân viên; đơn vị khác = mã nhân viên bỏ tiền tố mã đơn vị (SS41 -> 41).
 * Vân tay không gửi qua mạng được: nhân viên quẹt trực tiếp trên máy.
 */
@Service
public class DeviceEnrollService {

    public record Result(boolean ok, String message) {
    }

    private final HikvisionApiClient client;
    private final UnitScope unitScope;

    public DeviceEnrollService(HikvisionApiClient client, UnitScope unitScope) {
        this.client = client;
        this.unitScope = unitScope;
    }

    /** Mã của người này trên máy chấm công */
    public String deviceNo(User u) {
        if (u.getAttendanceCode() != null && !u.getAttendanceCode().isBlank()) {
            return u.getAttendanceCode().trim();
        }
        String no = u.getEmployeeNo() == null ? "" : u.getEmployeeNo().trim();
        CompanyUnit unit = unitScope.unitOf(u);
        if (unit != null && !unit.isLegacyUnit() && unit.getCode() != null
                && no.toUpperCase().startsWith(unit.getCode().toUpperCase())) {
            return no.substring(unit.getCode().length());
        }
        return no;
    }

    /** Tên ghi lên máy: tối đa 32 ký tự (một số máy đời cũ không hiện dấu: bỏ dấu khi hrm.device.ascii-name=true) */
    static String deviceName(String fullName) {
        String n = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        return n.length() > 32 ? n.substring(0, 32) : n;
    }

    public static String noAccent(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    private <T> T onDevice(User u, Function<CompanyUnit, T> job, T noDevice) {
        CompanyUnit unit = unitScope.unitOf(u);
        if (unit == null || !unit.isHasDevice()) {
            return noDevice;
        }
        HikvisionApiClient.use(unit.getDeviceUrl(), unit.getDeviceUsername(), unit.getDevicePassword());
        try {
            return job.apply(unit);
        } finally {
            HikvisionApiClient.clear();
        }
    }

    private static Result fail(String what, DeviceResult r) {
        if (r.status() == 0) {
            return new Result(false, what + ": không kết nối được máy chấm công (" + r.body() + ")");
        }
        if (r.status() == 401) {
            return new Result(false, what + ": sai tài khoản / mật khẩu máy chấm công");
        }
        String sub = r.subStatus();
        return new Result(false, what + ": máy báo lỗi " + r.status() + (sub.isEmpty() ? "" : " (" + sub + ")"));
    }

    /** Thêm lên máy; đã có thì cập nhật tên */
    public Result pushUser(User u, String gender) {
        String no = deviceNo(u);
        if (!no.matches("[0-9A-Za-z]{1,32}")) {
            return new Result(false, "Mã trên máy \"" + no + "\" không hợp lệ (chỉ chữ, số)");
        }
        String g = "Nam".equals(gender) ? "male" : "Nữ".equals(gender) ? "female" : null;
        return onDevice(u, unit -> {
            DeviceResult r = client.addUser(no, deviceName(u.getFullName()), g);
            if (r.ok()) {
                return new Result(true, "Đã thêm " + u.getFullName() + " lên máy " + unit.getCode() + " với mã " + no
                        + ". Nhân viên ra máy quẹt vân tay / chụp khuôn mặt (hoặc tải ảnh khuôn mặt bên dưới).");
            }
            if (r.subStatus().toLowerCase().contains("exist") || r.body() != null && r.body().toLowerCase().contains("alreadyexist")) {
                DeviceResult m = client.modifyUser(no, deviceName(u.getFullName()), g);
                return m.ok() ? new Result(true, "Máy " + unit.getCode() + " đã có mã " + no + ", đã cập nhật tên " + u.getFullName())
                        : fail("Cập nhật tên trên máy", m);
            }
            return fail("Thêm lên máy", r);
        }, new Result(false, "Đơn vị chưa khai máy chấm công"));
    }

    public Result removeUser(User u) {
        String no = deviceNo(u);
        return onDevice(u, unit -> {
            DeviceResult r = client.deleteUser(no);
            return r.ok() ? new Result(true, "Đã xoá mã " + no + " khỏi máy " + unit.getCode()
                    + " (tài khoản và dữ liệu công trên phần mềm vẫn giữ)") : fail("Xoá khỏi máy", r);
        }, new Result(false, "Đơn vị chưa khai máy chấm công"));
    }

    public Result addCard(User u, String cardNo) {
        String c = cardNo == null ? "" : cardNo.trim();
        if (!c.matches("[0-9]{4,20}")) {
            return new Result(false, "Số thẻ chỉ gồm 4-20 chữ số");
        }
        String no = deviceNo(u);
        return onDevice(u, unit -> {
            DeviceResult r = client.addCard(no, c);
            return r.ok() ? new Result(true, "Đã gán thẻ " + c + " cho mã " + no + " trên máy " + unit.getCode()) : fail("Gán thẻ", r);
        }, new Result(false, "Đơn vị chưa khai máy chấm công"));
    }

    public Result uploadFace(User u, byte[] image) {
        byte[] jpg;
        try {
            jpg = toDeviceJpeg(image);
        } catch (IOException e) {
            return new Result(false, "Ảnh không đọc được: " + e.getMessage());
        }
        String no = deviceNo(u);
        return onDevice(u, unit -> {
            DeviceResult r = client.uploadFace(no, jpg);
            if (r.ok()) {
                return new Result(true, "Đã tải ảnh khuôn mặt của " + u.getFullName() + " lên máy " + unit.getCode());
            }
            String sub = r.subStatus().toLowerCase();
            if (sub.contains("face") || sub.contains("model") || sub.contains("quality")) {
                return new Result(false, "Máy không nhận ảnh (" + r.subStatus() + "): cần ảnh chính diện, rõ mặt, đủ sáng, 1 người");
            }
            return fail("Tải ảnh khuôn mặt", r);
        }, new Result(false, "Đơn vị chưa khai máy chấm công"));
    }

    /** Thu nhỏ ảnh về JPG ≤ 200 KB, cạnh dài ≤ 640px (yêu cầu thường gặp của máy Hikvision) */
    static byte[] toDeviceJpeg(byte[] src) throws IOException {
        BufferedImage in = ImageIO.read(new ByteArrayInputStream(src));
        if (in == null) {
            throw new IOException("không phải ảnh JPG / PNG");
        }
        int w = in.getWidth(), h = in.getHeight();
        double scale = Math.min(1.0, 640.0 / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale)), nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage img = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(in, 0, 0, nw, nh, null);
        g.dispose();
        for (float q = 0.9f; ; q -= 0.1f) {
            byte[] out = writeJpeg(img, q);
            if (out.length <= 200 * 1024 || q <= 0.35f) {
                return out;
            }
        }
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }
}
