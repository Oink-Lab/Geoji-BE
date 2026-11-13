package com.geoji.app.challenge.repository;

import com.geoji.app.challenge.entity.Challenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChallengeRepository extends JpaRepository<Challenge, UUID> {
    Optional<Challenge> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Challenge c where c.id = :id and c.user.id = :userId")
    Optional<Challenge> findLocked(UUID id, UUID userId);

    Optional<Challenge> findFirstByUserIdAndStatusIn(UUID userId, List<Challenge.Status> statuses);

    long countByUserId(UUID userId);

    List<Challenge> findByUserIdAndStatusIn(UUID userId, List<Challenge.Status> statuses);

    List<Challenge> findByUserIdAndStatusInOrderByFinishedAtDescStartDateDesc(
        UUID userId, List<Challenge.Status> statuses
    );

    List<Challenge> findByStatusAndStartDateLessThanEqual(
        Challenge.Status status, LocalDate date
    );

    List<Challenge> findByStatusAndEndDateBefore(
        Challenge.Status status, LocalDate date
    );

}
