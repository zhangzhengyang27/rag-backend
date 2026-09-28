package org.example.repository;

import org.example.entity.KbRuntimeConfigEntity;

import java.util.Optional;

/**
 * 知识库运行时配置覆盖仓库。
 */
public interface KbRuntimeConfigRepository extends BaseRepository<KbRuntimeConfigEntity> {

    Optional<KbRuntimeConfigEntity> findByCfgKey(String cfgKey);
}
