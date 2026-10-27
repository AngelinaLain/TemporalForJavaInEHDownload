package com.checker.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 把画廊标题、作者、合集、标签和简介组织成 Komga 能识别的 ComicInfo.xml 元数据。
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

    /** 这本画廊在 Komga 系列中的顺序。 */
    private Integer number;

    /** 剧情简介 */
    private String summary;

    /** 作者列表（artist / group 标签），映射到 Komga 作者 */
    private List<String> writers;

    /** 标签列表，映射到 Komga 标签 */
    private List<String> tags;

    /**
     * 按 ComicInfo 格式生成 XML，省略没有值的字段，并转义文本中的特殊字符。
     */
    public String toXml() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n");
        appendElement(sb, "Title", title);
        appendElement(sb, "Series", series);
        if (number != null) appendElement(sb, "Number", String.valueOf(number));
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

    /**
     * 只有字段有内容时才添加对应 XML 节点，避免写入无意义的空字段。
     */
    private static void appendElement(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  <").append(name).append(">")
                .append(escapeXml(value))
                .append("</").append(name).append(">\n");
    }

    /**
     * 转义文本中的 XML 特殊字符，防止标题或简介破坏元数据结构。
     */
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
