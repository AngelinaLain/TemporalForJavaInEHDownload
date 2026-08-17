package com.checker.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;

import java.util.List;

/**
 * 自定义 SQL 注入器：在通用 CRUD 基础上增加 {@link InsertBatchSomeColumn} 方法，
 * 用于大批量写入时一次性生成 INSERT INTO ... VALUES (...),(...) 语句，
 * 相比逐条 INSERT 显著提升批量入库性能（默认排除 null 字段与自动填充字段）。
 */
public class BatchSqlInjector extends DefaultSqlInjector {

    @Override
    public List<AbstractMethod> getMethodList(Class<?> mapperClass, TableInfo tableInfo) {
        List<AbstractMethod> methods = super.getMethodList(mapperClass, tableInfo);
        methods.add(new InsertBatchSomeColumn(field ->
                !field.isLogicDelete()
                        && field.getFieldFill() == FieldFill.DEFAULT
        ));
        return methods;
    }
}
