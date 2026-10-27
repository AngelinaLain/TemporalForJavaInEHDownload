package com.checker.service;

import com.checker.config.EhNetworkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** 只读访问 Komga 的书籍信息和图片，预览请求与同步写入流程分别控制超时。 */
@Component
public class KomgaMediaClient {
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_PAGES = 10;
    private static final int MAX_LIBRARY_BOOKS = 50_000;
    private static final long MAX_LIBRARY_BYTES = 16L * 1024 * 1024;
    private final EhNetworkConfig config;
    private final ObjectMapper mapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build();

    public KomgaMediaClient(EhNetworkConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** 检查是否具备访问 Komga 所需的地址和 API Key。 */
    public boolean configured() {
        return config.getKomga() != null && config.getKomga().getUrl() != null
                && !config.getKomga().getUrl().isBlank() && config.getKomga().getApiKey() != null
                && !config.getKomga().getApiKey().isBlank();
    }

    /** 读取指定书籍当前可用页数，判断关联是否仍能用于预览。 */
    public int pageCount(String bookId) throws IOException {
        JsonNode book = mapper.readTree(read(bookId, "", 1024 * 1024));
        return book == null ? 0 : Math.max(0, book.path("media").path("pagesCount").asInt(0));
    }

    /** 完整读取有容量上限的物理书籍目录，供数据库关联修复使用；不读取图片内容。 */
    public LibrarySnapshot librarySnapshot(BooleanSupplier cancelled) throws IOException {
        if (!configured()) throw new IOException("Komga 图片服务未配置");
        String library = config.getKomga().getLibraryId();
        Map<String, Object> body = library == null || library.isBlank() ? Map.of()
                : Map.of("condition", Map.of("libraryId", Map.of("operator", "is", "value", library)));
        Map<String, LibraryBook> books = new LinkedHashMap<>();
        var seenIds = new LinkedHashSet<String>();
        Long expectedTotal = null;
        int received = 0;
        long bytes = 256;
        for (int page = 0; ; page++) {
            if (cancelled.getAsBoolean()) throw new CancellationException("已取消 Komga ID 刷新");
            if (page >= MAX_LIBRARY_BOOKS / SEARCH_PAGE_SIZE)
                throw new IOException("Komga 图库超过 50000 本的刷新容量，请缩小配置的 Library 范围");
            HttpUrl url = base().newBuilder().addPathSegments("api/v1/books/list")
                    .addQueryParameter("size", String.valueOf(SEARCH_PAGE_SIZE)).addQueryParameter("page", String.valueOf(page)).build();
            JsonNode result = mapper.readTree(execute(authorized(url).post(RequestBody.create(mapper.writeValueAsBytes(body),
                    okhttp3.MediaType.get("application/json"))).build(), 4 * 1024 * 1024));
            if (cancelled.getAsBoolean()) throw new CancellationException("已取消 Komga ID 刷新");
            JsonNode content = result == null ? null : result.get("content");
            if (content == null || !content.isArray() || content.size() > SEARCH_PAGE_SIZE)
                throw new IOException("Komga 图库分页无效，未更新数据库");
            if (result.has("number") && result.path("number").asInt(-1) != page)
                throw new IOException("Komga 图库分页位置不一致，请等待扫描完成后重试");
            if (result.path("totalElements").canConvertToLong()) {
                long total = result.path("totalElements").asLong();
                if (total < 0 || total > MAX_LIBRARY_BOOKS) throw new IOException("Komga 图库超过 50000 本的刷新容量");
                if (expectedTotal != null && total != expectedTotal)
                    throw new IOException("Komga 图库在读取期间发生变化，请等待扫描完成后重试");
                expectedTotal = total;
            }
            received += content.size();
            for (JsonNode item : content) {
                String id = item.path("id").asText("");
                if (id.isBlank() || !seenIds.add(id)) throw new IOException("Komga 图库存在缺失或重复的书籍 ID，无法完整刷新");
                String actualLibrary = item.path("libraryId").asText("");
                if (library != null && !library.isBlank() && !actualLibrary.isBlank() && !library.equals(actualLibrary))
                    throw new IOException("Komga 返回的书籍不属于配置的 Library，未更新数据库");
                if (item.path("deleted").asBoolean(false)) continue;
                var filenames = new LinkedHashSet<String>();
                for (String field : List.of("name", "fileName", "url")) {
                    String name = basename(item.path(field).asText(""));
                    if (!name.isBlank()) filenames.add(name);
                }
                bytes += 192L + id.length() * 2L + filenames.stream().mapToLong(name -> 48L + name.length() * 2L).sum();
                if (bytes > MAX_LIBRARY_BYTES) throw new IOException("Komga 物理文件索引超过 16 MiB 容量，请缩小 Library 范围");
                books.put(id, new LibraryBook(id, List.copyOf(filenames)));
            }
            boolean last;
            if (result.path("last").isBoolean()) last = result.path("last").asBoolean();
            else if (result.path("totalPages").canConvertToInt()) last = page + 1 >= result.path("totalPages").asInt();
            else if (expectedTotal != null) last = received >= expectedTotal;
            else throw new IOException("Komga 图库缺少分页完成信息，无法安全刷新");
            if (last) {
                if ((expectedTotal != null && expectedTotal != received)
                        || (result.path("totalPages").canConvertToInt() && result.path("totalPages").asInt() > page + 1))
                    throw new IOException("Komga 图库未完整读取，未更新数据库");
                return new LibrarySnapshot(List.copyOf(books.values()), books.size());
            }
            if (content.isEmpty()) throw new IOException("Komga 图库分页提前结束，未更新数据库");
        }
    }

    /** Komga 重扫可能更换书籍 ID；只在物理归档唯一匹配时重新定位，不把相似标题当作同一本书。 */
    public Optional<PreviewBook> findBook(long gid, String filename) throws IOException {
        if (!configured()) throw new IOException("Komga 图片服务未配置");
        if (gid <= 0) throw new IOException("画廊 GID 无效");
        String expected = basename(filename);
        Map<String, JsonNode> matches = new LinkedHashMap<>();
        int[] remainingPages = {MAX_SEARCH_PAGES};
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(String.valueOf(gid));
        String titleTerms = titleTerms(expected);
        if (!titleTerms.isBlank()) terms.add(titleTerms);
        if (!expected.isBlank()) terms.add(stem(expected).substring(0, Math.min(120, stem(expected).length())));
        // Komga 全文索引可能只有作品标题，没有文件名中的 GID；标题仅用于召回候选，后面仍核对物理文件。
        for (String term : terms) {
            search(term, gid, expected, matches, remainingPages);
            if (!matches.isEmpty()) break;
        }
        if (matches.size() > 1) throw new IOException("Komga 中存在多个同 GID 或同文件名的归档，请先核对书籍关联");
        if (matches.isEmpty()) return Optional.empty();
        Map.Entry<String, JsonNode> match = matches.entrySet().iterator().next();
        int count = match.getValue().path("media").path("pagesCount").asInt(0);
        if (count < 1) count = pageCount(match.getKey());
        return Optional.of(new PreviewBook(match.getKey(), Math.max(0, count)));
    }

    /** 分页召回候选并校验分页完整性，超出预算或无法确认唯一性时明确失败。 */
    private void search(String term, long gid, String expected, Map<String, JsonNode> matches,
                        int[] remainingPages) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullTextSearch", term);
        String library = config.getKomga().getLibraryId();
        if (library != null && !library.isBlank()) {
            body.put("condition", Map.of("libraryId", Map.of("operator", "is", "value", library)));
        }
        int received = 0;
        for (int page = 0; ; page++) {
            if (remainingPages[0]-- <= 0) throw new IOException("Komga 查询超过预览检索范围，请先修复书籍关联后重试");
            HttpUrl url = base().newBuilder().addPathSegments("api/v1/books/list")
                    .addQueryParameter("size", String.valueOf(SEARCH_PAGE_SIZE))
                    .addQueryParameter("page", String.valueOf(page)).build();
            Request request = authorized(url).post(RequestBody.create(mapper.writeValueAsBytes(body),
                    okhttp3.MediaType.get("application/json"))).build();
            JsonNode result = mapper.readTree(execute(request, 4 * 1024 * 1024));
            JsonNode content = result == null ? null : result.get("content");
            if (content == null || !content.isArray() || content.size() > SEARCH_PAGE_SIZE) {
                throw new IOException("Komga 返回的书籍分页无效");
            }
            if (result.has("number") && result.path("number").asInt(-1) != page) {
                throw new IOException("Komga 返回的书籍分页位置不一致");
            }
            received += content.size();
            for (JsonNode book : content) {
                String id = book.path("id").asText("");
                String actualLibrary = book.path("libraryId").asText("");
                if (id.isBlank() || (library != null && !library.isBlank()
                        && !actualLibrary.isBlank() && !library.equals(actualLibrary))) continue;
                if (matchesPhysicalFile(book, gid, expected)) matches.put(id, book);
            }
            boolean last;
            if (result.path("last").isBoolean()) {
                last = result.path("last").asBoolean();
            } else if (result.path("totalPages").canConvertToInt()) {
                last = page + 1 >= result.path("totalPages").asInt();
            } else if (result.path("totalElements").canConvertToLong()) {
                last = received >= result.path("totalElements").asLong();
            } else {
                throw new IOException("Komga 书籍响应缺少分页完成信息，无法确认唯一归档");
            }
            if (last && ((result.path("totalElements").canConvertToLong()
                    && result.path("totalElements").asLong() > received)
                    || (result.path("totalPages").canConvertToInt()
                    && result.path("totalPages").asInt() > page + 1))) {
                throw new IOException("Komga 返回的书籍分页总数不一致");
            }
            if (last) return;
            if (content.isEmpty()) throw new IOException("Komga 书籍分页提前结束，无法确认唯一归档");
        }
    }

