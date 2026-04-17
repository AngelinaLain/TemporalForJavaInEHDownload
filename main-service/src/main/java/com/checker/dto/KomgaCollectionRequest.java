package com.checker.dto;

import lombok.Data;

import java.util.List;

/**
 * Komga 合集Dto
 */
@Data
public class KomgaCollectionRequest {

    /**
     *  中文合集名，例如："纯爱 / Vanilla"
     */
    private String collectionName;
    /**
     * 英文标签列表，例如：["female:sole female", "male:sole male"]
     */
    private List<String> tags;

    /**
     * 默认行为：包含列表中【任意一个】tag就加入，还是必须包含【所有】tag？
     * 这里加一个参数以便后续扩展，默认 false (满足任意一个即可)
     */
    private boolean matchAllTags = false;
}