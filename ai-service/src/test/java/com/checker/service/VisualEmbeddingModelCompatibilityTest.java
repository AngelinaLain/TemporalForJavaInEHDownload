package com.checker.service;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.checker.config.VisualEmbeddingProperties;
import com.checker.common.VectorSimilarity;
import com.checker.dto.VisualEmbedding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "AI_MODEL_TEST_DIR", matches = ".+")
class VisualEmbeddingModelCompatibilityTest {
    private static final Map<String, float[]> EMBEDDINGS = new ConcurrentHashMap<>();

    @TestFactory
    Stream<DynamicTest> validatesConfiguredOnnxModels() throws Exception {
        Path directory = Path.of(System.getenv("AI_MODEL_TEST_DIR")).toAbsolutePath().normalize();
        List<Path> models;
        try (Stream<Path> files = Files.list(directory)) {
            models = files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".onnx"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertTrue(!models.isEmpty(), "模型目录中没有 ONNX 文件: " + directory);
        byte[] image = testImage();
        return models.stream().map(model -> DynamicTest.dynamicTest(model.getFileName().toString(),
                () -> validate(model, image)));
    }

    private void validate(Path model, byte[] image) throws Exception {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession session = OrtEnvironment.getEnvironment().createSession(model.toString(), options)) {
            System.out.printf("MODEL %s (%d bytes)%n", model.getFileName(), Files.size(model));
            printNodes("  input", session.getInputInfo());
            printNodes("  output", session.getOutputInfo());
        }

        VisualEmbeddingProperties properties = new VisualEmbeddingProperties();
        properties.setEnabled(true);
        properties.setModelPath(model.toString());
        properties.setModelName(model.getFileName().toString());
        properties.setModelVersion("compatibility-test");
        properties.setInputSize(224);
        try (AutoCloseableEmbeddingService service = new AutoCloseableEmbeddingService(properties)) {
            long coldStart = System.nanoTime();
            VisualEmbedding embedding = service.embed(image);
            EMBEDDINGS.put(model.getFileName().toString(), embedding.getValues().clone());
            double coldMillis = (System.nanoTime() - coldStart) / 1_000_000D;
            assertEquals(384, embedding.getDimensions(), "DINOv2-small 应输出 384 维向量");
            assertEquals(384, embedding.getValues().length);
            double norm = 0D;
            for (float value : embedding.getValues()) {
                assertTrue(Float.isFinite(value), "向量不能包含 NaN 或 Infinity");
                norm += (double) value * value;
            }
            assertEquals(1D, Math.sqrt(norm), 0.0001D, "输出应经过 L2 归一化");
            int warmupRuns = 3;
            long warmStart = System.nanoTime();
            for (int i = 0; i < warmupRuns; i++) {
                VisualEmbedding repeated = service.embed(image);
                assertTrue(VectorSimilarity.cosine(embedding.getValues(), repeated.getValues()) > 0.999999D,
                        "相同输入的推理结果应保持稳定");
            }
            double warmAverageMillis = (System.nanoTime() - warmStart) / 1_000_000D / warmupRuns;
            System.out.printf("  inference: OK, dimensions=%d, norm=%.6f, cold=%.2fms, warmAvg=%.2fms%n",
                    embedding.getDimensions(), Math.sqrt(norm), coldMillis, warmAverageMillis);
        }
    }

    @AfterAll
    static void compareQuantizedModelsWithFp32() {
        float[] baseline = EMBEDDINGS.get("dinov2-small-ONNX.onnx");
        assertNotNull(baseline, "FP32 基准模型未完成验证");

        EMBEDDINGS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    double cosine = VectorSimilarity.cosine(baseline, entry.getValue());
                    System.out.printf("[model-quality] %-38s cosineToFp32=%.8f%n", entry.getKey(), cosine);
                    assertTrue(cosine > 0.90D,
                            () -> entry.getKey() + " 与 FP32 基准偏差过大: " + cosine);
                });
    }

    private static void printNodes(String prefix, Map<String, NodeInfo> nodes) {
        nodes.forEach((name, info) -> System.out.printf("%s %s: %s%n", prefix, name, info.getInfo()));
    }

    private static byte[] testImage() throws Exception {
        BufferedImage image = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = x * 255 / (image.getWidth() - 1);
                int green = y * 255 / (image.getHeight() - 1);
                int blue = (x + y) * 255 / (image.getWidth() + image.getHeight() - 2);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /** Makes the production service usable with try-with-resources in this focused compatibility test. */
    private static final class AutoCloseableEmbeddingService extends VisualEmbeddingService implements AutoCloseable {
        private AutoCloseableEmbeddingService(VisualEmbeddingProperties properties) {
            super(properties);
        }

        @Override
        public void close() throws ai.onnxruntime.OrtException {
            super.close();
        }
    }
}
