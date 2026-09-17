package com.homework.analysis.ocr;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OCR 服务的分析结果。
 *
 * <p>契约校验放在 {@link #requireValid()} 里由调用方显式触发，而不是塞进 record 的紧凑构造器：
 * 构造器抛异常会在 Jackson 反序列化过程中被包成解析异常，真正的原因（哪个字段越界）就丢了，
 * 排查时只能看到一个笼统的"无法解析响应"。
 *
 * <p>校验失败的响应一律判为不可重试：上游返回了不合契约的数据，再问一次也还是同样的数据，
 * 重试只会浪费时间并把问题往后拖。
 */
public record OcrResult(
    String schemaVersion,
    String engine,
    String modelVersion,
    List<Page> pages) {

    public static final String SCHEMA_VERSION = "v1";

    /** 3x3 矩阵按行展开成 9 个数字，与 {@code document_page.transform_matrix} 的列注释一致。 */
    private static final int MATRIX_ELEMENTS = 9;

    /**
     * 识别结果的一页。
     *
     * <p>后三个字段是配准结果，全部可空：
     * <ul>
     *   <li>{@code templatePageNo}：这一页对应答卷模板的第几页。空 = 没对上任何模板页
     *       （学生交错了页、或者这一页根本没印在模板上）。</li>
     *   <li>{@code alignmentConfidence}：配准得分。空 = 没有做过配准（请求里没带模板）；
     *       有值但 {@code templatePageNo} 为空 = 比过了，就是不像。这两个状态必须能分开 ——
     *       把"没比过"和"比了不像"都写成空，教师就分不清是该重拍还是该换模板。</li>
     *   <li>{@code transformMatrix}：透视变换矩阵。当前引擎不产出，恒为空。</li>
     * </ul>
     */
    public record Page(int pageNo, int width, int height, List<OcrRegion> regions,
                       Integer templatePageNo, Double alignmentConfidence, String transformMatrix) {

        /**
         * 没有配准结果的页。
         *
         * <p>{@code null} 与"配准了但不像"在语义上不同（后者要带一个低得分），所以这里不写
         * {@code alignmentConfidence = 0}：0 是一个真实的得分，用它表示"没比过"会让教师
         * 看到"配准度 0%"这种根本不存在的结论。
         */
        public Page(int pageNo, int width, int height, List<OcrRegion> regions) {
            this(pageNo, width, height, regions, null, null, null);
        }
    }

    public void requireValid() {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("schemaVersion 不受支持：" + schemaVersion);
        }
        if (pages == null || pages.isEmpty()) {
            throw invalid("pages 不能为空");
        }
        Set<Integer> pageNumbers = new HashSet<>();
        Set<String> externalIds = new HashSet<>();
        for (Page page : pages) {
            if (page == null || page.pageNo() <= 0) {
                throw invalid("页码必须从 1 开始");
            }
            if (!pageNumbers.add(page.pageNo())) {
                throw invalid("页码重复：" + page.pageNo());
            }
            if (page.width() <= 0 || page.height() <= 0) {
                throw invalid("页面尺寸必须为正数：pageNo=" + page.pageNo());
            }
            requireValidAlignment(page);
            // 整页零区域是合法的：空白页、只有图形的页、整页未作答都真实存在。
            // 强行要求至少一个区域，只会让一张白页把整份文档判成契约不合法。
            if (page.regions() == null) {
                throw invalid("页面区域列表不能为空指针：pageNo=" + page.pageNo());
            }
            for (OcrRegion region : page.regions()) {
                requireValidRegion(region);
                if (!externalIds.add(region.externalId())) {
                    throw invalid("区域标识重复：" + region.externalId());
                }
            }
        }
    }

    /**
     * 配准结果的合法性。
     *
     * <p>矩阵只校验"9 个有限数字"这一层形状，不判断它是否可逆：不可逆的矩阵来自上游算错，
     * 而这里除了拒收整份文档之外做不了任何补救——真正要拦住的是"元素个数不对／含 NaN"
     * 这类会在裁剪时静默产出空白图的错误（NaN 参与的比较恒为 false，一路穿过去也不报错）。
     */
    private static void requireValidAlignment(Page page) {
        if (page.templatePageNo() != null && page.templatePageNo() <= 0) {
            throw invalid("模板页码必须从 1 开始：pageNo=" + page.pageNo());
        }
        if (page.alignmentConfidence() != null) {
            requireUnitInterval("alignmentConfidence", page.alignmentConfidence(), "pageNo=" + page.pageNo());
        }
        String matrix = page.transformMatrix();
        if (matrix == null) {
            return;
        }
        String[] elements = matrix.split(",", -1);
        if (elements.length != MATRIX_ELEMENTS) {
            throw invalid("变换矩阵必须是 " + MATRIX_ELEMENTS + " 个数字：pageNo=" + page.pageNo());
        }
        for (String element : elements) {
            try {
                if (!Double.isFinite(Double.parseDouble(element.trim()))) {
                    throw invalid("变换矩阵的元素必须有限：pageNo=" + page.pageNo());
                }
            } catch (NumberFormatException exception) {
                throw invalid("变换矩阵含无法解析的元素：pageNo=" + page.pageNo());
            }
        }
    }

    private static void requireValidRegion(OcrRegion region) {
        if (region == null) {
            throw invalid("区域不能为空");
        }
        if (region.externalId() == null || region.externalId().isBlank()) {
            throw invalid("区域缺少标识");
        }
        if (region.type() == null || region.type().isBlank()) {
            throw invalid("区域缺少类型：" + region.externalId());
        }
        // 题号要么是空（未归属，由教师手工归类），要么是有内容的字符串。
        // 空白串是第三种东西：它不是"认不出"，而是"上游填了个空"——前者要靠教师补，
        // 后者要靠上游修，混在一起就分不清该找谁。
        if (region.questionCode() != null && region.questionCode().isBlank()) {
            throw invalid("区域题号不能是空白：" + region.externalId());
        }
        requireUnitInterval("x", region.x(), region.externalId());
        requireUnitInterval("y", region.y(), region.externalId());
        requireUnitInterval("width", region.width(), region.externalId());
        requireUnitInterval("height", region.height(), region.externalId());
        requireUnitInterval("confidence", region.confidence(), region.externalId());
    }

    /**
     * 取值必须落在 {@code 0..1}。
     *
     * <p>{@code Double.isFinite} 一并挡住 NaN 与无穷：{@code NaN} 参与的所有比较都是 false，
     * 只用 {@code < 0 || > 1} 判断会让 NaN 一路穿过去，最后在裁剪坐标时变成一张空白图。
     *
     * <p>只校验单值范围，不强制 {@code x+width<=1}：越出页面的框属于数据质量问题，
     * 由候选阶段的警告提示教师处理，不该在这里把整份文档判为契约不合法。
     */
    private static void requireUnitInterval(String field, double value, String externalId) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw invalid(field + " 必须在 0 到 1 之间：" + externalId);
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException("OCR_CONTRACT_INVALID", "OCR 结果不符合契约：" + message,
            HttpStatus.BAD_GATEWAY);
    }
}
