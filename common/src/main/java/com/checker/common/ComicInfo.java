package com.checker.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ComicRack ComicInfo 元数据模型。
 * Komga 在扫描漫画压缩包时读取内嵌的 ComicInfo.xml 并自动写入书/系列元数据，
 * 从而免去下载后逐本 PATCH 元数据 API 的脆弱链路。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComicInfo {

    /** 书名 */
    private String title;

    /** 系列（合集）名称，映射到 Komga Series */
    private String series;

    /** 剧情简介 */
    private String summary;

    /** 作者列表（artist / group 标签），映射到 Komga 作者 */
    private List<String> writers;

    /** 标签列表，映射到 Komga 标签 */
    private List<String> tags;

    /**
     * 生成 ComicInfo.xml 内容（含 XML 转义）。
     */
    public String toXml() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n");
        appendElement(sb, "Title", title);
        appendElement(sb, "Series", series);
        appendElement(sb, "Summary", summary);
        if (writers != null && !writers.isEmpty()) {
            appendElement(sb, "Writer", String.join(", ", writers));
        }
        if (tags != null && !tags.isEmpty()) {
            // Genre 是 ComicInfo 标准的标签字段，Komga 会映射为 book tags
            appendElement(sb, "Genre", String.join(", ", tags));
        }
        sb.append("</ComicInfo>\n");
        return sb.toString();
    }

    private static void appendElement(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  <").append(name).append(">")
                .append(escapeXml(value))
                .append("</").append(name).append(">\n");
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
