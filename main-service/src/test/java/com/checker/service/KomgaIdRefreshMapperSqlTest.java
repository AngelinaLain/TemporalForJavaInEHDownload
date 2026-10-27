package com.checker.service;

import com.checker.mapper.EhGalleriesMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KomgaIdRefreshMapperSqlTest {
    @Test void casSqlUsesRealNullSafeOperatorsAndBindsEveryIdentityField() throws Exception {
        Update annotation = EhGalleriesMapper.class.getMethod("compareAndSetKomgaBookId", long.class,
                String.class, String.class, String.class, String.class).getAnnotation(Update.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("gid", 42L); parameters.put("oldId", null); parameters.put("newId", "new-book");
        parameters.put("filename", "[42] title.cbz"); parameters.put("storagePath", null);
        BoundSql bound = new XMLLanguageDriver().createSqlSource(new Configuration(),
                String.join(" ", annotation.value()), Map.class).getBoundSql(parameters);
        assertFalse(bound.getSql().contains("&lt;"));
        assertEquals(3, bound.getSql().split("<=>", -1).length - 1);
        assertEquals(List.of("newId", "gid", "oldId", "filename", "storagePath"),
                bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
        String writeColumns = bound.getSql().split("WHERE")[0];
        assertTrue(writeColumns.contains("komga_book_id")); assertTrue(writeColumns.contains("updated_at"));
        assertFalse(writeColumns.contains("download_status")); assertFalse(writeColumns.contains("filename"));
    }

    @Test void defaultScopeAndSelectedGidsAreParsedWithoutLiteralInterpolation() throws Exception {
        Select annotation = EhGalleriesMapper.class.getMethod("getKomgaIdRefreshBatch", long.class,
                long.class, List.class, int.class).getAnnotation(Select.class);
        var source = new XMLLanguageDriver().createSqlSource(new Configuration(), String.join(" ", annotation.value()), Map.class);
        String defaults = source.getBoundSql(Map.of("after", 0L, "maxGid", 50L, "gids", List.of(), "size", 200)).getSql();
        assertTrue(defaults.contains("download_status IN ('IMPORTED','已入库')"));
        assertTrue(defaults.contains("gid > ? AND gid <= ?")); assertTrue(defaults.contains("LIMIT ?"));
        String selected = source.getBoundSql(Map.of("after", 0L, "maxGid", 50L, "gids", List.of(42L), "size", 200)).getSql();
        assertTrue(selected.contains("gid IN")); assertFalse(selected.contains("42"));
        assertFalse(selected.contains("download_status")); assertFalse(selected.contains("&gt;"));
    }
}
