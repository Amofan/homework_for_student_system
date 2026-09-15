package com.homework.analysis.exercise;

import java.util.Map;
import java.util.Set;

/**
 * Word 导出的确定性 LaTeX 子集转换器。
 *
 * <p>两条主规则（设计文档第 6 节）：上下标全程转换，LaTeX 命令只在 {@code $...$} 段内解释。
 * 段内出现命令表以外的命令时，保留原文并返回 {@code fellBack=true}，由调用方标红并收集题号。
 * 这里刻意不用正则替换：只有单趟扫描才能保证转义先于上下标、分数按平衡括号取参数。
 */
final class LatexToUnicode {
    private static final Map<String, String> COMMANDS = Map.ofEntries(
        Map.entry("angle", "∠"), Map.entry("parallel", "∥"), Map.entry("perp", "⊥"),
        Map.entry("triangle", "△"), Map.entry("odot", "⊙"), Map.entry("sim", "∽"),
        Map.entry("cong", "≌"), Map.entry("times", "×"), Map.entry("div", "÷"),
        Map.entry("pm", "±"), Map.entry("cdot", "·"), Map.entry("leq", "≤"),
        Map.entry("le", "≤"), Map.entry("geq", "≥"), Map.entry("ge", "≥"),
        Map.entry("neq", "≠"), Map.entry("ne", "≠"), Map.entry("approx", "≈")
    );
    private static final Set<String> REMOVED = Set.of("left", "right");
    private static final Map<Character, Character> SUPERSCRIPTS = Map.ofEntries(
        Map.entry('0', '⁰'), Map.entry('1', '¹'), Map.entry('2', '²'), Map.entry('3', '³'),
        Map.entry('4', '⁴'), Map.entry('5', '⁵'), Map.entry('6', '⁶'), Map.entry('7', '⁷'),
        Map.entry('8', '⁸'), Map.entry('9', '⁹'), Map.entry('+', '⁺'), Map.entry('-', '⁻'),
        Map.entry('=', '⁼'), Map.entry('(', '⁽'), Map.entry(')', '⁾'),
        Map.entry('n', 'ⁿ'), Map.entry('i', 'ⁱ')
    );
    private static final Map<Character, Character> SUBSCRIPTS = Map.ofEntries(
        Map.entry('0', '₀'), Map.entry('1', '₁'), Map.entry('2', '₂'), Map.entry('3', '₃'),
        Map.entry('4', '₄'), Map.entry('5', '₅'), Map.entry('6', '₆'), Map.entry('7', '₇'),
        Map.entry('8', '₈'), Map.entry('9', '₉'), Map.entry('+', '₊'), Map.entry('-', '₋'),
        Map.entry('=', '₌'), Map.entry('(', '₍'), Map.entry(')', '₎'),
        Map.entry('i', 'ᵢ'), Map.entry('j', 'ⱼ')
    );

    /** 紧跟符号、渲染时贴住后一个字符的命令：`\angle 1` 要得到 `∠1` 而不是 `∠ 1`。 */
    private static final Set<String> PREFIX_COMMANDS = Set.of("angle", "triangle", "odot");

    private LatexToUnicode() {}

    static Conversion convert(String raw) {
        StringBuilder output = new StringBuilder();
        boolean fellBack = false;
        int plainStart = 0;
        int index = 0;
        while (index < raw.length()) {
            if (raw.charAt(index) == '\\' && index + 1 < raw.length() && raw.charAt(index + 1) == '$') {
                output.append(convertScripts(raw.substring(plainStart, index)));
                output.append('$');
                index += 2;
                plainStart = index;
                continue;
            }
            if (raw.charAt(index) != '$') {
                index++;
                continue;
            }

            if (index + 1 < raw.length() && raw.charAt(index + 1) == '$') {
                int close = displayClose(raw, index + 2);
                if (close >= 0 && !raw.substring(index + 2, close).trim().isEmpty()) {
                    output.append(convertScripts(raw.substring(plainStart, index)));
                    Conversion math = convertMath(raw.substring(index + 2, close));
                    output.append(math.text());
                    fellBack |= math.fellBack();
                    index = close + 2;
                    plainStart = index;
                    continue;
                }
                index = close >= 0 ? close + 2 : index + 1;
                continue;
            }

            if (index + 1 >= raw.length() || Character.isWhitespace(raw.charAt(index + 1))) {
                index++;
                continue;
            }
            int close = inlineClose(raw, index + 1);
            if (close >= 0) {
                output.append(convertScripts(raw.substring(plainStart, index)));
                Conversion math = convertMath(raw.substring(index + 1, close));
                output.append(math.text());
                fellBack |= math.fellBack();
                index = close + 1;
                plainStart = index;
                continue;
            }
            index++;
        }
        output.append(convertScripts(raw.substring(plainStart)));
        return new Conversion(output.toString(), fellBack);
    }

