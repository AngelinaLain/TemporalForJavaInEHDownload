package com.checker.dao.archive;

import com.checker.model.dataobject.archive.ArchiveSyncReviewEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写归档匹配的待审核记录，保存候选文件和人工处理进度。
 */
@Mapper
public interface ArchiveSyncReviewMapper extends BaseMapper<ArchiveSyncReviewEntity> {
}
