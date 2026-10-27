package com.checker.service;

import com.checker.config.EhNetworkConfig;
import com.checker.common.DownloadStatus;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import okhttp3.HttpUrl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 从本地归档或 Komga 提供分页预览、封面和大图；预览过程不访问画廊源站。 */
@Service
public class GalleryMediaService {
    private static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 50, 100);
    private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;
    private final EhGalleriesMapper galleries;
    private final KomgaMediaClient komga;
    private final MountedArchiveStorage mounted;
    private final EhNetworkConfig config;
    private final Semaphore budget = new Semaphore(6);
    private final PreviewCache<Long, EhGalleriesEntity> metadata = new PreviewCache<>(256, 256, 10_000, ignored -> 1);
    private final PreviewCache<String, SourceIndex> sources = new PreviewCache<>(128, 8L * 1024 * 1024, 60_000,
            index -> 128L + index.location().length() * 2L + index.names().stream().mapToLong(name -> 64L + name.length() * 2L).sum());
    private final PreviewCache<String, ImageData> thumbnails = new PreviewCache<>(256, 32L * 1024 * 1024, 300_000, image -> image.bytes().length);

    public GalleryMediaService(EhGalleriesMapper galleries, KomgaMediaClient komga,
                               MountedArchiveStorage mounted, EhNetworkConfig config) {
        this.galleries = galleries;
        this.komga = komga;
        this.mounted = mounted;
        this.config = config;
    }

    /** 返回当前页图片索引；只列出图片，不提前读取这一页的所有原图。 */
    public Map<String, Object> page(long gid, int page, int size) {
        return page(gid, page, size, false);
    }

    /** 清除指定画廊的预览缓存，使更新后的 Komga ID 立即用于图片和跳转链接。 */
    public void invalidate(long gid) {
        EhGalleriesEntity previous;
        try { previous = metadata.getOrLoad(gid, () -> galleries.getPreviewMetadata(gid)); }
        catch (Exception unavailable) { previous = null; }
        metadata.invalidate(gid);
        if (previous != null) {
            String key = revision(previous);
            sources.invalidate(key);
            thumbnails.invalidate(key + ":cover");
        }
    }

    /** 返回当前页图片索引；只列出图片，不提前读取这一页的所有原图。 */
    public Map<String, Object> page(long gid, int page, int size, boolean refresh) {
        if (page < 1 || !PAGE_SIZES.contains(size)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页大小只支持 10、20、50、100");
        return limited(() -> {
            if (refresh) metadata.invalidate(gid);
            EhGalleriesEntity gallery = requireGallery(gid);
            if (refresh) { sources.invalidate(revision(gallery)); mounted.invalidatePreviewIndex(); }
            SourceIndex source = source(gallery);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("gid", gid);
            int safePage = source.total() > 0 ? Math.min(page, (source.total() + size - 1) / size) : 1;
            result.put("page", safePage);
            result.put("size", size);
            result.put("total", source.total());
            result.put("source", source.source());
            result.put("available", source.total() > 0);
            result.put("message", source.message());
            result.put("diagnostics", source.diagnostics());
            if (source.total() < 1) {
                result.put("images", List.of());
                return result;
            }
            int start = (safePage - 1) * size + 1;
            int end = Math.min(start + size - 1, source.total());
            List<Map<String, Object>> images = new ArrayList<>();
            for (int index = start; index <= end; index++) {
                String url = "/api/dashboard/gallery-media/" + gid + "/images/" + index;
                String name = "ARCHIVE".equals(source.source()) ? source.names().get(index - 1) : "第 " + index + " 页";
                images.add(Map.of("index", index, "name", name, "thumbnailUrl", url + "?thumbnail=true", "imageUrl", url));
            }
            result.put("images", images);
            return result;
        });
    }

    /** 校验图片页码，并按需要复用缩略图缓存或读取当前原图。 */
    public ImageData image(long gid, int index, boolean thumbnail) {
        if (index < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码必须大于 0");
        return limited(() -> {
            EhGalleriesEntity gallery = requireGallery(gid);
            SourceIndex source = source(gallery);
            if (index > source.total() || source.total() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, source.total() == 0 ? source.message() : "图片页码不存在");
            String key = revision(gallery) + ":" + source.source() + ":" + index;
            return thumbnail ? thumbnails.getOrLoad(key, () -> loadImage(source, index, true))
                    : loadImage(source, index, false);
        });
    }

    /** 按与预览相同的本地来源读取封面，优先复用已有缩略图缓存。 */
    public ImageData cover(long gid) {
        return limited(() -> {
            EhGalleriesEntity gallery = requireGallery(gid);
            return thumbnails.getOrLoad(revision(gallery) + ":cover", () -> {
                SourceIndex source = source(gallery);
                if (source.total() < 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, source.message());
                if ("KOMGA".equals(source.source())) {
                    try { return asImage(komga.cover(source.location()), true); }
                    catch (IOException unavailable) { /* 封面接口不可用时，改读同一本地书籍的第一页。 */ }
                }
                return loadImage(source, 1, true);
            });
        });
    }

    /** 生成浏览器可访问的 Komga 链接，保留部署路径并去除凭据等敏感地址部分。 */
    public Map<String, Object> komgaLink(long gid) {
        EhGalleriesEntity gallery = requireGallery(gid);
        if (!hasBook(gallery)) return Map.of("available", false, "url", "", "message", "该画廊尚未关联 Komga 书籍");
        String publicUrl = config.getKomga().getPublicUrl();
        String base = publicUrl == null || publicUrl.isBlank() ? config.getKomga().getUrl() : publicUrl;
        try {
            HttpUrl parsed = HttpUrl.parse(base == null ? "" : base.replaceAll("/+$", "") + "/");
            if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw new IllegalArgumentException();
            return Map.of("available", true, "url", parsed.newBuilder().query(null).fragment(null).addPathSegment("book")
                    .addPathSegment(gallery.getKomgaBookId()).build().toString(), "message", "");
        } catch (IllegalArgumentException invalid) {
            return Map.of("available", false, "url", "", "message", "请配置浏览器可访问的 KOMGA_PUBLIC_URL");
        }
    }

    /** 先找本地归档，再检查 Komga；旧书籍 ID 失效时重新定位，并保留每一步的检测说明。 */
    private SourceIndex source(EhGalleriesEntity gallery) throws Exception {
        return sources.getOrLoad(revision(gallery), () -> {
            List<Map<String, String>> diagnostics = new ArrayList<>();
            // 先直接读取归档，减少一次 Komga 请求，也让预览不依赖源站画廊是否还存在。
            try {
                var archive = mounted.findReadableArchive(gallery.getStoragePath(), gallery.getFilename(), gallery.getGid());
                if (archive.isPresent()) {
                    Path path = archive.get();
                    List<String> names = archiveNames(path);
                    if (!names.isEmpty()) return new SourceIndex("ARCHIVE", names.size(), path.toString(), names, "", List.of());
                    diagnostics.add(issue("ARCHIVE_EMPTY", "本地归档中没有可预览的图片"));
                } else {
                    diagnostics.add(issue("ARCHIVE_NOT_FOUND", "未在挂载图库中找到该 GID 的归档"));
                }
            } catch (MountedArchiveStorage.PreviewLookupException failure) {
                diagnostics.add(issue("ARCHIVE_" + failure.code(), failure.getMessage()));
            } catch (IllegalArgumentException invalid) {
                diagnostics.add(issue("ARCHIVE_INVALID_PATH", "记录中的归档目录或文件名无效，请检查归档同步记录"));
            } catch (Exception unavailable) {
                diagnostics.add(issue("ARCHIVE_UNREADABLE", "本地归档无法读取或压缩包已损坏"));
            }
            if (!komga.configured()) {
                diagnostics.add(issue("KOMGA_NOT_CONFIGURED", "未配置可用的 Komga 地址与 API Key"));
            } else {
                boolean discover = !hasBook(gallery) && isImported(gallery);
                if (hasBook(gallery)) {
                    try {
                        int count = komga.pageCount(gallery.getKomgaBookId());
                        if (count > 0) return komgaSource(gallery.getKomgaBookId(), count, "");
                        diagnostics.add(issue("KOMGA_NO_PAGES", "关联书籍尚无可读图片，可能仍在分析中"));
                        discover = true;
                    } catch (KomgaMediaClient.KomgaHttpException failure) {
                        discover = failure.isNotFound();
                        diagnostics.add(issue(failure.isNotFound() ? "KOMGA_STALE_BOOK" : "KOMGA_ACCESS_FAILED",
                                failure.isNotFound() ? "保存的 Komga 书籍已不存在，正在按物理文件重新定位" : failure.getMessage()));
                    } catch (IOException unavailable) {
                        diagnostics.add(issue("KOMGA_UNAVAILABLE", "Komga 暂时不可访问，请检查服务连接与书籍分析状态"));
                    }
                } else if (!discover) {
                    diagnostics.add(issue("KOMGA_UNLINKED", "该记录尚未关联 Komga 书籍；已入库记录可重新检测图库"));
                }
                if (discover) {
                    try {
                        var book = komga.findBook(gallery.getGid(), gallery.getFilename());
                        if (book.isPresent() && book.get().pageCount() > 0)
                            return komgaSource(book.get().bookId(), book.get().pageCount(), "已重新找到 Komga 中的本地图片");
                        diagnostics.add(book.isPresent()
                                ? issue("KOMGA_NO_PAGES", "重新找到的书籍尚无可读图片，请等待 Komga 完成分析后重新检测")
                                : issue("KOMGA_NOT_FOUND", "按物理 GID 与文件名未找到可读的 Komga 书籍"));
                    } catch (IOException unavailable) {
                        diagnostics.add(issue("KOMGA_LOOKUP_FAILED", unavailable.getMessage()));
                    }
                }
            }
            return new SourceIndex("NONE", 0, "", List.of(), "未能读取本地图片；请检查存储与 Komga 检测结果，再重新检测。", List.copyOf(diagnostics));
        });
    }

    private static Map<String, String> issue(String code, String message) {
        return Map.of("code", code, "message", message == null || message.isBlank() ? "图片服务暂不可用，请稍后重试" : message);
    }
    private static boolean isImported(EhGalleriesEntity gallery) {
        return DownloadStatus.IMPORTED.getValue().equals(gallery.getDownloadStatus()) || "IMPORTED".equalsIgnoreCase(gallery.getDownloadStatus());
    }
    private static SourceIndex komgaSource(String bookId, int count, String message) {
        return new SourceIndex("KOMGA", Math.min(count, 100_000), bookId, List.of(), message, List.of());
    }

    /** 按已经选定的本地来源读取一张图片，归档只打开所需条目，Komga 仅请求对应页。 */
    private ImageData loadImage(SourceIndex source, int index, boolean thumbnail) throws Exception {
        byte[] bytes;
        switch (source.source()) {
            case "KOMGA" -> {
                try { bytes = komga.image(source.location(), index, thumbnail); }
                catch (IOException failure) {
                    // 兼容没有逐页缩略图接口的 Komga 版本：读取该页原图后由本服务缩小。
                    if (!thumbnail) throw failure;
                    bytes = komga.image(source.location(), index, false);
                }
            }
            case "ARCHIVE" -> {
                try (ZipFile zip = new ZipFile(Path.of(source.location()).toFile())) {
                    ZipEntry entry = zip.getEntry(source.names().get(index - 1));
                    if (entry == null || entry.getSize() > MAX_IMAGE_BYTES) throw new IOException("图片不存在或超过 25MB");
                    try (InputStream input = zip.getInputStream(entry)) { bytes = bounded(input); }
                }
            }
            default -> throw new IOException("暂无可用本地图片");
        }
        return asImage(bytes, thumbnail);
    }

    private ImageData asImage(byte[] bytes, boolean thumbnail) throws IOException {
        return new ImageData(thumbnail ? PreviewImageCodec.thumbnail(bytes) : bytes);
    }

    static List<String> archiveNames(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.stream().filter(entry -> !entry.isDirectory() && isImageName(entry.getName()))
                    .limit(50_000).map(ZipEntry::getName).sorted(GalleryMediaService::naturalCompare).toList();
        }
    }

    static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith("__macosx/") && lower.matches(".*\\.(jpe?g|png|gif|webp|avif|bmp|tiff?|jfif)$");
    }

    static int naturalCompare(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            char first = left.charAt(a), second = right.charAt(b);
            if (Character.isDigit(first) && Character.isDigit(second)) {
                int aEnd = a, bEnd = b;
                while (aEnd < left.length() && Character.isDigit(left.charAt(aEnd))) aEnd++;
                while (bEnd < right.length() && Character.isDigit(right.charAt(bEnd))) bEnd++;
                String x = left.substring(a, aEnd).replaceFirst("^0+(?!$)", ""), y = right.substring(b, bEnd).replaceFirst("^0+(?!$)", "");
                int comparison = Integer.compare(x.length(), y.length());
                if (comparison == 0) comparison = x.compareTo(y);
                if (comparison != 0) return comparison;
                a = aEnd; b = bEnd;
            } else {
                int comparison = Character.compare(Character.toLowerCase(first), Character.toLowerCase(second));
                if (comparison != 0) return comparison;
                a++; b++;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    private static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
        if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("图片超过 25MB");
        return bytes;
    }

    /** 只读取预览需要的少量元数据，短暂缓存后用于同一画廊的多个图片请求。 */
    private EhGalleriesEntity requireGallery(long gid) {
        if (gid <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GID 必须是正整数");
        try {
            EhGalleriesEntity gallery = metadata.getOrLoad(gid, () -> galleries.getPreviewMetadata(gid));
            if (gallery == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "画廊不存在");
            return gallery;
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法读取画廊信息"); }
    }
    private static boolean hasBook(EhGalleriesEntity gallery) { return gallery.getKomgaBookId() != null && !gallery.getKomgaBookId().isBlank(); }
    private static String revision(EhGalleriesEntity gallery) { return gallery.getGid() + ":" + gallery.getKomgaBookId() + ":" + gallery.getStoragePath() + ":" + gallery.getFilename() + ":" + gallery.getUpdatedAt(); }

    /** 限制同时进行的预览操作，异常时保留业务状态码，并始终归还并发名额。 */
    private <T> T limited(Callable<T> task) {
        if (!budget.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "预览请求较多，请稍后重试");
        try { return task.call(); }
        catch (ResponseStatusException failure) { throw failure; }
        catch (Exception unavailable) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "本地图片暂不可用，请检查归档文件或 Komga 后重试"); }
        finally { budget.release(); }
    }

    record SourceIndex(String source, int total, String location, List<String> names, String message, List<Map<String, String>> diagnostics) {}
    /** 把图片字节和已识别的格式一起交给控制器，保证响应头与实际内容一致。 */
    public record ImageData(byte[] bytes, String mimeType) {
        public ImageData(byte[] bytes) throws IOException { this(bytes, PreviewImageCodec.mime(bytes)); }
    }
}
