package com.iflytek.skillhub.auth.repository;

import com.iflytek.skillhub.auth.entity.ApiTokenNamespaceGrant;
import com.iflytek.skillhub.auth.entity.ApiTokenNamespaceGrantId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenNamespaceGrantRepository extends JpaRepository<ApiTokenNamespaceGrant, ApiTokenNamespaceGrantId> {
    List<ApiTokenNamespaceGrant> findByTokenId(Long tokenId);
    void deleteByTokenId(Long tokenId);
}
