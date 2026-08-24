package com.iflytek.skillhub.domain.usage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/**
 * The single write path of the usage ledger. Always inserts the event row;
 * upserts the lifetime unique-actor row only for DOWNLOAD / VIEW with a skill
 * id (SEARCH is query-level with a null skill id, UPLOAD / UPDATE / PUBLISH
 * must never feed unique downloader / viewer counts). A dedup-key collision is
 * a successful dedup: the event insert affects 0 rows and the actor row is
 * left untouched; every other integrity violation propagates to the caller.
 */
@Service
public class SkillUsageRecorder {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final SkillUsageEventRepository eventRepository;
    private final SkillUsageActorRepository actorRepository;

    public SkillUsageRecorder(SkillUsageEventRepository eventRepository,
                              SkillUsageActorRepository actorRepository) {
        this.eventRepository = eventRepository;
        this.actorRepository = actorRepository;
    }

    @Transactional
    public void record(UsageCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.occurredAt(), "occurredAt");
        Objects.requireNonNull(command.action(), "action");
        Objects.requireNonNull(command.actorKind(), "actorKind");
        if (command.actorKey() == null || command.actorKey().isBlank()) {
            throw new IllegalArgumentException("actorKey must not be blank");
        }

        SkillUsageRequestContext context = command.requestContext();
        String client = context != null && context.client() != null
                ? context.client().name()
                : SkillUsageClient.UNKNOWN.name();
        String authMethod = context != null && context.authMethod() != null
                ? context.authMethod().name()
                : SkillUsageAuthMethod.UNKNOWN.name();
        String payloadJson = command.payloadJson() == null || command.payloadJson().isBlank()
                ? "{}"
                : command.payloadJson();

        int inserted = eventRepository.insert(
                command.occurredAt(),
                command.action().name(),
                command.skillId(),
                command.skillVersionId(),
                command.namespaceId(),
                command.actorUserId(),
                command.actorKey(),
                command.actorKind().name(),
                client,
                authMethod,
                context == null ? null : context.requestId(),
                context == null ? null : context.clientIp(),
                truncateUserAgent(context == null ? null : context.userAgent()),
                command.dedupKey(),
                payloadJson);
        if (inserted == 0) {
            return;
        }
        if (command.skillId() != null
                && (command.action() == SkillUsageAction.DOWNLOAD
                    || command.action() == SkillUsageAction.VIEW)) {
            actorRepository.upsert(
                    command.skillId(),
                    command.action().name(),
                    command.actorKey(),
                    command.occurredAt(),
                    client);
        }
    }

    private static String truncateUserAgent(String userAgent) {
        if (userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH) {
            return userAgent;
        }
        return userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
