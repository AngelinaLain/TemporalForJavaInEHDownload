package com.checker.common;

import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryDeduplicationTest {

    @Test
    void groupsChineseTranslationsByOriginalTitleAndCoreTags() {
        EhGalleriesEntity simplified = gallery(101L, "[汉化组 A] 中文标题", "作品原題", 4.2D, 28);
        EhGalleriesEntity traditional = gallery(102L, "[繁中] 另一個中文標題", "作品原題", 4.5D, 28);

        GalleryDeduplication.populateIdentity(simplified);
        GalleryDeduplication.populateIdentity(traditional);

        assertTrue(GalleryDeduplication.isIdentifiable(simplified));
        assertEquals(simplified.getDedupeKey(), traditional.getDedupeKey());
        assertEquals(100, simplified.getDedupeConfidence());
        assertEquals(traditional, GalleryDeduplication.choosePreferred(List.of(simplified, traditional)));
    }

    @Test
    void keepsDifferentOriginalWorksSeparate() {
        EhGalleriesEntity first = gallery(101L, "中文标题", "原作 A", 4.2D, 28);
        EhGalleriesEntity second = gallery(102L, "中文标题", "原作 B", 4.9D, 40);

        GalleryDeduplication.populateIdentity(first);
        GalleryDeduplication.populateIdentity(second);

        assertNotEquals(first.getDedupeKey(), second.getDedupeKey());
    }

    private EhGalleriesEntity gallery(Long gid, String title, String originalTitle, Double rating, Integer pageCount) {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(gid);
        gallery.setTitle(title);
        gallery.setOriginalTitle(originalTitle);
        gallery.setRating(rating);
        gallery.setPageCount(pageCount);
        gallery.setTags(List.of("parody:original-work", "character:heroine", "artist:author"));
        return gallery;
    }
}
