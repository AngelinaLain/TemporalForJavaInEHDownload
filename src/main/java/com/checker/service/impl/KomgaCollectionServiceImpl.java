package com.checker.service.impl;

import com.checker.common.KomgaApiClient;
import com.checker.dto.KomgaCollectionRequest;
import com.checker.service.KomgaCollectionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class KomgaCollectionServiceImpl implements KomgaCollectionService {
    @Autowired
    private KomgaApiClient komgaApiClient;
    @Override
    public void processCollectionAssignment(KomgaCollectionRequest request) {
        try {
            Set<String> targetSeriesIds = new HashSet<>();

            // 1. 遍历需要匹配的 Tags，查出 Komga 库中拥有这些 Tag 的所有画廊 ID
            for (String tag : request.getTags()) {
                List<String> ids = komgaApiClient.findSeriesIdsByTag(tag);
                targetSeriesIds.addAll(ids); // 使用 Set 自动去重
            }

            if (targetSeriesIds.isEmpty()) {
                log.info("未找到包含标签 {} 的画廊，跳过合集 [{}] 的处理", request.getTags(), request.getCollectionName());
                return;
            }

            // 2. 检查 Komga 中是否已经存在同名的合集
            JsonNode existingCollection = komgaApiClient.findCollectionByName(request.getCollectionName());

            if (existingCollection != null) {
                // 3. 合集已存在：将旧画廊 ID 与新查询到的画廊 ID 合并，然后更新
                String collectionId = existingCollection.get("id").asText();
                JsonNode existingSeriesIdsNode = existingCollection.get("seriesIds");

                Set<String> mergedIds = new HashSet<>(targetSeriesIds);
                if (existingSeriesIdsNode != null && existingSeriesIdsNode.isArray()) {
                    for (JsonNode idNode : existingSeriesIdsNode) {
                        mergedIds.add(idNode.asText());
                    }
                }

                komgaApiClient.updateCollection(collectionId, new ArrayList<>(mergedIds));
                log.info("✅ 成功更新 Komga 合集 [{}], 合集内总画廊数: {}", request.getCollectionName(), mergedIds.size());

            } else {
                // 4. 合集不存在：直接创建新合集并塞入画廊
                komgaApiClient.createCollection(request.getCollectionName(), new ArrayList<>(targetSeriesIds));
                log.info("✅ 成功创建新 Komga 合集 [{}], 放入画廊数: {}", request.getCollectionName(), targetSeriesIds.size());
            }

        } catch (Exception e) {
            log.error("处理 Komga 合集 [{}] 发生异常: {}", request.getCollectionName(), e.getMessage(), e);
        }
    }
}
