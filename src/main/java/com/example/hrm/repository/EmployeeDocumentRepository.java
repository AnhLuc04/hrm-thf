package com.example.hrm.repository;

import com.example.hrm.entity.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, Long> {
    List<EmployeeDocument> findByUserIdOrderByUploadedAtDesc(Long userId);
    List<EmployeeDocument> findByUserIdInAndCategory(Collection<Long> userIds, String category);
}
