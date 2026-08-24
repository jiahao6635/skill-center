package com.iflytek.skillhub.infra.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Test;

class JpaSkillVersionDeletionLockTest {

    private static final long ADVISORY_LOCK_NAMESPACE = 0x534B494C4C44454CL;

    @Test
    void lockAndRefresh_postgresqlUsesHibernateConnectionAndStableAdvisoryKey() {
        long skillId = 42L;
        Skill skill = new Skill(1L, "test-skill", "owner", SkillVisibility.PRIVATE);
        LockTestHarness harness = new LockTestHarness("PostgreSQL", true, skill);
        JpaSkillVersionDeletionLock deletionLock = new JpaSkillVersionDeletionLock(harness.entityManager());

        Optional<Skill> firstResult = deletionLock.lockAndRefresh(skillId);
        Optional<Skill> secondResult = deletionLock.lockAndRefresh(skillId);

        assertThat(firstResult).containsSame(skill);
        assertThat(secondResult).containsSame(skill);
        assertThat(harness.connectionsUsedByHibernate()).hasSize(2);
        assertThat(harness.connectionsUsedByHibernate().get(0)).isSameAs(harness.connection());
        assertThat(harness.connectionsUsedByHibernate().get(1)).isSameAs(harness.connection());
        assertThat(harness.preparedSql())
                .containsExactly(
                        "SELECT pg_advisory_xact_lock(?)",
                        "SELECT pg_advisory_xact_lock(?)");
        assertThat(harness.boundLongs())
                .containsExactly(ADVISORY_LOCK_NAMESPACE ^ skillId, ADVISORY_LOCK_NAMESPACE ^ skillId);
        assertThat(harness.executeQueryCount()).isEqualTo(2);
        assertThat(harness.refreshCount()).isEqualTo(2);
    }

    @Test
    void lockAndRefresh_h2LocksSkillRowAndReturnsEmptyWhenRowDoesNotExist() {
        long skillId = 84L;
        LockTestHarness harness = new LockTestHarness("H2", false, null);
        JpaSkillVersionDeletionLock deletionLock = new JpaSkillVersionDeletionLock(harness.entityManager());

        Optional<Skill> result = deletionLock.lockAndRefresh(skillId);

        assertThat(result).isEmpty();
        assertThat(harness.preparedSql()).containsExactly("SELECT id FROM skill WHERE id = ? FOR UPDATE");
        assertThat(harness.boundLongs()).containsExactly(skillId);
        assertThat(harness.executeQueryCount()).isEqualTo(1);
        assertThat(harness.findCount()).isZero();
        assertThat(harness.refreshCount()).isZero();
    }

    private static final class LockTestHarness {

        private final String productName;
        private final boolean rowExists;
        private final Skill skill;
        private final List<Connection> connectionsUsedByHibernate = new ArrayList<>();
        private final List<String> preparedSql = new ArrayList<>();
        private final List<Long> boundLongs = new ArrayList<>();
        private final Connection connection;
        private final EntityManager entityManager;
        private int executeQueryCount;
        private int findCount;
        private int refreshCount;

        private LockTestHarness(String productName, boolean rowExists, Skill skill) {
            this.productName = productName;
            this.rowExists = rowExists;
            this.skill = skill;
            this.connection = proxy(Connection.class, this::invokeConnection);
            Session session = proxy(Session.class, this::invokeSession);
            this.entityManager = proxy(EntityManager.class, (proxy, method, args) -> switch (method.getName()) {
                case "unwrap" -> session;
                case "find" -> {
                    findCount++;
                    yield skill;
                }
                case "refresh" -> {
                    refreshCount++;
                    yield null;
                }
                default -> unexpected(method);
            });
        }

        private Object invokeSession(Object proxy, Method method, Object[] args) throws Exception {
            if (!"doReturningWork".equals(method.getName())) {
                return unexpected(method);
            }
            connectionsUsedByHibernate.add(connection);
            @SuppressWarnings("unchecked")
            ReturningWork<Object> work = (ReturningWork<Object>) args[0];
            return work.execute(connection);
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMetaData" -> proxy(DatabaseMetaData.class, (metadata, metadataMethod, metadataArgs) -> {
                    if ("getDatabaseProductName".equals(metadataMethod.getName())) {
                        return productName;
                    }
                    return unexpected(metadataMethod);
                });
                case "prepareStatement" -> {
                    preparedSql.add((String) args[0]);
                    yield preparedStatement();
                }
                default -> unexpected(method);
            };
        }

        private PreparedStatement preparedStatement() {
            return proxy(PreparedStatement.class, (statement, method, args) -> switch (method.getName()) {
                case "setLong" -> {
                    boundLongs.add((Long) args[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    executeQueryCount++;
                    AtomicBoolean firstRead = new AtomicBoolean(true);
                    yield proxy(
                            ResultSet.class,
                            (resultSet, resultSetMethod, resultSetArgs) -> switch (resultSetMethod.getName()) {
                                case "next" -> firstRead.getAndSet(false) && rowExists;
                                case "close" -> null;
                                default -> unexpected(resultSetMethod);
                            });
                }
                case "close" -> null;
                default -> unexpected(method);
            });
        }

        private EntityManager entityManager() {
            return entityManager;
        }

        private Connection connection() {
            return connection;
        }

        private List<Connection> connectionsUsedByHibernate() {
            return connectionsUsedByHibernate;
        }

        private List<String> preparedSql() {
            return preparedSql;
        }

        private List<Long> boundLongs() {
            return boundLongs;
        }

        private int executeQueryCount() {
            return executeQueryCount;
        }

        private int findCount() {
            return findCount;
        }

        private int refreshCount() {
            return refreshCount;
        }
    }

    private static Object unexpected(Method method) {
        throw new AssertionError(
                "Unexpected call to " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
