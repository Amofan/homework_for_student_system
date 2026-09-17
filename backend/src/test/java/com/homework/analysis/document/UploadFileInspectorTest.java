package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadFileInspectorTest {

    private static final Path UNUSED_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "inspector-test");

    @Test
    void 合法图片与PDF通过并给出页数或尺寸() throws IOException {
        UploadFileInspector inspector = inspector(defaultProperties());

        UploadFileInspector.InspectedFile image = inspector.inspect("photo.png", "image/png", png(64, 32));
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(image.pageCount()).isEqualTo(1);
        assertThat(image.width()).isEqualTo(64);
        assertThat(image.height()).isEqualTo(32);

        UploadFileInspector.InspectedFile photo = inspector.inspect("scan.JPG", "image/jpeg", jpeg(20, 10));
        assertThat(photo.mimeType()).isEqualTo("image/jpeg");

        UploadFileInspector.InspectedFile paper = inspector.inspect("paper.pdf", "application/pdf", pdf(3));
        assertThat(paper.isPdf()).isTrue();
        assertThat(paper.pageCount()).isEqualTo(3);
        // PDF 没有单一宽高，返回 0 而不是拿第一页冒充整份文件的尺寸。
        assertThat(paper.width()).isZero();
    }

    /**
     * 改名的可执行文件必须被签名检查拦住。
     *
     * <p>只查扩展名和声明 MIME 是不够的——两者都由客户端提供，把它们改成 .pdf 的成本是零。
     */
    @Test
    void 改名为PDF的可执行文件被拒绝() {
        byte[] executable = "MZ\u0090\u0000\u0003\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> inspector(defaultProperties()).inspect("payload.pdf", "application/pdf", executable))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_CONTENT_MISMATCH");
    }

    @Test
    void SVG被扩展名白名单拦住而伪装成PNG的SVG被签名拦住() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> inspector(defaultProperties()).inspect("picture.svg", "image/svg+xml", svg))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_TYPE_NOT_ALLOWED");

        assertThatThrownBy(() -> inspector(defaultProperties()).inspect("picture.png", "image/png", svg))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_CONTENT_MISMATCH");
    }

    /**
     * 截断的 PNG：文件头是对的，但没有可解码的内容。
     *
     * <p>这一例专门覆盖"签名通过但解码失败"，也就是只做签名检查时会漏掉的那类文件。
     */
    @Test
    void 只有文件头的截断PNG被拒绝() {
        byte[] signatureOnly = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        assertThatThrownBy(() -> inspector(defaultProperties()).inspect("broken.png", "image/png", signatureOnly))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_NOT_DECODABLE");
    }

    @Test
    void 超过页数上限的PDF被拒绝() throws IOException {
        UploadFileInspector inspector = inspector(properties(25_000_000L, 2, 40_000_000L));

        assertThatThrownBy(() -> inspector.inspect("paper.pdf", "application/pdf", pdf(3)))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_PAGE_LIMIT_EXCEEDED");
    }

    /**
     * 像素炸弹。
     *
     * <p>把上限调成 100 像素而不是去构造一张 4000 万像素的真图：构造那样一张图要占几百 MB 堆，
     * 而被测的逻辑只是"拿宽高相乘和上限比大小"，与图有多大无关。
     */
    @Test
    void 超出像素上限的图片被拒绝() throws IOException {
        UploadFileInspector inspector = inspector(properties(25_000_000L, 40, 100L));

        assertThatThrownBy(() -> inspector.inspect("bomb.png", "image/png", png(20, 20)))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_PIXEL_LIMIT_EXCEEDED");
    }

    @Test
    void 声明类型与扩展名不符时被拒绝() throws IOException {
        assertThatThrownBy(() -> inspector(defaultProperties())
            .inspect("photo.png", "application/pdf", png(8, 8)))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_TYPE_NOT_ALLOWED");
    }

    /** 手机浏览器经常不给 MIME，此时以扩展名与签名为准，不能因此拒绝相册里的照片。 */
    @Test
    void 未声明内容类型时以扩展名与签名为准() throws IOException {
        UploadFileInspector inspector = inspector(defaultProperties());

        assertThat(inspector.inspect("photo.png", "application/octet-stream", png(8, 8)).mimeType())
            .isEqualTo("image/png");
        assertThat(inspector.inspect("photo.png", null, png(8, 8)).mimeType())
            .isEqualTo("image/png");
        assertThat(inspector.inspect("photo.png", "  ", png(8, 8)).mimeType())
            .isEqualTo("image/png");
    }

    @Test
    void 空文件被拒绝() {
        assertThatThrownBy(() -> inspector(defaultProperties()).inspect("photo.png", "image/png", new byte[0]))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_EMPTY");
    }

    @Test
    void 超过单文件上限被拒绝() throws IOException {
        UploadFileInspector inspector = inspector(properties(16L, 40, 40_000_000L));

        assertThatThrownBy(() -> inspector.inspect("photo.png", "image/png", png(64, 64)))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("FILE_SIZE_EXCEEDED");
    }

    /**
     * 文件名只取 basename 并去掉控制字符。
     *
     * <p>原始名从来不会被拼进路径（存储键由服务端生成），但把它洗成纯文件名仍然必要：
     * 它会进响应头与界面，留着 {@code ../} 就是在给下一个把文件名当路径用的人埋雷。
     */
    @Test
    void 路径穿越与含控制字符的文件名被清洗() throws IOException {
        UploadFileInspector inspector = inspector(defaultProperties());

        assertThat(inspector.inspect("../../../etc/photo.png", "image/png", png(8, 8)).originalName())
            .isEqualTo("photo.png");
        assertThat(inspector.inspect("..\\..\\windows\\evil.png", "image/png", png(8, 8)).originalName())
            .isEqualTo("evil.png");
        assertThat(inspector.inspect("bad\u0000name\u001F.png", "image/png", png(8, 8)).originalName())
            .isEqualTo("badname.png");
    }

    /**
     * 清洗不会把非法文件名"洗成合法"。
     *
     * <p>{@code ../../etc/passwd} 洗完是 {@code passwd}，没有允许的扩展名，仍然拒绝；
     * 空文件名洗完是占位的 {@code upload}，同样拒绝。这里不替用户猜一个扩展名——
     * 扩展名是白名单的一环，猜一个等于把它绕过去。
     */
    @Test
    void 洗完后没有合法扩展名的文件仍被拒绝() throws IOException {
        UploadFileInspector inspector = inspector(defaultProperties());

        for (String name : new String[]{"../../etc/passwd", "   ", "noextension"}) {
            assertThatThrownBy(() -> inspector.inspect(name, "image/png", png(8, 8)))
                .as("文件名 %s 应当被拒绝", name)
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo("FILE_TYPE_NOT_ALLOWED");
        }
    }

    private static UploadFileInspector inspector(StorageProperties properties) {
        return new UploadFileInspector(properties, new PdfPageRenderer(properties));
    }

    private static StorageProperties defaultProperties() {
        return properties(25_000_000L, 40, 40_000_000L);
    }

    private static StorageProperties properties(long maxFileBytes, int maxPages, long maxPixelsPerPage) {
        return new StorageProperties(StorageProperties.Provider.LOCAL, UNUSED_ROOT.toString(),
            "", "", "", false, maxFileBytes, 104_857_600L, maxPages, maxPixelsPerPage,
            200, false, 200, 3_600_000L, 7, 30);
    }

    private static byte[] png(int width, int height) throws IOException {
        return image(width, height, "png");
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        return image(width, height, "jpg");
    }

    private static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
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
