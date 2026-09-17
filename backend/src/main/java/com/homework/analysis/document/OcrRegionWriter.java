package com.homework.analysis.document;

import com.homework.analysis.ocr.OcrRegion;
import com.homework.analysis.ocr.OcrResult;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 OCR 结果里的区域落成 {@code document_region} 行。
 *
 * <p>两条链路共用这一份列清单：整卷导入（题号锚点分组）与学生答卷（模板配准分组）。
 * 两边要写的列完全一样，而列名写错一次不会报错，只会在某一处安静地偏移——所以只留一处。
 *
 * <p>这里不写 {@code crop_file_id} / {@code review_status} / {@code revision}：它们都有
 * 库里的默认值，且语义属于"教师校对之后"，物化阶段不该替教师做决定。
 *
 * <p>区域一个都不丢：识别出多少块就落多少块，哪怕它落在所有题目框之外。丢掉的区域
 * 是教师无法再核对的像素，而"这块到底属于哪道题"正是校对要回答的问题。
 */
@Component
public class OcrRegionWriter {

    private static final Set<String> KNOWN_REGION_TYPES =
        Set.of("TEXT_BLOCK", "FORMULA", "FIGURE", "ANSWER_BLOCK", "OPTION");

    /** 未知类型归一化后的落点。分组逻辑要按它判断"这一块是不是普通文本块"，所以对外公开。 */
    public static final String DEFAULT_REGION_TYPE = "TEXT_BLOCK";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    OcrRegionWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 落库并返回新建的区域，按识别结果里的顺序。
     *
     * @param pageIds 文档内的页码到 {@code document_page.id} 的映射，由调用方建页时得到
     */
    public List<WrittenRegion> write(Map<Integer, Long> pageIds, OcrResult result) {
        List<WrittenRegion> rows = new ArrayList<>();
        for (OcrResult.Page page : result.pages()) {
            Long pageId = pageIds.get(page.pageNo());
            if (pageId == null) {
                // 调用方漏建了这一页。静默跳过会让这一页的全部区域消失，而界面上看不出少了什么。
                throw new IllegalStateException("区域落库时缺少页面记录：pageNo=" + page.pageNo());
            }
            for (OcrRegion region : page.regions()) {
                String regionType = normalizeRegionType(region.type());
                long regionId = GeneratedKeys.insert(jdbc, """
                    insert into document_region(page_id, region_type, x, y, width, height,
                                                ocr_text, ocr_latex, confidence, raw_ocr_json)
                    values (:pageId, :regionType, :x, :y, :width, :height,
                            :ocrText, :ocrLatex, :confidence, :rawJson)
                    """, statement -> statement
                    .param("pageId", pageId)
                    .param("regionType", regionType)
                    .param("x", region.x())
                    .param("y", region.y())
                    .param("width", region.width())
                    .param("height", region.height())
                    .param("ocrText", region.text())
                    .param("ocrLatex", region.latex())
                    .param("confidence", region.confidence())
                    .param("rawJson", writeJson(region)));
                rows.add(new WrittenRegion(regionId, page.pageNo(), regionType, region));
            }
        }
        return rows;
    }

    /** 引擎给的类型不在已知集合里时归为文本块：未知类型不该让整篇文档无法校对。 */
    private static String normalizeRegionType(String raw) {
        if (raw == null) {
            return DEFAULT_REGION_TYPE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return KNOWN_REGION_TYPES.contains(normalized) ? normalized : DEFAULT_REGION_TYPE;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("区域数据无法序列化", exception);
        }
    }

    /**
     * 一行已落库的区域。
     *
     * <p>{@code region} 保留原始识别结果：{@code x/y/width/height} 落库时会被数据库转成
     * {@code decimal(8,7)}，分组与告警要判的是"引擎报的框"而不是"库里存下的框"。
     */
    public record WrittenRegion(long regionId, int pageNo, String regionType, OcrRegion region) {

        /** 归一化坐标的相交判断；只关心是否重叠，不关心重叠面积。 */
        public boolean intersects(WrittenRegion other) {
            double left = region.x();
            double top = region.y();
            double right = region.x() + region.width();
            double bottom = region.y() + region.height();
            double otherLeft = other.region().x();
            double otherTop = other.region().y();
            double otherRight = other.region().x() + other.region().width();
            double otherBottom = other.region().y() + other.region().height();
            return left < otherRight && otherLeft < right && top < otherBottom && otherTop < bottom;
        }

        /** 可读文本：优先识别出的文字，没有就退回公式。两者都没有时返回空串。 */
        public String text() {
            String text = region.text();
            if (text != null && !text.isBlank()) {
                return text;
            }
            return region.latex() == null ? "" : region.latex();
        }
    }
}
