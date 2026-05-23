package com.exam.backend.repository;

import com.exam.backend.model.UsedToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsedTokenRepository extends JpaRepository<UsedToken, String> {
    // existsById(jti) is inherited from JpaRepository
}
