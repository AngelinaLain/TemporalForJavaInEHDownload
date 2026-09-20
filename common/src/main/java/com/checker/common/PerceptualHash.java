package com.checker.common;

import com.checker.dto.GalleryPageFingerprint;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Iterator;

/** Pure-Java 64-bit DCT perceptual hashing with bounded image decoding. */
public final class PerceptualHash {
    public static final int ALGORITHM_VERSION = 1;
    private static final int HASH_SIZE = 8;
    private static final int DCT_SIZE = 32;
    private static final int MAX_DECODE_DIMENSION = 1600;

    private PerceptualHash() {
    }

    public static GalleryPageFingerprint fingerprint(InputStream input, Long gid, int pageIndex,
                                                     String pageName, String source) throws IOException {
        DecodedImage decoded = readBounded(input);
        if (decoded == null || decoded.image() == null) return null;
        BufferedImage image = decoded.image();
        String full = hash(image);
        int cropX = Math.max(0, image.getWidth() / 10);
        int cropY = Math.max(0, image.getHeight() / 10);
        int cropWidth = Math.max(1, image.getWidth() - cropX * 2);
        int cropHeight = Math.max(1, image.getHeight() - cropY * 2);
        BufferedImage center = image.getSubimage(cropX, cropY, cropWidth, cropHeight);
        String centerHash = hash(center);
        int quality = quality(image);
        return GalleryPageFingerprint.builder()
                .gid(gid)
                .pageIndex(pageIndex)
                .pageName(pageName)
                .source(source)
                .perceptualHash(full)
                .centerHash(centerHash)
                .quality(quality)
                .width(decoded.originalWidth())
                .height(decoded.originalHeight())
                .algorithmVersion(ALGORITHM_VERSION)
                .build();
    }

    public static int distance(String left, String right) {
        if (left == null || right == null || left.length() != 16 || right.length() != 16) return 64;
        try {
            return Long.bitCount(Long.parseUnsignedLong(left, 16) ^ Long.parseUnsignedLong(right, 16));
        } catch (NumberFormatException ignored) {
            return 64;
        }
    }

