package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传文件的第一道校验。
 *
 * <p>四件事必须同时成立，缺一项就等于没校验：
 * <ol>
 *   <li><b>扩展名</b>在白名单里；</li>
 *   <li><b>声明的 MIME</b> 与扩展名一致（浏览器报 {@code application/octet-stream} 时视为未声明）；</li>
 *   <li><b>文件签名</b>与扩展名一致——这一步挡住"把可执行文件改名成 .pdf"；</li>
 *   <li><b>真的能解码</b>，并在这之后才检查页数与像素上限——挡住截断文件和伪装成图片的文本。</li>
 * </ol>
 *
 * <p>原始文件名只作为展示元数据：这里取它的 basename、去掉控制字符，并且存储键从不使用它。
 * 所以 {@code ../../etc/passwd} 这类名字既不会成为路径，也不会通过扩展名检查。
 */
@Component
public final class UploadFileInspector {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private static final Map<String, String> EXTENSION_TO_MIME = Map.of(
        "pdf", "application/pdf",
        "png", "image/png",
        "jpg", "image/jpeg",
        "jpeg", "image/jpeg");

    /**
     * 浏览器在拿不准类型时会上报这两种，此时以扩展名与签名为准。
     *
     * <p>直接拒绝会让"从手机相册选照片"这条最常见的路径失败——移动端浏览器经常不给 MIME。
     * 而放宽的代价很小：真正的判据是签名与解码结果，不是这段客户端字符串。
     */
    private static final Set<String> UNDECLARED_MIME_TYPES =
        Set.of("application/octet-stream", "binary/octet-stream");

    private static final int MAX_NAME_LENGTH = 200;

    private final StorageProperties properties;
    private final PdfPageRenderer pdfRenderer;

    public UploadFileInspector(StorageProperties properties, PdfPageRenderer pdfRenderer) {
        this.properties = properties;
        this.pdfRenderer = pdfRenderer;
    }

    public InspectedFile inspect(String originalName, String declaredContentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new DomainException("FILE_EMPTY", "上传的文件是空的", HttpStatus.BAD_REQUEST);
        }
        String safeName = sanitizeName(originalName);
        String extension = extensionOf(safeName);
        String mimeType = EXTENSION_TO_MIME.get(extension);
        if (mimeType == null) {
            throw new DomainException("FILE_TYPE_NOT_ALLOWED",
                "仅支持 PDF、PNG 和 JPEG 文件", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        requireDeclaredTypeMatches(declaredContentType, mimeType);
        if (content.length > properties.maxFileBytes()) {
            throw new DomainException("FILE_SIZE_EXCEEDED",
                "单个文件不能超过 " + properties.maxFileBytes() + " 字节", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return mimeType.equals("application/pdf")
            ? inspectPdf(safeName, content)
            : inspectImage(safeName, mimeType, content);
    }

    private InspectedFile inspectPdf(String safeName, byte[] content) {
        requireSignature(content, PDF_SIGNATURE, "文件内容不是 PDF");
        PdfPageRenderer.PdfDescription description;
        try {
            description = pdfRenderer.describe(content);
        } catch (IOException exception) {
            throw notDecodable(exception);
        }
        if (description.pageCount() <= 0) {
            throw notDecodable(null);
        }
        if (description.pageCount() > properties.maxPages()) {
            throw new DomainException("FILE_PAGE_LIMIT_EXCEEDED",
                "单个 PDF 不能超过 " + properties.maxPages() + " 页", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        if (description.largestPagePixels() > properties.maxPixelsPerPage()) {
            throw new DomainException("FILE_PIXEL_LIMIT_EXCEEDED",
                "渲染后单页像素超出上限", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        // PDF 没有"整份文件的宽高"，用最长边为零表示，避免调用方拿页面尺寸当文件尺寸用。
        return new InspectedFile(mimeTypeOf(safeName), safeName, description.pageCount(), 0, 0);
    }

    private InspectedFile inspectImage(String safeName, String mimeType, byte[] content) {
        requireSignature(content, mimeType.equals("image/png") ? PNG_SIGNATURE : JPEG_SIGNATURE,
            "文件内容与扩展名不符");
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException exception) {
            throw notDecodable(exception);
        }
        if (image == null) {
            // 截断的 PNG、只有文件头的伪装图都会走到这里：ImageIO 读不出图就返回 null。
            throw notDecodable(null);
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels > properties.maxPixelsPerPage()) {
            throw new DomainException("FILE_PIXEL_LIMIT_EXCEEDED",
                "图片像素超出上限", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return new InspectedFile(mimeType, safeName, 1, image.getWidth(), image.getHeight());
    }

    private void requireDeclaredTypeMatches(String declaredContentType, String expected) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return;
        }
        String declared = declaredContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (UNDECLARED_MIME_TYPES.contains(declared)) {
            return;
        }
        if (!declared.equals(expected)) {
            throw new DomainException("FILE_TYPE_NOT_ALLOWED",
                "声明的内容类型与文件扩展名不一致", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private static void requireSignature(byte[] content, byte[] signature, String message) {
        if (content.length < signature.length) {
            throw new DomainException("FILE_CONTENT_MISMATCH", message, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                throw new DomainException("FILE_CONTENT_MISMATCH", message, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
        }
    }

    private static DomainException notDecodable(IOException cause) {
        return new DomainException("FILE_NOT_DECODABLE", "文件已损坏或无法解析",
            HttpStatus.UNPROCESSABLE_ENTITY, cause);
    }

    private static String mimeTypeOf(String safeName) {
        return EXTENSION_TO_MIME.get(extensionOf(safeName));
    }

    private static String extensionOf(String safeName) {
        int dot = safeName.lastIndexOf('.');
        return dot < 0 ? "" : safeName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 取 basename 并去掉控制字符。
     *
     * <p>只保留最后一段的另一个作用是让 {@code ../../x.png} 变成 {@code x.png}；
     * 即便将来有人把原始名拼进路径，也不会拼出目录穿越——当然存储键本来就不用它。
     */
    private static String sanitizeName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "upload";
        }
        String name = originalName.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        String cleaned = name.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "upload";
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(cleaned.length() - MAX_NAME_LENGTH) : cleaned;
    }

    /**
     * 校验结果。
     *
     * <p>{@code width}/{@code height} 只对图片有意义；PDF 返回 0，因为整份 PDF 没有单一宽高，
     * 拿第一页冒充整份文件的尺寸会让调用方算出错误的比例。
     */
    public record InspectedFile(String mimeType, String originalName, int pageCount, int width, int height) {

        public boolean isPdf() {
            return "application/pdf".equals(mimeType);
        }
    }
}