    private static int displayClose(String text, int from) {
        for (int index = from; index < text.length() - 1; index++) {
            if (text.charAt(index) == '$' && text.charAt(index + 1) == '$' && !isEscaped(text, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int inlineClose(String text, int from) {
        for (int index = from; index < text.length(); index++) {
            if (text.charAt(index) != '$' || isEscaped(text, index)) continue;
            if (Character.isWhitespace(text.charAt(index - 1))) continue;
            if (index + 1 < text.length() && Character.isDigit(text.charAt(index + 1))) continue;
            return index;
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) slashes++;
        return slashes % 2 == 1;
    }

    private static Conversion convertMath(String tex) {
        try {
            return new Conversion(new MathParser(tex).parse(), false);
        } catch (UnsupportedCommand exception) {
            return new Conversion(tex, true);
        }
    }

    private static String convertScripts(String text) {
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length() && text.charAt(index + 1) == '_') {
                // 转义先于上下标：`\_i` 是字面 "_i"，不是下标 ᵢ。
                output.append("\\_");
                index += 2;
                continue;
            }
            if (current != '^' && current != '_') {
                output.append(current);
                index++;
                continue;
            }
            Script script = scriptAt(text, index + 1);
            String mapped = mapScript(script.value(), current == '^' ? SUPERSCRIPTS : SUBSCRIPTS);
            if (mapped == null) {
                output.append(current);
                if (script.grouped()) output.append('{');
                output.append(script.value());
                if (script.grouped()) output.append('}');
            } else {
                output.append(mapped);
            }
            index = script.nextIndex();
        }
        return output.toString();
    }

    private static Script scriptAt(String text, int index) {
        if (index >= text.length()) return new Script("", false, index);
        if (text.charAt(index) != '{') {
            return new Script(String.valueOf(text.charAt(index)), false, index + 1);
        }
        Group group = groupAt(text, index);
        return group == null
            ? new Script(text.substring(index + 1), true, text.length())
            : new Script(group.value(), true, group.nextIndex());
    }

    /** 组内每个字符都能映射才返回映射结果，否则返回 null，由调用方保留原文。 */
    private static String mapScript(String value, Map<Character, Character> mapping) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            Character mapped = mapping.get(value.charAt(index));
            if (mapped == null) return null;
            output.append(mapped);
        }
        return output.toString();
    }

    private static Group groupAt(String text, int openBrace) {
        if (openBrace >= text.length() || text.charAt(openBrace) != '{') return null;
        int depth = 0;
        for (int index = openBrace; index < text.length(); index++) {
            if (text.charAt(index) == '{') depth++;
            if (text.charAt(index) == '}' && --depth == 0) {
                return new Group(text.substring(openBrace + 1, index), index + 1);
            }
        }
        return null;
    }

    /** 分子分母含低优先级运算符或空格时必须补括号，否则会改变运算顺序。 */
    private static String fractionPart(String value) {
        return value.startsWith("-") || value.indexOf('+') >= 0 || value.indexOf('-') >= 0 || value.indexOf(' ') >= 0
            ? "(" + value + ")" : value;
    }

    record Conversion(String text, boolean fellBack) {}

