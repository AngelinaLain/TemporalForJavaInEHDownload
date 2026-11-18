package com.checker.manager.search;
import com.checker.search.EhSearchQueryBuilder;
import com.checker.dto.SearchOptions;
import org.junit.jupiter.api.Test;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class EhRssSearchTest {
    @Test void queryAndRoutePreserveComplexSearchAndAllFilters() {
        var options=SearchOptions.builder().keyword("artist:\"a/b & 中文\" -tag:test$").language("chinese")
                .filterCats(1021).minimumRating(4).pageAtLeast(20).pageAtMost(80)
                .searchExpungedGalleries(true).showOnlyWithTorrents(true).disableLanguageFilter(true)
                .disableUploaderFilter(true).disableTagsFilter(true).build();
        String query=EhSearchQueryBuilder.query(options);
        String segment=EhSearchQueryBuilder.rssRoute(options).split("/")[3];
        assertEquals(query,URLDecoder.decode(segment,StandardCharsets.UTF_8));
        assertTrue(query.contains("&f_cats=1021&advsearch=1&f_srdd=4&f_spf=20&f_spt=80"));
        assertTrue(query.endsWith("&f_sh=on&f_sto=on&f_sfl=on&f_sfu=on&f_sft=on"));
        assertTrue(URLDecoder.decode(query,StandardCharsets.UTF_8).contains("artist:\"a/b & 中文\" -tag:test$ language:\"chinese\""));
    }
    @Test void listingUsesSourceTimestampAndPreservesNextCursor() {
        String html="<table><tr><td><div id='posted_123'>2026-11-16 01:30</div></td><td><a href='https://e-hentai.org/g/123/abc/'><div class='glink'>Title</div></a></td></tr></table><a id='unext' href='/?f_search=x&amp;next=122'>Next</a>";
        var result=EhSearchPageParser.parse(html,"https://e-hentai.org/?f_search=x");
        assertEquals(1,result.items().size());
        assertEquals(123L,result.items().get(0).gid());
        assertEquals(java.time.Instant.parse("2026-11-16T01:30:00Z").toEpochMilli(),result.items().get(0).publishedAt());
        assertEquals("https://e-hentai.org/?f_search=x&next=122",result.nextUrl());
        assertFalse(result.endOfResults());
    }
    @Test void errorPagesAndUnsafeCursorsNeverLookLikeEndOfResults() {
        assertThrows(IllegalStateException.class,()->EhSearchPageParser.parse("<html>Please log in</html>","https://e-hentai.org/?f_search=x"));
        assertThrows(IllegalArgumentException.class,()->EhSearchPageParser.validateCursor("https://e-hentai.org@127.0.0.1/"));
        assertThrows(IllegalArgumentException.class,()->EhSearchPageParser.validateCursor("https://e-hentai.org:443/"));
        assertTrue(EhSearchPageParser.parse("<p>No hits found</p>","https://e-hentai.org/?f_search=x").endOfResults());
    }
}
