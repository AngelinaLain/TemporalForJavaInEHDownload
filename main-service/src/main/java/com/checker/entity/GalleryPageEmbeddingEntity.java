package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("eh_gallery_page_embeddings")
public class GalleryPageEmbeddingEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long gid;
    private Integer pageIndex;
    private String cropType;
    private String modelName;
    private String modelVersion;
    private Integer dimensions;
    private byte[] embedding;
    private String sourceFingerprint;
    private Date createdAt;
    private Date updatedAt;
}
