package com.example.hrm.formula;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * =====================================================================
 *  BỘ MÁY TÍNH CÔNG THỨC LƯƠNG
 * =====================================================================
 *  Tự viết (không dùng SpEL / ScriptEngine) để HR nhập công thức trên web
 *  mà KHÔNG thể chạy được lệnh Java tùy ý -> an toàn.
 *
 *  Cú pháp (giống Excel):
 *    - Số:          15500000   15_500_000   0.105   10.5%   (dấu . là dấu thập phân)
 *    - Phép toán:   +  -  *  /  ^  ( )
 *    - So sánh:     =  ==  !=  <>  <  <=  >  >=      (kết quả 1 = đúng, 0 = sai)
 *    - Logic:       AND  OR  NOT   (hoặc && || !)
 *    - Tách tham số hàm: dấu phẩy "," hoặc chấm phẩy ";"
 *    - Hàm:         IF, MIN, MAX, ROUND, ROUNDUP, ROUNDDOWN, FLOOR, CEIL, ABS,
 *                   AND, OR, NOT, THUE_TNCN
 *    - Tên biến không phân biệt hoa thường, được dùng chữ có dấu.
 */
public final class FormulaEngine {

    private FormulaEngine() {
    }

    // =====================================================================
    // API
    // =====================================================================

    public interface Node {
        double eval(Vars vars);

