package com.iflytek.skillhub.auth.entity;

import java.io.Serializable;
import java.util.Objects;

public class ApiTokenNamespaceGrantId implements Serializable {
    private Long tokenId;
    private Long namespaceId;

    public ApiTokenNamespaceGrantId() {}
    public ApiTokenNamespaceGrantId(Long tokenId, Long namespaceId) { this.tokenId = tokenId; this.namespaceId = namespaceId; }
    public boolean equals(Object value) {
        return value instanceof ApiTokenNamespaceGrantId other
                && Objects.equals(tokenId, other.tokenId) && Objects.equals(namespaceId, other.namespaceId);
    }
    public int hashCode() { return Objects.hash(tokenId, namespaceId); }
}
