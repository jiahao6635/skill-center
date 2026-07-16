package com.iflytek.skillhub.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_token_namespace_grant")
@IdClass(ApiTokenNamespaceGrantId.class)
public class ApiTokenNamespaceGrant {
    @Id
    @Column(name = "token_id")
    private Long tokenId;

    @Id
    @Column(name = "namespace_id")
    private Long namespaceId;

    protected ApiTokenNamespaceGrant() {}
    public ApiTokenNamespaceGrant(Long tokenId, Long namespaceId) { this.tokenId = tokenId; this.namespaceId = namespaceId; }
    public Long getTokenId() { return tokenId; }
    public Long getNamespaceId() { return namespaceId; }
}
