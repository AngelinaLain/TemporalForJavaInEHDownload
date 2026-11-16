package com.checker.rss;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class RssFeedClientTest {
    private static byte[] xml(String items) { return ("<rss><channel>"+items+"</channel></rss>").getBytes(StandardCharsets.UTF_8); }
    @Test void parsesAndDeduplicatesGalleryLinks() throws Exception {
        String item="<item><title><![CDATA[Title & text]]></title><link>https://e-hentai.org/g/123/abc/</link><pubDate>Mon, 16 Nov 2026 01:30:00 GMT</pubDate></item>";
        var parsed=RssFeedClient.parse(xml(item+item));
        assertEquals(1,parsed.size()); assertEquals("abc",parsed.get(0).token()); assertEquals("Title & text",parsed.get(0).title());
    }
    @Test void rejectsExternalEntitiesHtmlUnknownDatesAndForeignLinks() {
        assertThrows(Exception.class,()->RssFeedClient.parse("<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///sensitive'>]><rss><channel>&xxe;</channel></rss>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,()->RssFeedClient.parse("<html>login</html>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,()->RssFeedClient.parse(xml("<item><title>x</title><link>http://127.0.0.1/admin</link><pubDate>Mon, 16 Nov 2026 01:30:00 GMT</pubDate></item>")));
        assertThrows(Exception.class,()->RssFeedClient.parse(xml("<item><title>x</title><link>https://e-hentai.org/g/123/abc/</link></item>")));
    }
}
