package com.checker.service;

import com.checker.dto.KomgaCollectionRequest;

/** 定义按标签查找 Komga 系列并创建或补充合集的业务入口。 */
public interface KomgaCollectionService {
    /** 按请求标签收集系列，创建或补充目标 Komga 合集。 */
    void processCollectionAssignment(KomgaCollectionRequest request);
}
