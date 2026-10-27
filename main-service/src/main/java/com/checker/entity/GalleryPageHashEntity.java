package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 保存画廊抽样页的感知哈希、中心区域哈希和画质信息，避免重复读取归档计算。
 */
@Data
@TableName("eh_gallery_page_hashes")
public class GalleryPageHashEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long gid;
    private Integer pageIndex;
    private String pageName;
    private String source;
    private String perceptualHash;
    private String centerHash;
    private Integer quality;
    private Integer width;
    private Integer height;
    private Integer algorithmVersion;
    private Date createdAt;
    private Date updatedAt;
}
