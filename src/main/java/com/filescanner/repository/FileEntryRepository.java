package com.filescanner.repository;

import com.filescanner.entity.FileEntry;
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
public interface FileEntryRepository extends JpaRepository<FileEntry, Long> {

    Page<FileEntry> findByScanIdAndParentPathOrderByDirectoryDescNameAsc(
            Long scanId, String parentPath, Pageable pageable);

    Page<FileEntry> findByScanIdAndFullPathContainingIgnoreCase(
            Long scanId, String pathFragment, Pageable pageable);

    List<FileEntry> findTop20ByScanIdAndDirectoryFalseOrderBySizeBytesDesc(Long scanId);

    @Query("""
            SELECT e.extension AS ext,
                   COUNT(e)    AS cnt,
                   SUM(e.sizeBytes) AS totalSize
            FROM   FileEntry e
            WHERE  e.scanId    = :scanId
              AND  e.directory = false
              AND  e.extension IS NOT NULL
            GROUP BY e.extension
            ORDER BY cnt DESC
            """)
    List<Object[]> countByExtension(@Param("scanId") Long scanId);

    @Query("""
            SELECT e.depth AS depth, COUNT(e) AS cnt
            FROM   FileEntry e
            WHERE  e.scanId = :scanId
            GROUP BY e.depth
            ORDER BY e.depth
            """)
    List<Object[]> countByDepth(@Param("scanId") Long scanId);

    @Query(value = """
            SELECT
              SUM(CASE WHEN DATEDIFF('DAY', last_modified, NOW()) < 1    THEN 1 ELSE 0 END),
              SUM(CASE WHEN DATEDIFF('DAY', last_modified, NOW()) BETWEEN 1  AND 6   THEN 1 ELSE 0 END),
              SUM(CASE WHEN DATEDIFF('DAY', last_modified, NOW()) BETWEEN 7  AND 29  THEN 1 ELSE 0 END),
              SUM(CASE WHEN DATEDIFF('DAY', last_modified, NOW()) BETWEEN 30 AND 364 THEN 1 ELSE 0 END),
              SUM(CASE WHEN DATEDIFF('DAY', last_modified, NOW()) >= 365 THEN 1 ELSE 0 END)
            FROM file_entry
            WHERE scan_id = :scanId AND is_directory = false
            """, nativeQuery = true)
    List<Object[]> ageBuckets(@Param("scanId") Long scanId);

    @Query("""
            SELECT COUNT(e), SUM(e.sizeBytes)
            FROM   FileEntry e
            WHERE  e.scanId = :scanId AND e.directory = false
            """)
    List<Object[]> totalCountAndSize(@Param("scanId") Long scanId);

    @Query("""
            SELECT e FROM FileEntry e
            WHERE e.scanId = :scanId AND e.directory = false
              AND e.name IN (
                  SELECT e2.name FROM FileEntry e2
                  WHERE e2.scanId = :scanId AND e2.directory = false
                  GROUP BY e2.name HAVING COUNT(e2) > 1
              )
            ORDER BY e.name ASC, e.sizeBytes DESC
            """)
    List<FileEntry> findAllDuplicateFiles(@Param("scanId") Long scanId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FileEntry e WHERE e.scanId = :scanId")
    void deleteByScanId(@Param("scanId") Long scanId);
}
