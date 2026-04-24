package com.filescanner.repository;

import com.filescanner.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    @Query("SELECT a FROM AuditEvent a WHERE a.folderPath = :path AND (:type = 'ALL' OR a.eventType = :type) ORDER BY a.occurredAt DESC")
    Page<AuditEvent> findByFolderAndType(@Param("path") String path,
                                         @Param("type") String type,
                                         Pageable pageable);

    @Query("SELECT a.eventType, COUNT(a) FROM AuditEvent a WHERE a.folderPath = :path GROUP BY a.eventType ORDER BY COUNT(a) DESC")
    List<Object[]> countByEventType(@Param("path") String path);

    long countByFolderPath(String folderPath);

    @Modifying
    @Transactional
    @Query("DELETE FROM AuditEvent a WHERE a.folderPath = :folderPath")
    void deleteByFolderPath(@Param("folderPath") String folderPath);
}
