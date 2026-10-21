package com.example.hrm.repository;

import com.example.hrm.entity.NotifyPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotifyPreferenceRepository extends JpaRepository<NotifyPreference, Long> {
}
