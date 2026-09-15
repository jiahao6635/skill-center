package com.iflytek.skillhub.domain.skill;

/** Serializes name claims before acquiring a skill mutation lock. Requires an active transaction. */
public interface NamespacePublishLock {
    void lock(Long namespaceId);
}
