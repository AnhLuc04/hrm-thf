package com.example.hrm.service;

import com.example.hrm.entity.SalaryConfig;
import com.example.hrm.entity.SalaryFormula;
import com.example.hrm.formula.DefinitionParser;
import com.example.hrm.formula.DefinitionParser.Definition;
import com.example.hrm.formula.FormulaException;
import com.example.hrm.formula.SalaryVariables;
import com.example.hrm.formula.Vars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Công thức áp dụng cho 1 nhân viên = MẪU CÔNG THỨC nhân viên đó chọn + TỈ LỆ RIÊNG của nhân viên.
 *
 *   Tham số : tham số mặc định hệ thống -> "Tham số" của mẫu -> tỉ lệ riêng NV -> tham số riêng nâng cao
 *   Biến trung gian, hạng mục lương : lấy từ mẫu.
 *
 * Phòng ban KHÔNG quyết định công thức, chỉ là dữ liệu của nhân viên.
 */
public class FormulaChain {

    public static final String SRC_BUILTIN = "Tham số mặc định";

    public record Resolved(String expression, String source) {
    }

    private SalaryFormula template;
    private String templateSource;

    private Map<String, String> employeeRates = Map.of();
    private String employeeRatesSource;

    private String employeeParams;
    private String employeeParamsSource;

    public FormulaChain template(SalaryFormula formula) {
        this.template = formula;
        this.templateSource = formula == null ? null : "Mẫu " + SalaryFormulaService.templateName(formula);
        return this;
    }

    /** Tỉ lệ riêng + tham số riêng nâng cao của nhân viên */
    public FormulaChain employee(SalaryConfig config, String label) {
        if (config == null) {
            return this;
        }
        if (config.getTyLe() != null && !config.getTyLe().isEmpty()) {
            this.employeeRates = config.getTyLe();
            this.employeeRatesSource = "Tỉ lệ riêng NV " + label;
        }
        if (config.getThamSoRieng() != null && !config.getThamSoRieng().isBlank()) {
            this.employeeParams = config.getThamSoRieng();
            this.employeeParamsSource = "Tham số riêng NV " + label;
        }
        return this;
    }

    public SalaryFormula getTemplate() {
        return template;
    }

    public boolean hasFormula() {
        return template != null;
    }

    /** Tham số của mẫu (chưa áp tỉ lệ riêng) - dùng hiện các ô nhập tỉ lệ */
    public List<Definition> templateParams() {
        return template == null ? List.of() : DefinitionParser.parse(template.getThamSo(), templateSource + " - Tham số");
    }

    /** Tham số sau khi áp tỉ lệ riêng + tham số riêng (KHÔNG gồm tham số mặc định hệ thống) */
    public List<Definition> params() {
        Map<String, Definition> merged = new LinkedHashMap<>();

        for (Definition d : templateParams()) {
            merged.put(Vars.key(d.name()), d);
        }

        // Tỉ lệ riêng: chỉ nhận cho tham số mà mẫu có khai báo, hoặc tham số mặc định hệ thống
        for (Map.Entry<String, String> e : employeeRates.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            String k = Vars.key(e.getKey());
            if (!merged.containsKey(k) && !SalaryVariables.PARAM_KEYS.contains(k)) {
                continue;   // tham số mẫu cũ đã bỏ -> bỏ qua
            }
            Definition base = merged.get(k);
            merged.put(k, new Definition(e.getKey(), v.trim(), employeeRatesSource, 0,
                    base != null ? base.label() : SalaryVariables.label(e.getKey())));
        }

        if (employeeParams != null) {
            for (Definition d : DefinitionParser.parse(employeeParams, employeeParamsSource)) {
                merged.put(Vars.key(d.name()), d);
            }
        }

        return new ArrayList<>(merged.values());
    }

    public List<Definition> intermediates() {
        return template == null ? List.of()
                : DefinitionParser.parse(template.getBienTrungGian(), templateSource + " - Biến trung gian");
    }

    /** Công thức 1 hạng mục trong mẫu; null nếu mẫu chưa nhập */
    public Resolved item(String code) {
        if (template == null) {
            return null;
        }
        String expr = template.expression(code);
        return expr == null ? null : new Resolved(expr, templateSource);
    }

    public Resolved requireItem(SalaryVariables.Item it) {
        Resolved r = item(it.code());
        if (r == null) {
            throw new FormulaException("Hạng mục '" + it.label() + "' (" + it.code()
                    + ") chưa có công thức trong " + (templateSource == null ? "mẫu" : templateSource));
        }
        return r;
    }

    public List<SalaryVariables.Item> missingItems() {
        List<SalaryVariables.Item> out = new ArrayList<>();
        for (SalaryVariables.Item it : SalaryVariables.ITEMS) {
            if (!it.optional() && item(it.code()) == null) {
                out.add(it);
            }
        }
        return out;
    }
}