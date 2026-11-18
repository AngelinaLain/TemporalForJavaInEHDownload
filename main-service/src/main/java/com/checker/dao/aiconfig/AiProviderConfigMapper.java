package com.checker.dao.aiconfig;

import com.checker.model.dataobject.aiconfig.AiProviderConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写模型供应商配置，供配置服务保存连接信息和调用权限。
 */
@Mapper
public interface AiProviderConfigMapper extends BaseMapper<AiProviderConfigEntity> {
}
