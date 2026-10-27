package com.checker.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

final class PreviewImageCodec {
    private PreviewImageCodec() {}

    static String mime(byte[] bytes) throws IOException {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216) return "image/jpeg";
        if (bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return "image/png";
        if (bytes.length >= 6 && new String(bytes, 0, 3, StandardCharsets.US_ASCII).equals("GIF")) return "image/gif";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        if (bytes.length >= 12 && new String(bytes, 4, 8, StandardCharsets.US_ASCII).startsWith("ftypavi")) return "image/avif";
        if (bytes.length >= 2 && bytes[0] == 66 && bytes[1] == 77) return "image/bmp";
        if (bytes.length >= 4 && ((bytes[0] == 73 && bytes[1] == 73 && bytes[2] == 42)
                || (bytes[0] == 77 && bytes[1] == 77 && bytes[3] == 42))) return "image/tiff";
        throw new IOException("图片源未返回有效图片");
    }

    static byte[] thumbnail(byte[] input) throws IOException {
        mime(input);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("当前图片格式不支持缩略图");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 80_000_000L) throw new IOException("图片尺寸超出预览限制");
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / 600);
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage source = reader.read(0, params);
                double scale = Math.min(1, 300.0 / Math.max(source.getWidth(), source.getHeight()));
                BufferedImage result = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)),
                        Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = result.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null);
                } finally {
                    graphics.dispose();
                    source.flush();
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(result, "jpg", output)) throw new IOException("缩略图编码失败");
                result.flush();
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }
}
