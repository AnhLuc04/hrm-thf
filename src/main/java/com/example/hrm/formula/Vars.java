package com.example.hrm.formula;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bảng biến dùng khi tính công thức. Tên biến KHÔNG phân biệt hoa thường
 * (luongCoBan = LuongCoBan = LUONGCOBAN).
 */
public final class Vars {

    private final Map<String, Double> values = new HashMap<>();

    public static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public void put(String name, double value) {
        values.put(key(name), value);
    }

    public Double get(String name) {
        return values.get(key(name));
    }

    public double getOrZero(String name) {
        Double v = get(name);
        return v == null ? 0 : v;
    }

    public boolean has(String name) {
        return values.containsKey(key(name));
    }

    public Map<String, Double> asMap() {
        return Collections.unmodifiableMap(values);
    }
}