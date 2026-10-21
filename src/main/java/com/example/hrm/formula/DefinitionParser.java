package com.example.hrm.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Đọc ô "Tham số" / "Biến trung gian" / "Tham số riêng": mỗi dòng một khai báo
 *
 *      tenBien = biểu thức        # ghi chú
 *
 *  Dòng trống, dòng bắt đầu bằng # hoặc // được bỏ qua.
 */
public final class DefinitionParser {

    public static final Pattern NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    private DefinitionParser() {
    }

    /**
     * @param label phần ghi chú sau dấu # (dùng làm tên hiển thị ô nhập tỉ lệ), null nếu không có
     */
    public record Definition(String name, String expression, String source, int line, String label) {

        public Definition(String name, String expression, String source, int line) {
            this(name, expression, source, line, null);
        }

        public String where() {
            return line > 0 ? "[" + source + "] dòng " + line + " (" + name + ")"
                    : "[" + source + "] " + name;
        }

        /** Tên hiển thị: ghi chú nếu có, không thì tên biến */
        public String display() {
            return label != null && !label.isBlank() ? label : name;
        }
    }

    public static List<Definition> parse(String text, String source) {
        List<Definition> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }

        String[] lines = text.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String comment = null;

            int hash = line.indexOf('#');
            if (hash >= 0) {
                comment = line.substring(hash + 1).trim();
                line = line.substring(0, hash);
            }
            line = line.trim();

            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new FormulaException("[" + source + "] dòng " + (i + 1)
                        + ": phải có dạng  tenBien = biểu thức");
            }

            String name = line.substring(0, eq).trim();
            String expr = line.substring(eq + 1).trim();

            if (!NAME.matcher(name).matches()) {
                throw new FormulaException("[" + source + "] dòng " + (i + 1)
                        + ": tên '" + name + "' không hợp lệ (chỉ gồm chữ, số, dấu _ và không bắt đầu bằng số)");
            }

            try {
                FormulaEngine.parse(expr);
            } catch (FormulaException e) {
                throw new FormulaException("[" + source + "] dòng " + (i + 1) + " (" + name + "): " + e.getMessage());
            }

            out.add(new Definition(name, expr, source, i + 1, comment));
        }

        return out;
    }
}