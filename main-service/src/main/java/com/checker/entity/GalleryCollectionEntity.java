package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("eh_gallery_collections")
public class GalleryCollectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Date createdAt;
    private Date updatedAt;
}
