package com.checker.service;

import com.checker.common.DedupeSettings;
import com.checker.mapper.DedupeSettingsMapper;
import com.checker.mapper.GalleryPageHashMapper;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DedupeSettingsMapperTest {
    @Test void savingBindsEveryRecordValueInOneFixedRowUpsert() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(DedupeSettingsMapper.class);
        MappedStatement statement = configuration.getMappedStatement(DedupeSettingsMapper.class.getName() + ".save");
        DedupeSettings changed = new DedupeSettings(70, 92, 90, 12, 5, 85, 8);
        Map<String, Object> parameters = Map.of("settings", changed);
        var sql = statement.getBoundSql(parameters);
        assertEquals(List.of("settings.reviewScore", "settings.autoMatchScore", "settings.titleSimilarityPercent",
                "settings.visualPageDistance", "settings.visualMinMatchedPages", "settings.visualMinCoverage",
                "settings.visualQualityDelta"), sql.getParameterMappings().stream().map(value -> value.getProperty()).toList());
        assertTrue(sql.getSql().contains("VALUES (1, ?"));
        assertTrue(sql.getSql().contains("ON DUPLICATE KEY UPDATE"));
        PreparedStatement jdbc = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameters, sql).setParameters(jdbc);
        int[] expected = {70, 92, 90, 12, 5, 85, 8};
        for (int i = 0; i < expected.length; i++) verify(jdbc).setInt(i + 1, expected[i]);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void readingUsesTheSameConstructorOrderAndFingerprintCountRunsInTheDatabase() {
        Configuration configuration = new Configuration();
        configuration.addMapper(DedupeSettingsMapper.class);
        configuration.addMapper(GalleryPageHashMapper.class);
        MappedStatement statement = configuration.getMappedStatement(DedupeSettingsMapper.class.getName() + ".current");
        assertTrue(statement.getBoundSql(null).getSql().contains("WHERE id = 1"));
        assertEquals(List.of("review_score", "auto_match_score", "title_similarity_percent", "visual_page_distance",
                        "visual_min_matched_pages", "visual_min_coverage", "visual_quality_delta"),
                statement.getResultMaps().get(0).getConstructorResultMappings().stream().map(value -> value.getColumn()).toList());
        String count = configuration.getMappedStatement(GalleryPageHashMapper.class.getName() + ".countCurrentGalleries")
                .getBoundSql(Map.of("version", 1)).getSql();
        assertTrue(count.contains("COUNT(DISTINCT gid)"));
        assertTrue(count.contains("algorithm_version = ?"));
    }
}
