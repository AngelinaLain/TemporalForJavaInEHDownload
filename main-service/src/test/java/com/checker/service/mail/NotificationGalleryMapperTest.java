package com.checker.service.mail;

import com.checker.mapper.NotificationGalleryMapper;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotificationGalleryMapperTest {
    @Test void queryOnlySelectsDisplayFieldsAndBindsExactIdsWithoutInterpolation() {
        Configuration configuration = new Configuration();
        configuration.addMapper(NotificationGalleryMapper.class);
        MappedStatement statement = configuration.getMappedStatement(NotificationGalleryMapper.class.getName() + ".findForNotification");
        var bound = statement.getBoundSql(Map.of("gids", List.of(1553036L, 1553037L)));
        String sql = bound.getSql();
        assertFalse(sql.contains("1553036"));
        assertFalse(sql.contains("SELECT *"));
        assertFalse(sql.contains("token"));
        assertFalse(sql.contains("storage_path"));
        assertFalse(sql.contains("trace"));
        assertTrue(sql.contains("LIMIT 10"));
        assertEquals(2, bound.getParameterMappings().size());
        assertEquals(List.of("gid", "title", "original_title", "status", "page_count", "rating", "file_size_mb",
                        "duplicate_of_gid", "confirmation_attempts", "reason", "komga_book_id"),
                statement.getResultMaps().get(0).getConstructorResultMappings().stream().map(value -> value.getColumn()).toList());
    }
}