    /** 只按物理文件 GID 或完整文件名核对身份，不采用元数据标题的模糊命中。 */
    private static boolean matchesPhysicalFile(JsonNode book, long gid, String expected) {
        String prefix = "[" + gid + "]";
        for (String field : List.of("name", "fileName", "url")) {
            String name = basename(book.path(field).asText(""));
            if (name.startsWith(prefix) && (name.length() == prefix.length()
                    || Character.isWhitespace(name.charAt(prefix.length())) || name.charAt(prefix.length()) == '.')) return true;
            if (!expected.isBlank() && (name.equalsIgnoreCase(expected) || stem(name).equalsIgnoreCase(stem(expected)))) return true;
        }
        return false;
    }

    private static String basename(String value) {
        if (value == null || value.isBlank()) return "";
        String decoded;
        // 只对地址做 URL 解码，并保留文件名中的加号，避免把普通名称里的“+”误变为空格。
        try { decoded = value.contains("%") || value.contains("://") ? URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8) : value; }
        catch (IllegalArgumentException malformed) { decoded = value; }
        decoded = decoded.replace('\\', '/');
        return decoded.substring(decoded.lastIndexOf('/') + 1).trim();
    }

    private static String stem(String value) { return value.replaceFirst("(?i)\\.(zip|cbz)$", ""); }

    private static String titleTerms(String expected) {
        String title = stem(expected).replaceAll("\\[[^\\]]*]", " ");
        var words = Pattern.compile("[\\p{L}\\p{N}]+").matcher(title);
        StringBuilder result = new StringBuilder();
        for (int count = 0; count < 2 && words.find(); count++) {
            if (!result.isEmpty()) result.append(' ');
            result.append(words.group());
        }
        return result.substring(0, Math.min(64, result.length()));
    }

    /** 读取指定书籍的一页原图或缩略图，不读取整本归档。 */
    public byte[] image(String bookId, int index, boolean thumbnail) throws IOException {
        // Komga 缩略图接口会把最长边缩到 300 像素，适合列表和分页预览。
        // https://komga.org/docs/openapi/get-book-page-thumbnail-by-number/
        return read(bookId, "/pages/" + index + (thumbnail ? "/thumbnail" : ""), MAX_BYTES);
    }

    /** 读取 Komga 生成的封面缩略图。 */
    public byte[] cover(String bookId) throws IOException {
        return read(bookId, "/thumbnail", 4 * 1024 * 1024);
    }

    private byte[] read(String bookId, String suffix, int limit) throws IOException {
        if (!configured() || bookId == null || bookId.isBlank()) throw new IOException("Komga 图片服务未配置");
        HttpUrl.Builder url = base().newBuilder().addPathSegments("api/v1/books").addPathSegment(bookId);
        if (!suffix.isBlank()) url.addPathSegments(suffix.replaceFirst("^/", ""));
        return execute(authorized(url.build()).get().build(), limit);
    }

    private HttpUrl base() throws IOException {
        if (!configured()) throw new IOException("Komga 图片服务未配置");
        HttpUrl parsed = HttpUrl.parse(config.getKomga().getUrl().replaceAll("/+$", "") + "/");
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw new IOException("Komga 地址无效");
        return parsed.newBuilder().query(null).fragment(null).build();
    }

    private Request.Builder authorized(HttpUrl url) {
        return new Request.Builder().url(url).header("X-API-Key", config.getKomga().getApiKey());
    }

    /** 执行受超时和响应大小限制的 HTTP 请求，把图库权限错误与本系统登录错误分开。 */
    private byte[] execute(Request request, int limit) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new KomgaHttpException(response.code());
            if (response.body() == null) throw new IOException("Komga 未返回可读取的数据");
            if (response.body().contentLength() > limit) throw new IOException("图片响应超过大小上限");
            byte[] bytes = response.body().byteStream().readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("图片响应超过大小上限");
            return bytes;
        }
    }

    /** 唯一定位到的预览书籍，以及当前可读页数。 */
    public record PreviewBook(String bookId, int pageCount) {}
    /** 只保留书籍 ID 和物理文件名，不把图库绝对路径带到匹配结果中。 */
    public record LibraryBook(String bookId, List<String> filenames) {}
    /** 全部分页已校验完成的图库目录，未完整读取时不会创建此结果。 */
    public record LibrarySnapshot(List<LibraryBook> books, int total) {}

    /** 保留 Komga 的响应状态，让调用方区分旧书籍 ID、图库权限和普通连接错误。 */
    public static final class KomgaHttpException extends IOException {
        private final int statusCode;
        public KomgaHttpException(int statusCode) {
            super(statusCode == 401 || statusCode == 403
                    ? "Komga 访问凭据无效或没有图库权限，请检查 Komga API Key"
                    : statusCode == 404 ? "Komga 书籍不存在，关联可能已因重新扫描而变化"
                    : "Komga 图片请求失败（HTTP " + statusCode + "）");
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
        public boolean isNotFound() { return statusCode == 404; }
    }
}
