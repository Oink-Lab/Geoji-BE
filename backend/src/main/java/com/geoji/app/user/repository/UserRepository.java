package com.geoji.app.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.geoji.app.user.entity.User;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCaseAndStatus(String email, User.Status status);
    boolean existsByEmailIgnoreCaseAndStatus(String email, User.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedById(UUID id);
}
