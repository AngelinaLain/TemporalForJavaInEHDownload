package com.checker.dto;

/** 单个画廊的源站读取请求，不接收任意页面或接口地址。 */
public record GallerySourceRequest(long gid, String token, String galleryUrl, Kind kind) {
    public enum Kind { TAGS, COVER }
}
