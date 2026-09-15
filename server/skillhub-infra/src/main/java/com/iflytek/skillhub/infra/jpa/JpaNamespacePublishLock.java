package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.NamespacePublishLock;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

/**
 * A namespace row lock serializes name claims across owners, including private-to-team migrations.
 * JDBC acquires the lock on the current transaction without flushing dirty entities first. The
 * portable FOR UPDATE syntax also works with the H2 PostgreSQL compatibility test database.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaNamespacePublishLock implements NamespacePublishLock {
    private final EntityManager entityManager;
    public JpaNamespacePublishLock(EntityManager entityManager) { this.entityManager = entityManager; }
    @Override
    public void lock(Long namespaceId) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (var statement = connection.prepareStatement("SELECT id FROM namespace WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, namespaceId);
                try (var result = statement.executeQuery()) { result.next(); }
            }
        });
    }
}
