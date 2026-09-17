package com.homework.analysis.ocr;

import com.homework.analysis.shared.error.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约校验器的直接单元测试。
 *
 * <p>不走 HTTP 是因为要覆盖 NaN：标准 JSON 里没有 NaN 字面量，靠 WireMock 造不出这个输入，
 * 但 Jackson 在部分配置下能解析出 NaN，而 {@code ge=0}/{@code le=1} 这类比较是拦不住它的
 * ——NaN 与任何数比较都是 false，会一路穿到裁剪坐标变成一张空白图。
 */
class OcrResultContractTest {

    @Test
    void 合法结果通过校验() {
        result(regions(region("p1-r1", 0.1, 0.2, 0.7, 0.08, 0.93))).requireValid();
    }

    @Test
    void 未知schema版本被拒绝() {
        OcrResult invalid = new OcrResult("v2", "paddleocr", "3.7.0", pages(regions(region())));

        assertCode(invalid, "OCR_CONTRACT_INVALID");
    }

    @Test
    void 空页面数组被拒绝() {
        assertCode(new OcrResult("v1", "paddleocr", "3.7.0", List.of()), "OCR_CONTRACT_INVALID");
        assertCode(new OcrResult("v1", "paddleocr", "3.7.0", null), "OCR_CONTRACT_INVALID");
    }

    @Test
    void NaN与无穷被拒绝() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> result(regions(region("p1-r1", value, 0.2, 0.7, 0.08, 0.93))).requireValid())
                .as("x=%s 应当被拒绝", value)
                .isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> result(regions(region("p1-r1", 0.1, 0.2, 0.7, 0.08, value))).requireValid())
                .as("confidence=%s 应当被拒绝", value)
                .isInstanceOf(DomainException.class);
        }
    }

    @Test
    void 越界取值被拒绝() {
        assertCode(result(regions(region("p1-r1", 1.4, 0.2, 0.7, 0.08, 0.93))), "OCR_CONTRACT_INVALID");
        assertCode(result(regions(region("p1-r1", -0.1, 0.2, 0.7, 0.08, 0.93))), "OCR_CONTRACT_INVALID");
        assertCode(result(regions(region("p1-r1", 0.1, 0.2, 0.7, 0.08, 1.2))), "OCR_CONTRACT_INVALID");
    }

    @Test
    void 区域标识重复被拒绝() {
        List<OcrRegion> duplicated = regions(region("p1-r1", 0.1, 0.2, 0.1, 0.1, 0.9),
            region("p1-r1", 0.3, 0.4, 0.1, 0.1, 0.9));

        assertCode(result(duplicated), "OCR_CONTRACT_INVALID");
    }

    /** 跨页重复同样拒绝：externalId 是区域在全文档内的身份，教师按 id 回写修订时不能改错页。 */
    @Test
    void 跨页区域标识重复被拒绝() {
        OcrResult duplicated = new OcrResult("v1", "paddleocr", "3.7.0", List.of(
            new OcrResult.Page(1, 100, 200, List.of(region())),
            new OcrResult.Page(2, 100, 200, List.of(region()))));

        assertCode(duplicated, "OCR_CONTRACT_INVALID");
    }

    @Test
    void 页码重复或从零开始被拒绝() {
        assertCode(new OcrResult("v1", "paddleocr", "3.7.0", List.of(
            new OcrResult.Page(0, 100, 200, List.of(region())))), "OCR_CONTRACT_INVALID");
        assertCode(new OcrResult("v1", "paddleocr", "3.7.0", List.of(
            new OcrResult.Page(1, 100, 200, List.of(region("p1-r1", 0.1, 0.1, 0.1, 0.1, 0.9))),
            new OcrResult.Page(1, 100, 200, List.of(region("p1-r2", 0.2, 0.2, 0.1, 0.1, 0.9))))),
            "OCR_CONTRACT_INVALID");
    }

    /** 空白页是合法输入：整页未作答、只有图形都会让区域列表为空。 */
    @Test
    void 整页零区域是合法的() {
        new OcrResult("v1", "paddleocr", "3.7.0",
            List.of(new OcrResult.Page(1, 2480, 3508, new ArrayList<>()))).requireValid();
    }

    @Test
    void 缺少区域标识被拒绝() {
        assertCode(result(regions(new OcrRegion("", "TEXT_BLOCK", 0.1, 0.1, 0.1, 0.1, null, null, 0.9))),
            "OCR_CONTRACT_INVALID");
        assertCode(result(regions(new OcrRegion("p1-r1", null, 0.1, 0.1, 0.1, 0.1, null, null, 0.9))),
            "OCR_CONTRACT_INVALID");
    }

    private static void assertCode(OcrResult invalid, String expectedCode) {
        assertThatThrownBy(invalid::requireValid)
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo(expectedCode);
    }

    private static OcrResult result(List<OcrRegion> regions) {
        return new OcrResult("v1", "paddleocr", "3.7.0", pages(regions));
    }

    private static List<OcrResult.Page> pages(List<OcrRegion> regions) {
        return List.of(new OcrResult.Page(1, 2480, 3508, regions));
    }

    private static List<OcrRegion> regions(OcrRegion... values) {
        return new ArrayList<>(List.of(values));
    }

    private static OcrRegion region() {
        return region("p1-r1", 0.1, 0.2, 0.7, 0.08, 0.93);
    }

    private static OcrRegion region(String externalId, double x, double y, double width, double height,
                                    double confidence) {
        return new OcrRegion(externalId, "QUESTION_TEXT", x, y, width, height, "题干", null, confidence);
    }
}
