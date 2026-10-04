package com.checker.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class EmbeddingCodec {
    private EmbeddingCodec() {
    }

    public static byte[] encode(float[] values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("向量不能为空");
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }

    public static float[] decode(byte[] bytes, int dimensions) {
        if (bytes == null || dimensions <= 0 || bytes.length != dimensions * Float.BYTES) {
            throw new IllegalArgumentException("向量二进制长度与维度不一致");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[dimensions];
        for (int i = 0; i < dimensions; i++) values[i] = buffer.getFloat();
        return values;
    }
}
