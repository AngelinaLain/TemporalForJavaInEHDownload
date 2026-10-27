package com.checker.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 在数据库新增或更新记录时补齐时间字段，便于按更新时间做增量同步。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 新建记录时填充抓取时间和更新时间，已明确提供的值按框架填充规则保留。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        Date now = new Date();
        strictInsertFill(metaObject, "crawledAt", Date.class, now);
        strictInsertFill(metaObject, "updatedAt", Date.class, now);
    }

    /**
     * 修改记录时补充更新时间，让后续同步能够识别本次变更。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", Date.class, new Date());
    }
}