    private static DecodedImage readBounded(InputStream input) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) return null;
                int largest = Math.max(width, height);
                int subsampling = Math.max(1, (int) Math.ceil(largest / (double) MAX_DECODE_DIMENSION));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                try {
                    return new DecodedImage(reader.read(0, param), width, height);
                } catch (IllegalArgumentException colorSpaceFailure) {
                    // Some JPEGs contain an ICC profile whose component count does not match
                    // their CMYK/YCCK raster. Read raw samples to bypass ImageIO color conversion.
                    imageInput.seek(0);
                    reader.reset();
                    reader.setInput(imageInput, true, true);
                    ImageReadParam rasterParam = reader.getDefaultReadParam();
                    rasterParam.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    try {
                        return new DecodedImage(rgbFromRaster(reader.readRaster(0, rasterParam)), width, height);
                    } catch (IOException | RuntimeException fallbackFailure) {
                        fallbackFailure.addSuppressed(colorSpaceFailure);
                        throw fallbackFailure;
                    }
                }
            } finally {
                reader.dispose();
            }
        }
    }

    static BufferedImage rgbFromRaster(Raster raster) {
        int width = raster.getWidth();
        int height = raster.getHeight();
        int bands = raster.getNumBands();
        if (width <= 0 || height <= 0 || bands <= 0) {
            throw new IllegalArgumentException("无法转换空的图片 Raster");
        }
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] samples = new int[bands];
        int minX = raster.getMinX();
        int minY = raster.getMinY();
        int[] sampleSizes = raster.getSampleModel().getSampleSize();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.getPixel(minX + x, minY + y, samples);
                int first = to8Bit(samples[0], sampleSizes[0]);
                int red;
                int green;
                int blue;
                if (bands >= 4) {
                    int cyan = first;
                    int magenta = to8Bit(samples[1], sampleSizes[1]);
                    int yellow = to8Bit(samples[2], sampleSizes[2]);
                    int black = to8Bit(samples[3], sampleSizes[3]);
                    red = 255 - Math.min(255, cyan + black);
                    green = 255 - Math.min(255, magenta + black);
                    blue = 255 - Math.min(255, yellow + black);
                } else if (bands >= 3) {
                    red = first;
                    green = to8Bit(samples[1], sampleSizes[1]);
                    blue = to8Bit(samples[2], sampleSizes[2]);
                } else {
                    red = green = blue = first;
                }
                rgb.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return rgb;
    }

    private static int to8Bit(int value, int bits) {
        if (bits <= 0 || bits == 8) return Math.max(0, Math.min(255, value));
        long max = bits >= 31 ? Integer.MAX_VALUE : (1L << bits) - 1L;
        return (int) Math.max(0, Math.min(255, Math.round(value * 255D / max)));
    }

    private static String hash(BufferedImage source) {
        BufferedImage scaled = new BufferedImage(DCT_SIZE, DCT_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, DCT_SIZE, DCT_SIZE, null);
        } finally {
            graphics.dispose();
        }

        double[][] pixels = new double[DCT_SIZE][DCT_SIZE];
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                pixels[y][x] = scaled.getRaster().getSample(x, y, 0);
            }
        }
        double[][] low = dctLowFrequency(pixels);
        double sum = 0;
        int count = 0;
        for (int y = 0; y < HASH_SIZE; y++) {
            for (int x = 0; x < HASH_SIZE; x++) {
                if (x != 0 || y != 0) {
                    sum += low[y][x];
                    count++;
                }
            }
        }
        double mean = sum / count;
        long bits = 0;
        int bit = 0;
        for (int y = 0; y < HASH_SIZE; y++) {
            for (int x = 0; x < HASH_SIZE; x++) {
                if (low[y][x] > mean) bits |= 1L << bit;
                bit++;
            }
        }
        return HexFormat.of().toHexDigits(bits);
    }

    private static double[][] dctLowFrequency(double[][] pixels) {
        double[][] result = new double[HASH_SIZE][HASH_SIZE];
        for (int v = 0; v < HASH_SIZE; v++) {
            for (int u = 0; u < HASH_SIZE; u++) {
                double sum = 0;
                for (int y = 0; y < DCT_SIZE; y++) {
                    for (int x = 0; x < DCT_SIZE; x++) {
                        sum += pixels[y][x]
                                * Math.cos((2 * x + 1) * u * Math.PI / (2 * DCT_SIZE))
                                * Math.cos((2 * y + 1) * v * Math.PI / (2 * DCT_SIZE));
                    }
                }
                double cu = u == 0 ? 1 / Math.sqrt(2) : 1;
                double cv = v == 0 ? 1 / Math.sqrt(2) : 1;
                result[v][u] = 0.25 * cu * cv * sum;
            }
        }
        return result;
    }

    private static int quality(BufferedImage image) {
        BufferedImage small = new BufferedImage(128, 128, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, 128, 128, null);
        } finally {
            graphics.dispose();
        }
        double sum = 0;
        double squares = 0;
        double edges = 0;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int value = small.getRaster().getSample(x, y, 0);
                sum += value;
                squares += value * value;
                if (x > 0) edges += Math.abs(value - small.getRaster().getSample(x - 1, y, 0));
                if (y > 0) edges += Math.abs(value - small.getRaster().getSample(x, y - 1, 0));
            }
        }
        double variance = squares / (128D * 128D) - Math.pow(sum / (128D * 128D), 2);
        double edgeMean = edges / (2D * 128D * 127D);
        return (int) Math.round(Math.max(0, Math.min(100, Math.sqrt(Math.max(0, variance)) * 1.4 + edgeMean * 1.2)));
    }

    private record DecodedImage(BufferedImage image, int originalWidth, int originalHeight) {
    }
}
