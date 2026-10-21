package com.example.hrm.repository;

import com.example.hrm.entity.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {

    List<AppNotification> findTop60ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    boolean existsByUserIdAndRefKey(Long userId, String refKey);

    @Modifying
    @Query("update AppNotification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
