package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把整卷 PDF 拆成页面 PNG。
 *
 * <p>渲染前先按页面尺寸算出目标像素，超限就在分配位图之前拒绝：{@code renderImageWithDPI}
 * 会一次性申请整张位图，一张恶意构造的大页面足以把堆吃光——"先渲染再检查"等于没有检查。
 */
public class PdfPageRenderer {

    private static final float POINTS_PER_INCH = 72f;

    private final int renderDpi;
    private final long maxPixelsPerPage;

    public PdfPageRenderer(StorageProperties properties) {
        this.renderDpi = properties.renderDpi();
        this.maxPixelsPerPage = properties.maxPixelsPerPage();
    }

    public PdfDescription describe(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pageCount = document.getNumberOfPages();
            long largestPagePixels = 0;
            long totalPixels = 0;
            for (int index = 0; index < pageCount; index++) {
                long pixels = renderedPixels(document.getPage(index));
                largestPagePixels = Math.max(largestPagePixels, pixels);
                totalPixels += pixels;
            }
            return new PdfDescription(pageCount, largestPagePixels, totalPixels);
        }
    }

    /** 按 {@code app.storage.render-dpi} 渲染全部页面，返回无损 PNG 字节。 */
    public List<PageImage> render(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageImage> pages = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                if (renderedPixels(document.getPage(index)) > maxPixelsPerPage) {
                    throw new DomainException("FILE_PIXEL_LIMIT_EXCEEDED",
                        "第 " + (index + 1) + " 页超出单页像素上限", HttpStatus.PAYLOAD_TOO_LARGE);
                }
                BufferedImage image = renderer.renderImageWithDPI(index, renderDpi, ImageType.RGB);
                int width = image.getWidth();
                int height = image.getHeight();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                image.flush();
                pages.add(new PageImage(index + 1, width, height, output.toByteArray()));
            }
            return pages;
        }
    }

    /** 页面在目标 DPI 下的像素数。1 英寸 = 72 点，所以缩放比是 {@code dpi / 72}。 */
    private long renderedPixels(PDPage page) {
        PDRectangle box = page.getCropBox() == null ? page.getMediaBox() : page.getCropBox();
        float scale = renderDpi / POINTS_PER_INCH;
        return (long) Math.ceil(box.getWidth() * scale) * (long) Math.ceil(box.getHeight() * scale);
    }

    public record PdfDescription(int pageCount, long largestPagePixels, long totalPixels) {}

    public record PageImage(int pageNo, int width, int height, byte[] png) {}
}
