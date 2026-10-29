package com.checker.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.checker.common.DedupeSettings;
import com.checker.entity.VisualMatchEntity;
import com.checker.mapper.GalleryPageHashMapper;
import com.checker.mapper.VisualMatchMapper;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisualFingerprintRecommendationPersistenceTest {
    @Test void updateByIdIncludesNullRecommendationSoAnOldRecommendationIsActuallyCleared() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(VisualMatchMapper.class);
        MappedStatement statement = configuration.getMappedStatement(VisualMatchMapper.class.getName() + ".updateById");
        VisualMatchEntity row = new VisualMatchEntity();
        row.setId(42L);
        row.setRecommendedGid(null);
        Map<String, Object> parameters = Map.of("et", row);
        var sql = statement.getBoundSql(parameters);

        assertTrue(sql.getSql().matches("(?s).*recommended_gid\\s*=\\s*\\?.*"), sql.getSql());
        List<String> properties = sql.getParameterMappings().stream().map(value -> value.getProperty()).toList();
        int recommendationParameter = properties.indexOf("et.recommendedGid") + 1;
        assertTrue(recommendationParameter > 0, properties.toString());
        PreparedStatement jdbc = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameters, sql).setParameters(jdbc);
        verify(jdbc).setNull(eq(recommendationParameter), anyInt());
        verify(jdbc).setLong(properties.indexOf("et.id") + 1, 42L);
    }

    @Test void recomparingWithInsufficientEvidencePreservesTheRowIdAndReplacesTheOldRecommendationWithNull() {
        GalleryPageHashMapper hashes = mock(GalleryPageHashMapper.class);
        VisualMatchMapper matches = mock(VisualMatchMapper.class);
        DedupeSettingsService settings = mock(DedupeSettingsService.class);
        VisualMatchEntity existing = new VisualMatchEntity();
        existing.setId(42L);
        existing.setRecommendedGid(2L);
        when(hashes.selectList(any())).thenReturn(List.of());
        when(matches.selectOne(any())).thenReturn(existing);
        VisualFingerprintService service = new VisualFingerprintService(hashes, matches, settings);

        var result = service.matchAndPersist(2L, 1L, DedupeSettings.defaults());
        assertNull(result.getRecommendedGid());
        assertNull(existing.getRecommendedGid());
        assertEquals(42L, existing.getId());
        assertEquals(1L, existing.getLeftGid());
        assertEquals(2L, existing.getRightGid());
        verify(matches).updateById(same(existing));
        verify(matches, never()).insert(any());
        verifyNoInteractions(settings);
    }
}
