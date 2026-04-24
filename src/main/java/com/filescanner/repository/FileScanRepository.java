package com.filescanner.repository;

import com.filescanner.entity.FileScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileScanRepository extends JpaRepository<FileScan, Long> {

    List<FileScan> findAllByOrderByStartedAtDesc();
}
