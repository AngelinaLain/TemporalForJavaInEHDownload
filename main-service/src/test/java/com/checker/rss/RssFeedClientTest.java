package com.checker.rss;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import com.checker.dto.SearchOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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

    @Test void http503ShowsActualProxyFailureWithoutLeakingCredentials() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String message = "FetchError: fetch failed (Proxy response (403) !== 200 when HTTP Tunneling) "
                + "http://alice:secret%40password@proxy.example:7893 Authorization: Basic dXNlcjpwYXNz ipb_pass_hash=secret-hash";
        String response = "<html><script>doNotDisplayThis()</script><p class='message'>Error Message:<code class='details'>" + message + "</code></p><p>Footer</p></html>";
        server.createContext("/", exchange -> {
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
            exchange.sendResponseHeaders(503,bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        try {
            var config = new RssConfig();config.setHubUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var error = assertThrows(IllegalStateException.class, () -> new RssFeedClient(config).fetch(SearchOptions.builder().keyword("test").build()));
            assertTrue(error.getMessage().contains("HTTP 503"));
            assertTrue(error.getMessage().contains("CONNECT 403"));
            assertTrue(error.getMessage().contains("Proxy response (403)"));
            for (String secret : new String[]{"alice", "secret%40password", "dXNlcjpwYXNz", "secret-hash", "doNotDisplayThis", "Footer", "<code"}) assertFalse(error.getMessage().contains(secret),secret);
        } finally { server.stop(0); }
    }

    @Test void handlesJsonPlainAndEmptyErrorBodiesWithinDisplayLimit() {
        String json = "{\"error\":{\"message\":\"connect ECONNREFUSED\"}}";
        assertTrue(RssFeedClient.httpError(503,"application/json",json.getBytes(StandardCharsets.UTF_8)).contains("连接被拒绝"));
        String empty = RssFeedClient.httpError(503,"",new byte[0]);
        assertTrue(empty.contains("请查看 RSSHub 日志"));
        String longError = RssFeedClient.httpError(502,"text/plain","x".repeat(10000).getBytes(StandardCharsets.UTF_8));
        assertTrue(longError.length()<1000);
    }
}
