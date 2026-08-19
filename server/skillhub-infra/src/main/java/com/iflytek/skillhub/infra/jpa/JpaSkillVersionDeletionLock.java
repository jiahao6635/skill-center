package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVersionDeletionLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates destructive version mutations without adding another row-lock edge to the
 * skill/version/review-task lock graph. Delete-version, replacement-publish, and hard-delete
 * flows use the same per-skill key before their first write.
 *
 * <p>PostgreSQL uses a transaction-scoped advisory lock. Direct JDBC is intentional because JPQL
 * has no representation for {@code pg_advisory_xact_lock}, and the lock must run on the connection
 * enlisted in the caller's existing transaction before any row is written. The fixed namespace is
 * XORed with the skill ID, which is a one-to-one mapping over {@code long} values.
 *
 * <p>H2 does not implement PostgreSQL advisory locks, so tests fall back to locking the skill root
 * row. After either coordination mechanism succeeds, the managed {@link Skill} is explicitly
 * refreshed so callers make the version-count decision from current database state.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaSkillVersionDeletionLock implements SkillVersionDeletionLock {

    private static final long ADVISORY_LOCK_NAMESPACE = 0x534B494C4C44454CL;
    private static final String POSTGRESQL_PRODUCT_NAME = "PostgreSQL";
    private static final String H2_PRODUCT_NAME = "H2";
    private static final String POSTGRESQL_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";
    private static final String H2_LOCK_SQL = "SELECT id FROM skill WHERE id = ? FOR UPDATE";

    private final EntityManager entityManager;

    public JpaSkillVersionDeletionLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Skill> lockAndRefresh(Long skillId) {
        Objects.requireNonNull(skillId, "skillId must not be null");

        boolean rowMayExist = entityManager.unwrap(Session.class).doReturningWork(
                connection -> acquireCoordinationLock(connection, skillId));
        if (!rowMayExist) {
            return Optional.empty();
        }

        Skill skill = entityManager.find(Skill.class, skillId);
        if (skill == null) {
            return Optional.empty();
        }
        try {
            entityManager.refresh(skill);
            return Optional.of(skill);
        } catch (EntityNotFoundException ignored) {
            return Optional.empty();
        }
    }

    private boolean acquireCoordinationLock(Connection connection, long skillId) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        if (POSTGRESQL_PRODUCT_NAME.equals(productName)) {
            acquirePostgresqlLock(connection, advisoryLockKey(skillId));
            return true;
        }
        if (H2_PRODUCT_NAME.equals(productName)) {
            return acquireH2Lock(connection, skillId);
        }
        throw new IllegalStateException("Unsupported database for skill version deletion locking: " + productName);
    }

    private void acquirePostgresqlLock(Connection connection, long lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(POSTGRESQL_LOCK_SQL)) {
            statement.setLong(1, lockKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("PostgreSQL did not acquire the skill version deletion lock");
                }
            }
        }
    }

    private boolean acquireH2Lock(Connection connection, long skillId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(H2_LOCK_SQL)) {
            statement.setLong(1, skillId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private long advisoryLockKey(long skillId) {
        return ADVISORY_LOCK_NAMESPACE ^ skillId;
    }
}