    private record Group(String value, int nextIndex) {}

    private record Script(String value, boolean grouped, int nextIndex) {}

    private static final class UnsupportedCommand extends RuntimeException {}

    /** 段内解析器：不认识的名字一律抛 UnsupportedCommand，由 convertMath 转成“保留原文 + 标红”。 */
    private static final class MathParser {
        private final String text;
        private final StringBuilder output = new StringBuilder();
        private int index;

        private MathParser(String text) {
            this.text = text;
        }

        private String parse() {
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current == '\\') {
                    command();
                } else if (current == '^' || current == '_') {
                    script(current);
                } else {
                    output.append(current);
                    index++;
                }
            }
            return output.toString();
        }

        private void command() {
            if (index + 1 >= text.length()) throw new UnsupportedCommand();
            char escaped = text.charAt(index + 1);
            if ("{}%$&#_".indexOf(escaped) >= 0) {
                output.append(escaped);
                index += 2;
                return;
            }
            if (escaped == ',' || escaped == ';' || escaped == ' ') {
                output.append(' ');
                index += 2;
                return;
            }

            int commandStart = index;
            index++;
            int nameStart = index;
            while (index < text.length() && Character.isLetter(text.charAt(index))) index++;
            if (nameStart == index) throw new UnsupportedCommand();
            String name = text.substring(nameStart, index);
            if ("frac".equals(name)) {
                fraction(commandStart);
                return;
            }
            if ("sqrt".equals(name)) {
                squareRoot(commandStart);
                return;
            }
            String mapped = COMMANDS.get(name);
            if (mapped != null) {
                output.append(mapped);
                if (PREFIX_COMMANDS.contains(name)
                        && index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                return;
            }
            if (REMOVED.contains(name)) return;
            throw new UnsupportedCommand();
        }

        private void fraction(int commandStart) {
            Group numerator = groupAt(text, index);
            if (numerator == null) {
                output.append(text, commandStart, index);
                return;
            }
            Group denominator = groupAt(text, numerator.nextIndex());
            if (denominator == null) {
                output.append(text, commandStart, numerator.nextIndex());
                index = numerator.nextIndex();
                return;
            }
            Conversion top = convertMath(numerator.value());
            Conversion bottom = convertMath(denominator.value());
            if (top.fellBack() || bottom.fellBack()) throw new UnsupportedCommand();
            output.append(fractionPart(top.text())).append('/').append(fractionPart(bottom.text()));
            index = denominator.nextIndex();
        }

        private void squareRoot(int commandStart) {
            String rootIndex = "";
            if (index < text.length() && text.charAt(index) == '[') {
                int close = text.indexOf(']', index + 1);
                if (close < 0) {
                    output.append(text, commandStart, index);
                    return;
                }
                rootIndex = text.substring(index + 1, close);
                index = close + 1;
            }
            Group radicand = groupAt(text, index);
            if (radicand == null) {
                output.append(text, commandStart, index);
                return;
            }
            Conversion converted = convertMath(radicand.value());
            if (converted.fellBack()) throw new UnsupportedCommand();
            String mappedIndex = mapScript(rootIndex, SUPERSCRIPTS);
            if (!rootIndex.isEmpty() && mappedIndex == null) {
                output.append(text, commandStart, radicand.nextIndex());
            } else {
                output.append(mappedIndex == null ? "" : mappedIndex).append('√').append(converted.text());
            }
            index = radicand.nextIndex();
        }

        private void script(char marker) {
            if (marker == '^' && text.startsWith("\\circ", index + 1)) {
                output.append('°');
                index += 6;
                return;
            }
            Script script = scriptAt(text, index + 1);
            String mapped = mapScript(script.value(), marker == '^' ? SUPERSCRIPTS : SUBSCRIPTS);
            if (mapped == null) {
                output.append(marker);
                if (script.grouped()) output.append('{');
                output.append(script.value());
                if (script.grouped()) output.append('}');
            } else {
                output.append(mapped);
            }
            index = script.nextIndex();
        }
    }
}
