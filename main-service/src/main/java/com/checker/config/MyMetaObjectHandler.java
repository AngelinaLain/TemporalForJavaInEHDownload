package com.checker.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 字段自动填充：插入时补全 crawled_at / updated_at，更新时补全 updated_at。
 * 与数据库 DEFAULT CURRENT_TIMESTAMP 双保险，保证增量同步的时间戳可靠。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Date now = new Date();
        strictInsertFill(metaObject, "crawledAt", Date.class, now);
        strictInsertFill(metaObject, "updatedAt", Date.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", Date.class, new Date());
    }
}