        void collectVariables(Set<String> out);
    }

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    /** Phân tích cú pháp (có cache). Ném FormulaException nếu sai cú pháp. */
    public static Node parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new FormulaException("Công thức đang để trống");
        }
        String normalized = Normalizer.normalize(expression.trim(), Normalizer.Form.NFC);
        return CACHE.computeIfAbsent(normalized, e -> new Parser(e).parseAll());
    }

    public static double evaluate(String expression, Vars vars) {
        return parse(expression).eval(vars);
    }

    /** Danh sách tên biến mà công thức sử dụng (giữ nguyên cách viết của người nhập). */
    public static Set<String> variablesOf(String expression) {
        Set<String> out = new LinkedHashSet<>();
        parse(expression).collectVariables(out);
        return out;
    }

    // =====================================================================
    // THUẾ TNCN - biểu lũy tiến 5 bậc (thu nhập tiền lương, từ kỳ tính thuế 2026)
    // =====================================================================

    private static final double[][] TAX_BRACKETS = {
            {10_000_000, 0.05},
            {30_000_000, 0.10},
            {60_000_000, 0.20},
            {100_000_000, 0.30},
            {Double.MAX_VALUE, 0.35}
    };

    public static double thueTncn(double thuNhapTinhThue) {
        double tax = 0;
        double lower = 0;
        for (double[] b : TAX_BRACKETS) {
            if (thuNhapTinhThue <= lower) {
                break;
            }
            tax += (Math.min(thuNhapTinhThue, b[0]) - lower) * b[1];
            lower = b[0];
        }
        return tax;
    }

    // =====================================================================
    // TOKENIZER
    // =====================================================================

    private enum T { NUM, ID, OP, LP, RP, SEP, END }

    private record Tok(T type, String text, double num, int pos) {
    }

    private static final Set<String> TWO_CHAR_OPS = Set.of("<=", ">=", "==", "!=", "<>", "&&", "||");

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();

        while (i < n) {
            char c = s.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            int start = i;

            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                StringBuilder sb = new StringBuilder();
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) {
                    if (s.charAt(i) != '_') {
                        sb.append(s.charAt(i));
                    }
                    i++;
                }
                double v;
                try {
                    v = Double.parseDouble(sb.toString());
                } catch (NumberFormatException e) {
                    throw error("Số không hợp lệ '" + sb + "' (dùng dấu chấm cho phần thập phân, VD 0.105)", start);
                }
                if (i < n && s.charAt(i) == '%') {
                    v = v / 100;
                    i++;
                }
                out.add(new Tok(T.NUM, s.substring(start, i), v, start));
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Tok(T.ID, s.substring(start, i), 0, start));
                continue;
            }

            if (c == '(') {
                out.add(new Tok(T.LP, "(", 0, i++));
                continue;
            }
            if (c == ')') {
                out.add(new Tok(T.RP, ")", 0, i++));
                continue;
            }
            if (c == ',' || c == ';') {
                out.add(new Tok(T.SEP, String.valueOf(c), 0, i++));
                continue;
            }

            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (TWO_CHAR_OPS.contains(two)) {
                out.add(new Tok(T.OP, two, 0, i));
                i += 2;
                continue;
            }
            if ("+-*/^<>=!".indexOf(c) >= 0) {
                out.add(new Tok(T.OP, String.valueOf(c), 0, i++));
                continue;
            }

            throw error("Ký tự không hợp lệ '" + c + "'", i);
        }

        out.add(new Tok(T.END, "", 0, n));
        return out;
    }

    private static FormulaException error(String msg, int pos) {
        return new FormulaException(msg + " (vị trí " + (pos + 1) + ")");
    }

    // =====================================================================
    // PARSER (đệ quy xuống, ưu tiên: OR < AND < so sánh < +- < */ < ^ < một ngôi)
    // =====================================================================

    private static final class Parser {

        private final List<Tok> toks;
        private int p = 0;

        Parser(String src) {
            this.toks = tokenize(src);
        }

        Node parseAll() {
            Node n = or();
            if (peek().type() == T.SEP) {
                throw error("Dấu '" + peek().text() + "' chỉ dùng để ngăn tham số của hàm; số thập phân viết bằng dấu chấm (VD 1.5)", peek().pos());
            }
            if (peek().type() != T.END) {
                throw error("Thừa '" + peek().text() + "' hoặc thiếu toán tử", peek().pos());
            }
            return n;
        }

        private Tok peek() {
            return toks.get(p);
        }

        private Tok next() {
            return toks.get(p++);
        }

        private boolean acceptOp(String op) {
            if (peek().type() == T.OP && peek().text().equals(op)) {
                p++;
                return true;
            }
            return false;
        }

        private boolean acceptKeyword(String kw) {
            Tok t = peek();
            if (t.type() == T.ID && t.text().equalsIgnoreCase(kw)
                    && toks.get(p + 1).type() != T.LP) {
                p++;
                return true;
            }
            return false;
        }

        private Node or() {
            Node left = and();
            while (acceptOp("||") || acceptKeyword("OR")) {
                left = new Binary("OR", left, and());
            }
            return left;
        }

        private Node and() {
            Node left = comparison();
            while (acceptOp("&&") || acceptKeyword("AND")) {
                left = new Binary("AND", left, comparison());
            }
            return left;
        }

        private Node comparison() {
            Node left = additive();
            Tok t = peek();
            if (t.type() == T.OP) {
                String op = switch (t.text()) {
                    case "=", "==" -> "=";
                    case "!=", "<>" -> "!=";
                    case "<", "<=", ">", ">=" -> t.text();
                    default -> null;
                };
                if (op != null) {
                    p++;
                    return new Binary(op, left, additive());
                }
            }
            return left;
        }

        private Node additive() {
            Node left = multiplicative();
            while (true) {
                if (acceptOp("+")) {
                    left = new Binary("+", left, multiplicative());
                } else if (acceptOp("-")) {
                    left = new Binary("-", left, multiplicative());
                } else {
                    return left;
                }
            }
        }

        private Node multiplicative() {
            Node left = unary();
            while (true) {
                if (acceptOp("*")) {
                    left = new Binary("*", left, unary());
                } else if (acceptOp("/")) {
                    left = new Binary("/", left, unary());
                } else {
                    return left;
                }
            }
        }

        private Node unary() {
            if (acceptOp("-")) {
                return new Negate(unary());
            }
            if (acceptOp("+")) {
                return unary();
            }
            if (acceptOp("!") || acceptKeyword("NOT")) {
                return new Not(unary());
            }
            return power();
        }

        private Node power() {
            Node base = primary();
            if (acceptOp("^")) {
                return new Binary("^", base, unary());
            }
            return base;
        }

        private Node primary() {
            Tok t = next();

            switch (t.type()) {
                case NUM:
                    return new Num(t.num());

                case LP: {
                    Node inner = or();
                    if (peek().type() != T.RP) {
                        throw error("Thiếu dấu ')'", peek().pos());
                    }
                    p++;
                    return inner;
                }

                case ID: {
                    if (peek().type() == T.LP) {
                        p++;
                        List<Node> args = new ArrayList<>();
                        if (peek().type() != T.RP) {
                            args.add(or());
                            while (peek().type() == T.SEP) {
                                p++;
                                args.add(or());
                            }
                        }
                        if (peek().type() != T.RP) {
                            throw error("Thiếu dấu ')' khi gọi hàm " + t.text(), peek().pos());
                        }
                        p++;
                        return Call.create(t.text(), args, t.pos());
                    }
                    if (t.text().equalsIgnoreCase("TRUE")) {
                        return new Num(1);
                    }
                    if (t.text().equalsIgnoreCase("FALSE")) {
                        return new Num(0);
                    }
                    return new Var(t.text());
                }

                case END:
                    throw error("Công thức bị cụt, thiếu giá trị ở cuối", t.pos());

                default:
                    throw error("Không mong đợi '" + t.text() + "'", t.pos());
            }
        }
    }

    // =====================================================================
    // AST
    // =====================================================================

    private static double checked(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new FormulaException("Kết quả không hợp lệ (NaN/vô cực)");
        }
        return v;
    }

    private static boolean truthy(double v) {
        return Math.abs(v) > 1e-12;
    }

    private record Num(double value) implements Node {
        public double eval(Vars vars) {
            return value;
        }

        public void collectVariables(Set<String> out) {
        }
    }

    private record Var(String name) implements Node {
        public double eval(Vars vars) {
            Double v = vars.get(name);
            if (v == null) {
                throw new FormulaException("Biến '" + name + "' chưa được định nghĩa");
            }
            return v;
        }

        public void collectVariables(Set<String> out) {
            out.add(name);
        }
    }

    private record Negate(Node inner) implements Node {
        public double eval(Vars vars) {
            return -inner.eval(vars);
        }

        public void collectVariables(Set<String> out) {
            inner.collectVariables(out);
        }
    }

    private record Not(Node inner) implements Node {
        public double eval(Vars vars) {
            return truthy(inner.eval(vars)) ? 0 : 1;
        }

        public void collectVariables(Set<String> out) {
            inner.collectVariables(out);
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {

        public double eval(Vars vars) {
            if (op.equals("AND")) {
                return truthy(left.eval(vars)) && truthy(right.eval(vars)) ? 1 : 0;
            }
            if (op.equals("OR")) {
                return truthy(left.eval(vars)) || truthy(right.eval(vars)) ? 1 : 0;
            }

            double a = left.eval(vars);
            double b = right.eval(vars);

            return switch (op) {
                case "+" -> checked(a + b);
                case "-" -> checked(a - b);
                case "*" -> checked(a * b);
                case "/" -> {
                    if (b == 0) {
                        throw new FormulaException("Chia cho 0 (dùng IF(mẫu > 0, tử / mẫu, 0) để tránh)");
                    }
                    yield checked(a / b);
                }
                case "^" -> checked(Math.pow(a, b));
                case "=" -> Math.abs(a - b) < 1e-9 ? 1 : 0;
                case "!=" -> Math.abs(a - b) >= 1e-9 ? 1 : 0;
                case "<" -> a < b ? 1 : 0;
                case "<=" -> a <= b ? 1 : 0;
                case ">" -> a > b ? 1 : 0;
                case ">=" -> a >= b ? 1 : 0;
                default -> throw new FormulaException("Toán tử không hỗ trợ: " + op);
            };
        }

        public void collectVariables(Set<String> out) {
            left.collectVariables(out);
            right.collectVariables(out);
        }
    }

    private record Call(String fn, List<Node> args) implements Node {

        /** tên hàm -> {số tham số tối thiểu, tối đa} (-1 = không giới hạn) */
        private static final Map<String, int[]> ARITY = Map.ofEntries(
                Map.entry("IF", new int[]{2, 3}),
                Map.entry("MIN", new int[]{1, -1}),
                Map.entry("MAX", new int[]{1, -1}),
                Map.entry("ROUND", new int[]{1, 2}),
                Map.entry("ROUNDUP", new int[]{1, 2}),
                Map.entry("ROUNDDOWN", new int[]{1, 2}),
                Map.entry("FLOOR", new int[]{1, 1}),
                Map.entry("CEIL", new int[]{1, 1}),
                Map.entry("ABS", new int[]{1, 1}),
                Map.entry("AND", new int[]{1, -1}),
                Map.entry("OR", new int[]{1, -1}),
                Map.entry("NOT", new int[]{1, 1}),
                Map.entry("THUE_TNCN", new int[]{1, 1})
        );

        static Call create(String name, List<Node> args, int pos) {
            String fn = name.toUpperCase(Locale.ROOT);
            int[] arity = ARITY.get(fn);
            if (arity == null) {
                throw error("Không có hàm '" + name + "'. Các hàm hỗ trợ: " + String.join(", ", new java.util.TreeSet<>(ARITY.keySet())), pos);
            }
            if (args.size() < arity[0] || (arity[1] >= 0 && args.size() > arity[1])) {
                String expect = arity[1] < 0 ? "ít nhất " + arity[0]
                        : arity[0] == arity[1] ? String.valueOf(arity[0])
                        : arity[0] + " đến " + arity[1];
                throw error("Hàm " + fn + " cần " + expect + " tham số, đang có " + args.size(), pos);
            }
            return new Call(fn, List.copyOf(args));
        }

        public double eval(Vars vars) {
            switch (fn) {
                case "IF": {
                    boolean cond = truthy(args.get(0).eval(vars));
                    if (cond) {
                        return args.get(1).eval(vars);
                    }
                    return args.size() > 2 ? args.get(2).eval(vars) : 0;
                }
                case "AND": {
                    for (Node a : args) {
                        if (!truthy(a.eval(vars))) {
                            return 0;
                        }
                    }
                    return 1;
                }
                case "OR": {
                    for (Node a : args) {
                        if (truthy(a.eval(vars))) {
                            return 1;
                        }
                    }
                    return 0;
                }
                default:
                    break;
            }

            double x = args.get(0).eval(vars);

            switch (fn) {
                case "MIN": {
                    double m = x;
                    for (int i = 1; i < args.size(); i++) {
                        m = Math.min(m, args.get(i).eval(vars));
                    }
                    return m;
                }
                case "MAX": {
                    double m = x;
                    for (int i = 1; i < args.size(); i++) {
                        m = Math.max(m, args.get(i).eval(vars));
                    }
                    return m;
                }
                case "ROUND":
                    return round(x, digits(vars), RoundingMode.HALF_UP);
                case "ROUNDUP":
                    return round(x, digits(vars), RoundingMode.UP);
                case "ROUNDDOWN":
                    return round(x, digits(vars), RoundingMode.DOWN);
                case "FLOOR":
                    return Math.floor(x);
                case "CEIL":
                    return Math.ceil(x);
                case "ABS":
                    return Math.abs(x);
                case "NOT":
                    return truthy(x) ? 0 : 1;
                case "THUE_TNCN":
                    return thueTncn(x);
                default:
                    throw new FormulaException("Hàm không hỗ trợ: " + fn);
            }
        }

        private int digits(Vars vars) {
            return args.size() > 1 ? (int) Math.round(args.get(1).eval(vars)) : 0;
        }

        private static double round(double x, int digits, RoundingMode mode) {
            return BigDecimal.valueOf(x).setScale(digits, mode).doubleValue();
        }

        public void collectVariables(Set<String> out) {
            for (Node a : args) {
                a.collectVariables(out);
            }
        }
    }
}