package com.checker.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;

import java.util.List;

/**
 * 给通用数据库接口补充批量插入能力，让一次抓到的多条画廊能用一条插入语句写入。
 */
public class BatchSqlInjector extends DefaultSqlInjector {

    /**
     * 保留框架原有的数据库操作，并注册批量插入方法；插入字段按定义的策略过滤。
     */
    @Override
    public List<AbstractMethod> getMethodList(Class<?> mapperClass, TableInfo tableInfo) {
        List<AbstractMethod> methods = super.getMethodList(mapperClass, tableInfo);
        // 只排除逻辑删除字段与「仅更新时自动填充」的字段（如 fill=UPDATE 的 update_time）。
        // 注意：fill=INSERT 的字段（如 crawled_at）必须保留，否则批量 INSERT 会漏列，
        // 依赖数据库默认值，一旦表没有默认值就报「Field doesn't have a default value」。
        methods.add(new InsertBatchSomeColumn(field ->
                !field.isLogicDelete() && field.getFieldFill() != FieldFill.UPDATE
        ));
        return methods;
    }
}
