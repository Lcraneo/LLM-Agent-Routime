package com.youkeda.project.skill;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;


@Component
public class CalculatorSkill implements Skill {

    public static final String PREFIX = "计算";

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public List<String> keywords() {
        return List.of("计算");
    }

    @Override
    public String description() {
        return "计算器：以\"计算\"开头，支持 + - * / 和括号的混合四则运算，例如：计算(10+2)*3-4/2";
    }

    @Override
    public String execute(String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        // 保持原逻辑：必须以"计算"开头，否则提示正确用法
        if (!text.startsWith(PREFIX)) {
            return "请以\"" + PREFIX + "\"开头输入算式，例如：计算10+20";
        }
        String expr = text.substring(PREFIX.length()).trim();
        if (expr.isBlank()) {
            return "无法识别算式，请输入类似：计算10+20";
        }
        // 移除所有空白，并补充被省略的乘号（如 (10+2)3 -> (10+2)*3）
        expr = expr.replaceAll("\\s+", "");
        expr = insertImplicitMultiplications(expr);
        // 校验只允许数字、运算符和括号，防止注入
        if (!expr.matches("[0-9+\\-*/().]+")) {
            return "算式包含非法字符，请使用数字和 + - * / ( ) 运算符，例如：计算10+20";
        }
        try {
            BigDecimal result = evaluate(expr);
            // 去掉多余的尾零
            return "计算结果：" + result.stripTrailingZeros().toPlainString();
        } catch (RuntimeException e) {
            return "无法识别算式，请检查是否有括号不匹配，例如：计算10+20";
        }
    }


    static String insertImplicitMultiplications(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && needImplicitMul(prev, c)) {
                out.append('*');
            }
            out.append(c);
            prev = c;
        }
        return out.toString();
    }


    private static boolean needImplicitMul(char prev, char next) {
        boolean prevClosed = prev == ')';
        boolean prevNumberOrDot = Character.isDigit(prev) || prev == '.';
        boolean nextOpen = next == '(';
        boolean nextNumberOrDot = Character.isDigit(next) || next == '.';
        // 数 -> (  或  ) -> 数/(  ：省略乘号
        return (prevNumberOrDot && nextOpen) || (prevClosed && (nextOpen || nextNumberOrDot));
    }


    private BigDecimal evaluate(String expr) {
        int[] pos = {0};
        BigDecimal result = parseExpr(expr, pos);
        if (pos[0] < expr.length()) {
            throw new IllegalArgumentException("unexpected char at " + pos[0]);
        }
        return result;
    }

    private BigDecimal parseExpr(String s, int[] pos) {
        BigDecimal value = parseTerm(s, pos);
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '+' || c == '-') {
                pos[0]++;
                BigDecimal rhs = parseTerm(s, pos);
                value = c == '+' ? value.add(rhs) : value.subtract(rhs);
            } else {
                break;
            }
        }
        return value;
    }

    private BigDecimal parseTerm(String s, int[] pos) {
        BigDecimal value = parseFactor(s, pos);
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '*' || c == '/') {
                pos[0]++;
                BigDecimal rhs = parseFactor(s, pos);
                value = c == '*' ? value.multiply(rhs) : value.divide(rhs, MathContext.DECIMAL64);
            } else {
                break;
            }
        }
        return value;
    }

    private BigDecimal parseFactor(String s, int[] pos) {
        if (pos[0] >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            BigDecimal value = parseExpr(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ')') {
                throw new IllegalArgumentException("missing )");
            }
            pos[0]++;
            return value;
        }
        if (c == '-') { // 一元负号
            pos[0]++;
            return parseFactor(s, pos).negate();
        }
        if (c == '+') { // 一元正号
            pos[0]++;
            return parseFactor(s, pos);
        }
        // 数字
        int start = pos[0];
        while (pos[0] < s.length()
            && (Character.isDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        if (start == pos[0]) {
            throw new IllegalArgumentException("expected number");
        }
        return new BigDecimal(s.substring(start, pos[0]));
    }
}
