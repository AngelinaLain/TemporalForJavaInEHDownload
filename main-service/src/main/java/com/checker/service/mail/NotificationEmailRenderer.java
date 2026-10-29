package com.checker.service.mail;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/** 将通知数据渲染为中文邮件，使用表格和行内样式兼容常见邮箱，并统一转义所有动态内容。 */
@Component
public class NotificationEmailRenderer {
    private static final String FONT = "'Segoe UI','Microsoft YaHei','PingFang SC',Arial,sans-serif";
    private static final String WRAP = "word-break:break-word;overflow-wrap:anywhere;";

    /** 根据实际有值的内容生成统计、状态、画廊和操作入口，缺失内容不输出空区块。 */
    public String render(NotificationEmail email) {
        if (email == null) throw new IllegalArgumentException("邮件内容不能为空");
        Palette palette = palette(email.level());
        String subject = present(email.subject()) ? email.subject() : "系统通知";
        StringBuilder out = new StringBuilder(16000);
        out.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"color-scheme\" content=\"light\"><meta name=\"supported-color-schemes\" content=\"light\">")
                .append("<title>").append(escape(subject)).append("</title>")
                .append("<style>@media only screen and (max-width:560px){.mail-outer{padding:12px 8px!important}")
                .append(".mail-pad{padding:22px 18px!important}.mail-title{font-size:23px!important}")
                .append(".metric-value{font-size:25px!important}.metric-cell{padding:14px 12px!important}")
                .append(".brand-cell{font-size:17px!important}.gallery-pad{padding:16px!important}")
                .append(".action-link{display:block!important;text-align:center!important;margin-right:0!important}")
                .append("}</style></head><body style=\"margin:0;padding:0;background:#edf1f7;color:#24334a;font-family:")
                .append(FONT).append(";-webkit-text-size-adjust:100%;\">")
                .append("<div style=\"display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all;\">")
                .append(escape(present(email.summary()) ? email.summary() : subject)).append("</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;background:#edf1f7;\">")
                .append("<tr><td class=\"mail-outer\" align=\"center\" style=\"padding:32px 16px;\">")
                // Outlook 桌面版不可靠地支持 max-width，用条件表格固定桌面版内容宽度。
                .append("<!--[if mso]><table role=\"presentation\" width=\"720\" cellpadding=\"0\" cellspacing=\"0\"><tr><td><![endif]-->")
                .append("<table role=\"presentation\" width=\"720\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;max-width:720px;border-spacing:0;table-layout:fixed;background:#ffffff;border:1px solid #dae2ef;border-radius:16px;overflow:hidden;\">")
                .append("<tr><td class=\"mail-pad\" style=\"padding:25px 30px;background:#182846;border-radius:15px 15px 0 0;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;table-layout:fixed;\"><tr>")
                .append("<td class=\"brand-cell\" style=\"font-size:19px;line-height:1.4;font-weight:700;letter-spacing:.3px;color:#ffffff;\">GalleryImport")
                .append("<div style=\"margin-top:5px;font-size:11px;font-weight:400;letter-spacing:1.4px;color:#b5c3df;\">画廊任务通知</div></td>")
                .append("<td align=\"right\" style=\"width:110px;vertical-align:top;\">").append(badge(palette.label(), palette))
                .append("</td></tr></table></td></tr>")
                .append("<tr><td class=\"mail-pad\" style=\"padding:28px 30px 25px;\">");
        if (present(email.category())) {
            out.append("<p style=\"margin:0 0 9px;font-size:12px;line-height:1.6;font-weight:600;color:#667a9b;letter-spacing:.8px;\">")
                    .append(escape(email.category())).append("</p>");
        }
        out.append("<h1 class=\"mail-title\" style=\"margin:0;font-size:27px;line-height:1.45;font-weight:700;color:#182846;")
                .append(WRAP).append("\">").append(escape(subject)).append("</h1>");
        if (present(email.summary())) {
            out.append("<p style=\"margin:13px 0 0;font-size:14px;line-height:1.95;color:#60718a;")
                    .append(WRAP).append("\">").append(lines(email.summary())).append("</p>");
        }
        out.append("</td></tr>");
        appendMetrics(out, email.metrics());
        appendStatuses(out, email.statuses());
        appendNotices(out, email.notices());
        appendGalleries(out, email.galleries(), email.omittedGalleries());
        appendLinks(out, email.links());
        if (present(email.detail())) {
            beginSection(out, "补充说明");
            out.append("<div style=\"padding:16px 18px;background:#f5f7fb;border:1px solid #e7ecf4;border-radius:8px;font-size:13px;line-height:1.95;color:#52657f;")
                    .append(WRAP).append("\">").append(lines(email.detail())).append("</div>");
            endSection(out);
        }
        appendFooter(out, email);
        return out.append("</table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>").toString();
    }

    /** 两列排列关键统计，数值使用文本以兼容未知值和百分比。 */
    private void appendMetrics(StringBuilder out, List<NotificationEmail.Metric> metrics) {
        if (metrics.isEmpty()) return;
        out.append("<tr><td class=\"mail-pad\" style=\"padding:0 30px 25px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;table-layout:fixed;border:1px solid #e3e9f2;border-radius:10px;background:#f7f9fd;\">");
        for (int index = 0; index < metrics.size(); index++) {
            if (index % 2 == 0) out.append("<tr>");
            NotificationEmail.Metric metric = metrics.get(index);
            out.append("<td class=\"metric-cell\" width=\"50%\" style=\"width:50%;padding:17px 20px;vertical-align:top;")
                    .append(index >= 2 ? "border-top:1px solid #e3e9f2;" : "")
                    .append(index % 2 == 1 ? "border-left:1px solid #e3e9f2;" : "")
                    .append(WRAP).append("\"><div style=\"font-size:12px;line-height:1.7;color:#71829d;\">")
                    .append(escape(metric.label())).append("</div><div class=\"metric-value\" style=\"margin-top:5px;font-size:29px;line-height:1.35;font-weight:700;color:#263e66;\">")
                    .append(escape(metric.value())).append("</div></td>");
            if (index % 2 == 1) out.append("</tr>");
        }
        if (metrics.size() % 2 == 1) out.append("<td width=\"50%\" style=\"width:50%;\"></td></tr>");
        out.append("</table></td></tr>");
    }

    /** 展示真实状态数量和占比条，颜色只从预设级别选择，避免动态样式注入。 */
    private void appendStatuses(StringBuilder out, List<NotificationEmail.StatusCount> statuses) {
        if (statuses.isEmpty()) return;
        beginSection(out, "状态分布");
        long total = statuses.stream().mapToLong(status -> Math.max(0, status.count())).sum();
        out.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;table-layout:fixed;\">");
        for (NotificationEmail.StatusCount status : statuses) {
            int count = Math.max(0, status.count());
            long percentage = total == 0 ? 0 : Math.round(count * 100D / total);
            Palette colors = palette(status.level());
            out.append("<tr><td style=\"padding:8px 12px 8px 0;width:112px;font-size:13px;line-height:1.65;color:#52657f;")
                    .append(WRAP).append("\">").append(escape(status.label())).append("</td><td style=\"padding:8px 0;\">")
                    .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;background:#edf1f7;border-radius:4px;\"><tr><td style=\"height:7px;font-size:0;line-height:7px;\">");
            if (percentage > 0) {
                out.append("<table role=\"presentation\" width=\"").append(percentage)
                        .append("%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:").append(percentage).append("%;background:")
                        .append(colors.accent()).append(";border-radius:4px;\"><tr><td height=\"7\" style=\"height:7px;font-size:0;line-height:7px;\">&nbsp;</td></tr></table>");
            }
            out.append("</td></tr></table></td><td align=\"right\" style=\"padding:8px 0 8px 12px;width:52px;font-size:13px;font-weight:700;line-height:1.65;color:#263e66;\">")
                    .append(count).append("</td></tr>");
        }
        out.append("</table>");
        endSection(out);
    }

    private void appendNotices(StringBuilder out, List<String> notices) {
        if (notices.stream().noneMatch(NotificationEmailRenderer::present)) return;
        beginSection(out, "需要留意");
        out.append("<div style=\"padding:14px 18px;background:#fff8eb;border:1px solid #f1dfb8;border-left:3px solid #c18b35;border-radius:8px;\">");
        for (String notice : notices) {
            if (!present(notice)) continue;
            out.append("<p style=\"margin:4px 0;font-size:13px;line-height:1.9;color:#886329;")
                    .append(WRAP).append("\">• ").append(lines(notice)).append("</p>");
        }
        out.append("</div>");
        endSection(out);
    }

    /** 每本画廊单独成卡，保留失败原因和元数据；明细有省略时在列表末尾明确提示。 */
    private void appendGalleries(StringBuilder out, List<NotificationEmail.Gallery> galleries, int omitted) {
        if (galleries.isEmpty() && omitted == 0) return;
        beginSection(out, "画廊处理明细");
        for (NotificationEmail.Gallery gallery : galleries) {
            out.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;table-layout:fixed;margin-bottom:12px;border:1px solid #e3e9f2;border-radius:9px;\"><tr><td class=\"gallery-pad\" style=\"padding:18px 20px;")
                    .append(WRAP).append("\">")
                    .append("<div style=\"margin-bottom:10px;font-size:11px;line-height:1.8;color:#7889a2;\">");
            if (gallery.gid() != null) out.append("GID ").append(gallery.gid()).append(" &nbsp; ");
            if (present(gallery.status())) out.append(badge(gallery.status(), palette(gallery.level())));
            out.append("</div><div style=\"font-size:15px;line-height:1.75;font-weight:600;color:#263e66;\">")
                    .append(escape(present(gallery.title()) ? gallery.title() : "未提供标题")).append("</div>");
            if (present(gallery.originalTitle()) && !gallery.originalTitle().equals(gallery.title())) {
                out.append("<p style=\"margin:6px 0 0;font-size:12px;line-height:1.8;color:#7b8aa1;\">原始标题：")
                        .append(escape(gallery.originalTitle())).append("</p>");
            }
            if (present(gallery.facts())) {
                out.append("<p style=\"margin:10px 0 0;font-size:12px;line-height:1.9;color:#647790;\">")
                        .append(lines(gallery.facts())).append("</p>");
            }
            if (present(gallery.reason())) {
                out.append("<div style=\"margin-top:12px;padding:10px 12px;background:#f5f7fb;border-radius:6px;font-size:12px;line-height:1.9;color:#52657f;\"><strong>处理说明</strong><br>")
                        .append(lines(gallery.reason())).append("</div>");
            }
            String url = safeUrl(gallery.url());
            if (url != null) {
                out.append("<p style=\"margin:12px 0 0;font-size:12px;line-height:1.8;\"><a href=\"")
                        .append(escape(url)).append("\" style=\"color:#4265aa;text-decoration:none;font-weight:600;\">查看画廊 →</a></p>");
            }
            out.append("</td></tr></table>");
        }
        if (omitted > 0) {
            out.append("<p style=\"margin:4px 0 0;font-size:12px;line-height:1.8;color:#7b8aa1;\">另有 ")
                    .append(omitted).append(" 条明细未在邮件展开，请进入系统查看完整记录。</p>");
        }
        endSection(out);
    }

    /** 只为合法的浏览器地址生成按钮，缺少地址时不显示误导性的空操作。 */
    private void appendLinks(StringBuilder out, List<NotificationEmail.Link> links) {
        List<NotificationEmail.Link> valid = links.stream()
                .filter(link -> present(link.label()) && safeUrl(link.url()) != null).toList();
        if (valid.isEmpty()) return;
        out.append("<tr><td class=\"mail-pad\" style=\"padding:0 30px 25px;\">");
        for (NotificationEmail.Link link : valid) {
            out.append("<a class=\"action-link\" href=\"").append(escape(safeUrl(link.url())))
                    .append("\" style=\"display:inline-block;max-width:100%;box-sizing:border-box;margin:0 10px 10px 0;padding:12px 18px;background:#294b85;border:1px solid #294b85;border-radius:7px;font-size:13px;line-height:1.65;font-weight:600;color:#ffffff;text-decoration:none;")
                    .append(WRAP).append("\">").append(escape(link.label())).append(" →</a>");
        }
        out.append("</td></tr>");
    }

    /** 在页脚保留定位任务所需的信息，时间文本由上游明确带上时区。 */
    private void appendFooter(StringBuilder out, NotificationEmail email) {
        out.append("<tr><td class=\"mail-pad\" style=\"padding:22px 30px;background:#f7f9fd;border-top:1px solid #e7edf5;border-radius:0 0 15px 15px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;table-layout:fixed;\">");
        metadata(out, "通知时间", email.generatedAt());
        metadata(out, "执行耗时", email.duration());
        metadata(out, "工作流 ID", email.workflowId());
        metadata(out, "运行 ID", email.runId());
        out.append("</table><p style=\"margin:14px 0 0;font-size:11px;line-height:1.9;color:#8a98af;\">由 GalleryImport 自动发送 · 如需处理，请使用邮件中的系统入口。<br>本通知不包含登录凭据；请勿直接回复此邮件。</p></td></tr>");
    }

    private void metadata(StringBuilder out, String label, String value) {
        if (!present(value)) return;
        out.append("<tr><td style=\"width:80px;padding:3px 10px 3px 0;vertical-align:top;font-size:11px;line-height:1.85;color:#8998ae;\">")
                .append(label).append("</td><td style=\"padding:3px 0;font-size:11px;line-height:1.85;color:#677b98;word-break:break-all;overflow-wrap:anywhere;\">")
                .append(escape(value)).append("</td></tr>");
    }

    private void beginSection(StringBuilder out, String title) {
        out.append("<tr><td class=\"mail-pad\" style=\"padding:0 30px 25px;\"><h2 style=\"margin:0 0 13px;font-size:14px;line-height:1.75;font-weight:700;color:#334b71;\">")
                .append(title).append("</h2>");
    }

    private void endSection(StringBuilder out) {
        out.append("</td></tr>");
    }

    private String badge(String label, Palette palette) {
        return "<span style=\"display:inline-block;padding:4px 9px;border-radius:5px;font-size:11px;line-height:1.5;font-weight:600;color:"
                + palette.accent() + ";background:" + palette.background() + ";\">" + escape(label) + "</span>";
    }

    private static Palette palette(String level) {
        if ("success".equals(level)) return new Palette("已完成", "#207a65", "#e9f5ef");
        if ("warning".equals(level)) return new Palette("需要关注", "#a66c1b", "#fff3d9");
        if ("danger".equals(level)) return new Palette("处理异常", "#b44f4f", "#ffeded");
        return new Palette("系统通知", "#496ba6", "#edf3ff");
    }

    /** 地址必须是无用户名密码的绝对 HTTP(S) URL，禁止脚本协议、相对地址和控制字符。 */
    private static String safeUrl(String value) {
        if (!present(value)) return null;
        try {
            URI url = new URI(value);
            String scheme = url.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && url.getHost() != null && url.getRawUserInfo() == null && url.getPort() <= 65535
                    ? url.toASCIIString() : null;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    /** 动态文本统一转义，正文换行仅在完成转义之后转换为固定的 br 标签。 */
    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String lines(String value) {
        return escape(value).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record Palette(String label, String accent, String background) { }
}
