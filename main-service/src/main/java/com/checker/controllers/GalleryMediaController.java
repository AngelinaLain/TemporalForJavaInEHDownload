package com.checker.controllers;

import com.checker.common.Result;
import com.checker.service.GalleryMediaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 提供经过登录校验的画廊封面和分页图片接口，图片来自本地归档或 Komga 图库。
 */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "画廊预览", description = "按需分页预览与 Komga 跳转，不下载完整归档")
@PreAuthorize("hasRole('ADMIN')")
public class GalleryMediaController {
    private final GalleryMediaService service;

    public GalleryMediaController(GalleryMediaService service) { this.service = service; }

    /**
     * 返回当前页的图片索引；主动重新检测时清理定位缓存，不批量下载原图。
     */
    @GetMapping("/gallery-media/{gid}")
    public Result<Map<String, Object>> page(@PathVariable long gid, @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(defaultValue = "false") boolean refresh) {
        return Result.success(service.page(gid, page, size, refresh));
    }

    /**
     * 按本地图库策略读取一张封面，供列表停留预览使用。
     */
    @GetMapping("/gallery-media/{gid}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable long gid) { return image(service.cover(gid)); }

    /**
     * 读取指定页的缩略图或原图，仍由服务控制读取大小和并发。
     */
    @GetMapping("/gallery-media/{gid}/images/{index}")
    public ResponseEntity<byte[]> pageImage(@PathVariable long gid, @PathVariable int index,
                                           @RequestParam(defaultValue = "false") boolean thumbnail) {
        return image(service.image(gid, index, thumbnail));
    }

    /**
     * 生成浏览器可访问的 Komga 书籍链接，不在链接中携带服务端密钥。
     */
    @GetMapping("/komga-link/{gid}")
    public Result<Map<String, Object>> komgaLink(@PathVariable long gid) { return Result.success(service.komgaLink(gid)); }

    /**
     * 为图片二进制设置实际格式、私有缓存及认证相关响应头。
     */
    private ResponseEntity<byte[]> image(GalleryMediaService.ImageData image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
