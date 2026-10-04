package com.checker.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.checker.config.VisualEmbeddingProperties;
import com.checker.dto.VisualEmbedding;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VisualEmbeddingService {
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final VisualEmbeddingProperties properties;
    private final Object sessionLock = new Object();
    private volatile OrtSession session;
    private volatile OrtEnvironment environment;

    public VisualEmbeddingService(VisualEmbeddingProperties properties) {
        this.properties = properties;
    }

    public VisualEmbedding embed(byte[] imageBytes) throws IOException, OrtException {
        if (!properties.isEnabled()) throw new IllegalStateException("本地视觉向量功能未启用");
        if (imageBytes == null || imageBytes.length == 0) throw new IllegalArgumentException("图片不能为空");
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null) throw new IllegalArgumentException("无法解码图片");
        if ((long) source.getWidth() * source.getHeight() > properties.getMaxImagePixels()) {
            throw new IllegalArgumentException("图片像素数超过安全限制");
        }

        OrtSession current = requireSession();
        float[][][][] input = preprocess(source, properties.getInputSize());
        String inputName = current.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input);
             OrtSession.Result result = current.run(Map.of(inputName, tensor))) {
            float[] values = l2Normalize(extractEmbedding(result.get(0).getValue()));
            return VisualEmbedding.builder()
                    .model(properties.getModelName())
                    .modelVersion(properties.getModelVersion())
                    .dimensions(values.length)
                    .values(values)
                    .build();
        }
    }

    public Map<String, Object> status() {
        Path model = modelPath();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("available", properties.isEnabled() && Files.isRegularFile(model));
        status.put("model", properties.getModelName());
        status.put("modelVersion", properties.getModelVersion());
        status.put("inputSize", properties.getInputSize());
        status.put("modelPath", model.toString());
        return status;
    }

    private OrtSession requireSession() throws OrtException {
        OrtSession existing = session;
        if (existing != null) return existing;
        synchronized (sessionLock) {
            if (session != null) return session;
            Path model = modelPath();
            if (!Files.isRegularFile(model)) {
                throw new IllegalStateException("未找到视觉模型: " + model);
            }
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            if (properties.getIntraOpThreads() > 0) {
                options.setIntraOpNumThreads(properties.getIntraOpThreads());
            }
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(model.toString(), options);
            return session;
        }
    }

    private Path modelPath() {
        return Path.of(properties.getModelPath()).toAbsolutePath().normalize();
    }

    private static float[][][][] preprocess(BufferedImage source, int size) {
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        float[][][][] tensor = new float[1][3][size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = resized.getRGB(x, y);
                tensor[0][0][y][x] = ((((rgb >> 16) & 0xff) / 255f) - MEAN[0]) / STD[0];
                tensor[0][1][y][x] = ((((rgb >> 8) & 0xff) / 255f) - MEAN[1]) / STD[1];
                tensor[0][2][y][x] = (((rgb & 0xff) / 255f) - MEAN[2]) / STD[2];
            }
        }
        return tensor;
    }

    private static float[] extractEmbedding(Object output) {
        if (output instanceof float[][][] tokens && tokens.length > 0 && tokens[0].length > 0) {
            return tokens[0][0].clone();
        }
        if (output instanceof float[][] batch && batch.length > 0) {
            return batch[0].clone();
        }
        if (output instanceof float[] vector) return vector.clone();
        throw new IllegalStateException("视觉模型输出格式不受支持: "
                + (output == null ? "null" : output.getClass().getTypeName()));
    }

    private static float[] l2Normalize(float[] values) {
        double norm = 0D;
        for (float value : values) norm += (double) value * value;
        if (norm == 0D) throw new IllegalStateException("视觉模型返回了零向量");
        double divisor = Math.sqrt(norm);
        for (int i = 0; i < values.length; i++) values[i] = (float) (values[i] / divisor);
        return values;
    }

    @PreDestroy
    public void close() throws OrtException {
        if (session != null) session.close();
        session = null;
    }
}
