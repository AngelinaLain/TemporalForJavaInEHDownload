package com.checker.dto;

import java.util.List;

/** tags 为 null 表示源站未提供标签；空列表表示源站明确返回零个标签。 */
public record GallerySourceResult(List<String> tags, GalleryPageFingerprint cover) {}
