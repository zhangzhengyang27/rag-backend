package org.example.repository;

import org.example.entity.UserEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户仓库：按用户名查询与判重。
 */
@Repository
public interface UserRepository extends BaseRepository<UserEntity> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);
}
