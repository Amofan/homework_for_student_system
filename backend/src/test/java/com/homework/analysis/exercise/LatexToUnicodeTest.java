package com.homework.analysis.exercise;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转换器规则矩阵。CSV 里的反斜杠按 Java 文本块原样传给被测代码，
 * JUnit 的 CsvSource 不做二次转义，所以 `\\angle` 就是字面的 `\angle`。
 */
class LatexToUnicodeTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        $\\angle 1=65°$|∠1=65°
        $AB \\parallel CD$|AB ∥ CD
        $a\\perp b$|a⊥ b
        $\\triangle ABC \\sim \\triangle DEF$|△ABC ∽ △DEF
        $\\odot O$|⊙O
        $a\\cong b$|a≌ b
        $2\\times3\\div6$|2×3÷6
        $a\\pm b\\cdot c$|a± b· c
        $a\\leq b, c\\ge d, x\\ne y, m\\approx n$|a≤ b, c≥ d, x≠ y, m≈ n
        $\\left(x\\right)$|(x)
        $a\\,b\\;c\\ d$|a b c d
        $\\{x\\}\\%\\$\\&\\#\\_$|{x}%$&#_
        $^\\circ$|°
        """)
    void convertsSupportedCommands(String raw, String expected) {
        assertThat(LatexToUnicode.convert(raw))
            .isEqualTo(new LatexToUnicode.Conversion(expected, false));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        4x^2|4x²
        x^{2n}|x²ⁿ
        x_1|x₁
        x^{ab}|x^{ab}
        $\\frac{2}{3}$|2/3
        $\\frac{\\sqrt{2}}{2}$|√2/2
        $\\sqrt[3]{x}$|³√x
        $\\frac{x+1}{2}$|(x+1)/2
        $\\frac{a}{b-c}$|a/(b-c)
        $\\frac{a+b}{c-d}$|(a+b)/(c-d)
        $\\frac{-1}{2}$|(-1)/2
        """)
    void convertsStructuresAndScripts(String raw, String expected) {
        assertThat(LatexToUnicode.convert(raw).text()).isEqualTo(expected);
        assertThat(LatexToUnicode.convert(raw).fellBack()).isFalse();
    }

    @Test
    void leavesCommandsOutsideMathAndUnsupportedScriptsReadable() {
        assertThat(LatexToUnicode.convert("\\angle 1=65°").text()).isEqualTo("\\angle 1=65°");
        assertThat(LatexToUnicode.convert("$\\_i$").text()).isEqualTo("_i");
        assertThat(LatexToUnicode.convert("纯文本说明").text()).isEqualTo("纯文本说明");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        $\\begin{cases}x=1\\end{cases}$|\\begin{cases}x=1\\end{cases}
        $\\vec{a}$|\\vec{a}
        $\\overline{AB}$|\\overline{AB}
        """)
    void fallsBackOnUnknownCommandsWithoutLeavingDelimiters(String raw, String expected) {
        LatexToUnicode.Conversion result = LatexToUnicode.convert(raw);
        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.text()).doesNotContain("$");
        assertThat(result.fellBack()).isTrue();
    }
}
