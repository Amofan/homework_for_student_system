package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 页面图的派生变换：缩略图、题图裁剪与直角旋转。
 *
 * <p>两处共同的硬约束是**先在原始像素上做边界钳制再做变换**。区域坐标来自 OCR，
 * 是归一化值，一旦上游给了越界或退化的框，直接按它裁剪会抛 {@code RasterFormatException}——
 * 那是一个 500，而正确的结果是一个可读的业务错误，或者干脆是一张被钳到页内的图。
 */
public final class ImageTransforms {

    /** 缩略图的目标宽度；高度按比例算，保证缩略图之间视觉高度一致。 */
    private static final int THUMBNAIL_WIDTH = 240;

    private ImageTransforms() {
    }

    /**
     * 按归一化矩形裁剪。
     *
     * <p>矩形会被钳到图像范围内；钳制后宽或高不足 1 像素时拒绝——那种"成功"的裁剪
     * 只会产出一张空白图，而空白图正是教师校对时唯一能核对的东西，不能悄悄给出去。
     */
    public static byte[] crop(byte[] imageBytes, double x, double y, double width, double height) {
        BufferedImage source = decode(imageBytes, "图片无法解析");
        try {
            int left = clamp((int) Math.round(x * source.getWidth()), 0, source.getWidth() - 1);
            int top = clamp((int) Math.round(y * source.getHeight()), 0, source.getHeight() - 1);
            int right = clamp((int) Math.round((x + width) * source.getWidth()), left + 1, source.getWidth());
            int bottom = clamp((int) Math.round((y + height) * source.getHeight()), top + 1, source.getHeight());
            if (right - left < 1 || bottom - top < 1) {
                throw new DomainException("REGION_CROP_EMPTY", "该区域太小，无法裁出题图");
            }
            BufferedImage cropped = new BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = cropped.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, cropped.getWidth(), cropped.getHeight(),
                    left, top, right, bottom, null);
            } finally {
                graphics.dispose();
            }
            return encode(cropped);
        } finally {
            source.flush();
        }
    }

    /**
     * 生成缩略图。
     *
     * <p>原图比目标宽度还窄时返回原图字节：放大只会得到一张更模糊的图，白花一次编码。
     */
    public static byte[] thumbnail(byte[] imageBytes) {
        BufferedImage source = decode(imageBytes, "页面图无法解析");
        try {
            if (source.getWidth() <= THUMBNAIL_WIDTH) {
                return imageBytes;
            }
            int height = Math.max(1, (int) Math.round(
                source.getHeight() * (double) THUMBNAIL_WIDTH / source.getWidth()));
            BufferedImage scaled = new BufferedImage(THUMBNAIL_WIDTH, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, THUMBNAIL_WIDTH, height, null);
            } finally {
                graphics.dispose();
            }
            return encode(scaled);
        } finally {
            source.flush();
        }
    }

    /** 直角旋转的结果：旋转后的图片字节，以及旋转后画布的像素尺寸。 */
    public record RotatedImage(byte[] content, int width, int height) {
    }

    /**
     * 直角旋转。
     *
     * <p>{@code degrees} 是目标角度，不是增量：调用方每次传的都是"这一页应该显示成什么角度"，
     * 所以连点两下 90 得到的是 90 度而不是 180 度，也正因为每次都从原图重算，
     * 90 度再转回 0 度不会因为两次重采样而变糊。传 0 返回原字节，不做一次白费的编码。
     *
     * <p>尺寸随结果一起返回而不是让调用方自己算：转 90 或 270 度时宽高互换，而调用方手上的
     * 宽高多半已经是上一次旋转的结果（四十宽八十高），照着它再换一次就换回去了 ——
     * 旋转两次宽高会打回原形。尺寸必须由这张原图算出来。
     *
     * <p>画布按旋转后的尺寸新建，并把源图中心对齐到画布中心：对齐之后旋转后的源图正好铺满
     * 画布，不会留出一条黑边。
     */
    public static RotatedImage rotate(byte[] imageBytes, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new DomainException("IMAGE_ROTATION_INVALID", "只支持 90、180、270 度的直角旋转",
                HttpStatus.BAD_REQUEST);
        }
        BufferedImage source = decode(imageBytes, "页面图无法解析");
        try {
            if (normalized == 0) {
                return new RotatedImage(imageBytes, source.getWidth(), source.getHeight());
            }
            boolean quarterTurn = normalized != 180;
            int width = quarterTurn ? source.getHeight() : source.getWidth();
            int height = quarterTurn ? source.getWidth() : source.getHeight();
            BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rotated.createGraphics();
            try {
                graphics.rotate(Math.toRadians(normalized), width / 2.0, height / 2.0);
                graphics.drawImage(source,
                    (int) Math.round((width - source.getWidth()) / 2.0),
                    (int) Math.round((height - source.getHeight()) / 2.0), null);
            } finally {
                graphics.dispose();
            }
            return new RotatedImage(encode(rotated), width, height);
        } finally {
            source.flush();
        }
    }

    private static BufferedImage decode(byte[] bytes, String message) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new DomainException("IMAGE_DECODE_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return image;
        } catch (IOException exception) {
            throw new DomainException("IMAGE_DECODE_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY, exception);
        }
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new DomainException("IMAGE_ENCODE_FAILED", "派生图片写入失败",
                HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
