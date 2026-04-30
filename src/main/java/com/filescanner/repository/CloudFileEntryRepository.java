package com.filescanner.repository;

import com.filescanner.entity.CloudFileEntry;
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
public interface CloudFileEntryRepository extends JpaRepository<CloudFileEntry, Long> {

    Page<CloudFileEntry> findByAccountIdAndParentPathOrderByDirectoryDescNameAsc(
            Long accountId, String parentPath, Pageable pageable);

    Page<CloudFileEntry> findByAccountIdAndNameContainingIgnoreCaseOrderByNameAsc(
            Long accountId, String name, Pageable pageable);

    List<CloudFileEntry> findTop20ByAccountIdAndDirectoryFalseOrderBySizeBytesDesc(Long accountId);

    @Query("""
            SELECT c.extension, COUNT(c), SUM(c.sizeBytes)
            FROM   CloudFileEntry c
            WHERE  c.accountId = :id
              AND  c.directory = false
              AND  c.extension IS NOT NULL
            GROUP BY c.extension
            ORDER BY COUNT(c) DESC
            """)
    List<Object[]> statsByExtension(@Param("id") Long accountId);

    @Query("""
            SELECT COUNT(c), SUM(c.sizeBytes)
            FROM   CloudFileEntry c
            WHERE  c.accountId = :id AND c.directory = false
            """)
    List<Object[]> totalCountAndSize(@Param("id") Long accountId);

    long countByAccountId(Long accountId);

    @Query("""
            SELECT c FROM CloudFileEntry c
            WHERE c.accountId = :accountId AND c.directory = false
              AND c.name IN (
                  SELECT c2.name FROM CloudFileEntry c2
                  WHERE c2.accountId = :accountId AND c2.directory = false
                  GROUP BY c2.name HAVING COUNT(c2) > 1
              )
            ORDER BY c.name ASC, c.sizeBytes DESC
            """)
    List<CloudFileEntry> findAllDuplicateFiles(@Param("accountId") Long accountId);

    @Modifying
    @Transactional
    @Query("DELETE FROM CloudFileEntry c WHERE c.accountId = :accountId")
    void deleteByAccountId(@Param("accountId") Long accountId);
}
