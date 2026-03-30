package com.checker.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.EhGalleriesService;
import org.springframework.stereotype.Service;

/**
 * 画廊业务服务实现类
 */
@Service
public class EhGalleriesServiceImpl extends ServiceImpl<EhGalleriesMapper, EhGalleriesEntity> implements EhGalleriesService {
}
