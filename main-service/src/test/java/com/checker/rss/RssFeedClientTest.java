package com.checker.rss;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import com.checker.dto.SearchOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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

    @Test void redactsJsonAndJavascriptHeaderObjectsWithoutHidingTheCause() throws Exception {
        String details = "FetchError: headers={\"authorization\":\"Bearer bearer-secret\","
                + "\"cookie\":\"session=cookie-secret; extra=other-secret\"} "
                + "config={'password':'pass-secret', 'proxy-authorization': 'Basic basic-secret'} "
                + "cause=ECONNREFUSED proxy.example:7893";
        String html = "<html><pre>" + details.replace("\"", "&quot;") + "</pre></html>";
        String json = new ObjectMapper().writeValueAsString(Map.of("error", Map.of("message", details)));
        for (String output : new String[]{
                RssFeedClient.httpError(503, "text/html", html.getBytes(StandardCharsets.UTF_8)),
                RssFeedClient.httpError(503, "application/json", json.getBytes(StandardCharsets.UTF_8))}) {
            assertTrue(output.contains("HTTP 503"));
            assertTrue(output.contains("ECONNREFUSED proxy.example:7893"));
            for (String secret : new String[]{"bearer-secret", "cookie-secret", "other-secret", "pass-secret", "basic-secret"})
                assertFalse(output.contains(secret), secret);
        }
    }

    @Test void redactsEscapedQuotedValuesAndEmbeddedSerializedObjects() throws Exception {
        var json = new ObjectMapper();
        String credentialObject = json.writeValueAsString(Map.of(
                "password", "password-prefix\" , password-tail\\",
                "token", "token-prefix\\\" ; token-tail",
                "cookie", new String[]{"first-cookie", "second-cookie"},
                "authorization", "Bearer bearer-secret"));
        // An error can contain either an inspected object or JSON serialized inside another string.
        String escapedObject = json.writeValueAsString(credentialObject);
        String jsObject = "{'password':'single-prefix\\' , single-tail\\\\', 'token':'single-token'}";
        for (String object : new String[]{credentialObject, escapedObject, jsObject}) {
            String detail = "FetchError: " + object + " cause=Proxy response (407)";
            String output = RssFeedClient.httpError(503, "text/plain", detail.getBytes(StandardCharsets.UTF_8));
            assertTrue(output.contains("HTTP 503"));
            assertTrue(output.contains("Proxy response (407)"), output);
            for (String secret : new String[]{"password-prefix", "password-tail", "token-prefix", "token-tail",
                    "first-cookie", "second-cookie", "bearer-secret", "single-prefix", "single-tail", "single-token"})
                assertFalse(output.contains(secret), secret + ": " + output);
        }
    }

    @Test void redactsTheEntireTruncatedQuotedSecret() {
        String detail = "ECONNREFUSED config={\"password\":\"prefix\\\" , secret-tail";
        String output = RssFeedClient.httpError(503, "text/plain", detail.getBytes(StandardCharsets.UTF_8));
        assertTrue(output.contains("ECONNREFUSED"));
        assertFalse(output.contains("prefix"));
        assertFalse(output.contains("secret-tail"));
    }
}
