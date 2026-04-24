package com.filescanner.repository;

import com.filescanner.entity.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    List<CloudAccount> findAllByOrderByCreatedAtDesc();

    List<CloudAccount> findByProvider(String provider);
}
