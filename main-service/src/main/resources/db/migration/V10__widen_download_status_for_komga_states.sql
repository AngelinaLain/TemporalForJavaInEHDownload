-- 旧部署中的 download_status 可能是历史 ENUM，无法保存新增的
-- “等待 Komga”和“Komga 入库失败”状态。统一为 VARCHAR，保留既有值。
-- 新建数据库从 V1 起已使用 VARCHAR；重复 MODIFY 在 MySQL 中安全且幂等。
ALTER TABLE `eh_galleries`
    MODIFY COLUMN `download_status` VARCHAR(50) NOT NULL DEFAULT 'PENDING';
