package com.checker.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.checker.entity.EhGalleriesEntity;

/**
 * 画廊业务服务接口，继承 MyBatis-Plus IService 提供通用服务能力
 */
public interface EhGalleriesService extends IService<EhGalleriesEntity> {
    /**
     * 批量补全缺失的文件大小记录
     */
    void batchUpdateFileSizes();
}
