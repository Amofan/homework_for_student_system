package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfPageRendererTest {

    @Test
    void 逐页渲染出可解码的PNG且尺寸符合目标DPI() throws IOException {
        List<PdfPageRenderer.PageImage> pages = renderer(40_000_000L).render(pdf(3));

        assertThat(pages).hasSize(3);
        assertThat(pages).extracting(PdfPageRenderer.PageImage::pageNo).containsExactly(1, 2, 3);
        for (PdfPageRenderer.PageImage page : pages) {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(page.png()));
            assertThat(decoded).isNotNull();
            // A4 是 595.28 x 841.89 点，200 DPI 下约 1654 x 2339 像素；PDFBox 会取整，
            // 所以给几像素的容忍度，只验证"按 DPI 换算"这件事成立。
            assertThat(page.width()).isBetween(1650, 1658);
            assertThat(page.height()).isBetween(2335, 2343);
            assertThat(decoded.getWidth()).isEqualTo(page.width());
        }
    }

    /**
     * 超限页面必须在分配位图之前被拒绝。
     *
     * <p>{@code renderImageWithDPI} 会先申请整张位图，一张恶意构造的大页面足以把堆吃光。
     * 所以这里把上限调到极小值来触发这条分支——被测的是"渲染前比较像素数"，与图有多大无关。
     */
    @Test
    void 超出像素上限的页面在渲染前被拒绝() throws IOException {
        PdfPageRenderer renderer = renderer(1_000L);

        assertThatThrownBy(() -> renderer.render(pdf(1)))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_PIXEL_LIMIT_EXCEEDED");
    }

    @Test
    void describe报告页数与像素规模() throws IOException {
        PdfPageRenderer.PdfDescription description = renderer(40_000_000L).describe(pdf(2));

        assertThat(description.pageCount()).isEqualTo(2);
        assertThat(description.largestPagePixels()).isGreaterThan(1_000_000L);
        assertThat(description.totalPixels()).isEqualTo(description.largestPagePixels() * 2);
    }

    private static PdfPageRenderer renderer(long maxPixelsPerPage) {
        return new PdfPageRenderer(new StorageProperties(StorageProperties.Provider.LOCAL,
            System.getProperty("java.io.tmpdir"), "", "", "", false,
            25_000_000L, 104_857_600L, 40, maxPixelsPerPage, 200, false, 200, 3_600_000L, 7, 30));
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
