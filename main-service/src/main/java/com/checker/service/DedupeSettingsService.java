package com.checker.service;

import com.checker.common.DedupeSettings;
import com.checker.mapper.DedupeSettingsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理去重参数的持久化，为一次完整判断提供稳定的参数快照。 */
@Service
public class DedupeSettingsService {
    private final DedupeSettingsMapper mapper;

    public DedupeSettingsService(DedupeSettingsMapper mapper) {
        this.mapper = mapper;
    }

    /** 每次业务操作只读一次数据库。没有保存记录才用默认值，数据库异常不能悄悄放宽策略。 */
    public DedupeSettings current() {
        DedupeSettings settings = mapper.current();
        return settings == null ? DedupeSettings.defaults() : settings;
    }

    /** 保存已通过范围和相互关系校验的整份参数，不自动重写历史去重关系或人工结论。 */
    @Transactional
    public DedupeSettings save(DedupeSettings settings) {
        if (settings == null) throw new IllegalArgumentException("去重参数不能为空");
        mapper.save(settings);
        return settings;
    }
}
