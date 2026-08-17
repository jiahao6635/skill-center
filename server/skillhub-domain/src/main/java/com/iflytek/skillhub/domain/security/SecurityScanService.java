package com.iflytek.skillhub.domain.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.service.SkillPublicationService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityScanService {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanService.class);
    private static final String TEMP_DIR = "/tmp/skillhub-scans";
    private static final Path TEMP_BASE_DIR = Paths.get(TEMP_DIR).toAbsolutePath().normalize();

    private final SecurityAuditRepository auditRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final ScanTaskProducer scanTaskProducer;
    private final ObjectMapper objectMapper;
    private final SkillPublicationService skillPublicationService;
    private final SkillRepository skillRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final String scanMode;
    private final boolean enabled;

    public SecurityScanService(SecurityAuditRepository auditRepository,
                               SkillVersionRepository skillVersionRepository,
                               ScanTaskProducer scanTaskProducer,
                               ObjectMapper objectMapper,
                               SkillPublicationService skillPublicationService,
                               SkillRepository skillRepository,
                               ReviewTaskRepository reviewTaskRepository,
                               ApplicationEventPublisher eventPublisher,
                               @Value("${skillhub.security.scanner.mode:local}") String scanMode,
                               @Value("${skillhub.security.scanner.enabled:false}") boolean enabled) {
        this.auditRepository = auditRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.scanTaskProducer = scanTaskProducer;
        this.objectMapper = objectMapper;
        this.skillPublicationService = skillPublicationService;
        this.skillRepository = skillRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.eventPublisher = eventPublisher;
        this.scanMode = scanMode;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void triggerScan(Long versionId, List<PackageEntry> entries, String publisherId) {
        if (!enabled) {
            log.debug("Security scanner disabled, skipping trigger for versionId={}", versionId);
            return;
        }

        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));

        String packagePath = null;
        String bundleKey = null;
        if ("upload".equalsIgnoreCase(scanMode)) {
            validateUploadEntries(entries);
            bundleKey = buildBundleStorageKey(version.getSkillId(), versionId);
        } else {
            packagePath = saveTempDirectory(versionId, entries).toString();
        }
        // Always create a new audit record — supports multiple rounds per version
        auditRepository.save(new SecurityAudit(versionId, ScannerType.SKILL_SCANNER));
        scanTaskProducer.publishScanTask(new ScanTask(
                UUID.randomUUID().toString(),
                versionId,
                packagePath,
                bundleKey,
                publisherId,
                System.currentTimeMillis(),
                Map.of("scannerType", ScannerType.SKILL_SCANNER.getValue())
        ));
        // Only transition to SCANNING if the version is not already published (auto-publish flow)
        if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
            version.setStatus(SkillVersionStatus.SCANNING);
            skillVersionRepository.save(version);
        }
    }

    @Transactional
    public void processScanResult(Long versionId, ScannerType scannerType, SecurityScanResponse response) {
        SecurityAudit audit = auditRepository.findLatestActiveByVersionIdAndScannerType(versionId, scannerType)
                .orElseThrow(() -> new IllegalStateException(
                        "SecurityAudit not found for versionId=" + versionId + ", scannerType=" + scannerType));
        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));

        audit.setScanId(response.scanId());
        audit.setVerdict(response.verdict());
        audit.setIsSafe(response.verdict() == SecurityVerdict.SAFE);
        audit.setMaxSeverity(response.maxSeverity());
        audit.setFindingsCount(response.findingsCount());
        audit.setFindings(serializeFindings(response.findings()));
        audit.setScanDurationSeconds(response.scanDurationSeconds());
        audit.setScannedAt(Instant.now(Clock.systemUTC()));
        auditRepository.save(audit);

        // Only transition from SCANNING — leave PUBLISHED/REJECTED/YANKED untouched
        if (version.getStatus() == SkillVersionStatus.SCANNING) {
            if (version.getRequestedVisibility() == SkillVisibility.PRIVATE) {
                version.setStatus(SkillVersionStatus.UPLOADED);
                skillVersionRepository.save(version);
            } else if (version.isAutoPublishOnScanPass()) {
                // Review-exempt fast path: the scan is the sole publish gate.
                if (response.verdict() == SecurityVerdict.SAFE) {
                    Skill skill = skillRepository.findById(version.getSkillId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Skill not found: " + version.getSkillId()));
                    skillPublicationService.publishVersion(skill, version, version.getCreatedBy());
                } else {
                    // Any non-SAFE verdict falls back to mandatory human review.
                    fallbackToReview(version);
                }
            } else {
                version.setStatus(SkillVersionStatus.PENDING_REVIEW);
                skillVersionRepository.save(version);
            }
        } else {
            skillVersionRepository.save(version);
        }
    }

    /**
     * Falls a review-exempt version back to the normal human-review flow, e.g. when the scan verdict
     * is not SAFE or the scan failed outright. Clears the fast-path flag, moves the version to
     * PENDING_REVIEW, creates a ReviewTask (attributed to the original publisher), and emits a
     * {@link ReviewSubmittedEvent}. No-op if the version is not on the fast path.
     */
    @Transactional
    public void fallbackToReview(Long versionId) {
        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));
        if (!version.isAutoPublishOnScanPass()) {
            return;
        }
        fallbackToReview(version);
    }

    private void fallbackToReview(SkillVersion version) {
        version.setAutoPublishOnScanPass(false);
        version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        skillVersionRepository.save(version);

        Skill skill = skillRepository.findById(version.getSkillId())
                .orElseThrow(() -> new IllegalStateException("Skill not found: " + version.getSkillId()));

        // Guard against a duplicate task if one already exists for this version.
        if (reviewTaskRepository.findBySkillVersionIdAndStatus(version.getId(), ReviewTaskStatus.PENDING).isPresent()) {
            return;
        }
        ReviewTask reviewTask = new ReviewTask(version.getId(), skill.getNamespaceId(), version.getCreatedBy());
        ReviewTask saved = reviewTaskRepository.save(reviewTask);
        eventPublisher.publishEvent(new ReviewSubmittedEvent(
                saved.getId(),
                skill.getId(),
                version.getId(),
                saved.getSubmittedBy(),
                saved.getNamespaceId()));
    }

    private Path saveTempDirectory(Long versionId, List<PackageEntry> entries) {
        try {
            Path skillDir = TEMP_BASE_DIR.resolve(String.valueOf(versionId)).normalize();
            Files.createDirectories(skillDir);
            for (PackageEntry entry : entries) {
                Path filePath = resolveSafeChild(skillDir, entry.path());
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(filePath, entry.content());
            }
            return skillDir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save temp directory for versionId: " + versionId, e);
        }
    }

    private void validateUploadEntries(List<PackageEntry> entries) {
        for (PackageEntry entry : entries) {
            safeZipEntryName(entry.path());
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    private String serializeFindings(List<SecurityFinding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize findings for security audit", e);
            return "[]";
        }
    }

    private Path resolveSafeChild(Path baseDir, String entryPath) {
        Path resolved = baseDir.resolve(entryPath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        return resolved;
    }

    private String safeZipEntryName(String entryPath) {
        Path normalized = Paths.get(entryPath).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        String safePath = normalized.toString().replace('\\', '/');
        if (safePath.isBlank() || safePath.startsWith("../")) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        return safePath;
    }

    /**
     * Soft delete all audit records for a given skill version.
     * Called before physically deleting a skill version to preserve audit history.
     */
    @Transactional
    public void softDeleteByVersionId(Long versionId) {
        List<SecurityAudit> audits = auditRepository.findAllActiveBySkillVersionId(versionId);
        if (audits.isEmpty()) {
            log.debug("No active security audits to soft-delete for versionId={}", versionId);
            return;
        }
        audits.forEach(SecurityAudit::markAsDeleted);
        auditRepository.saveAll(audits);
        log.info("Soft deleted {} security audit(s) for versionId={}", audits.size(), versionId);
    }

    /**
     * Physically delete all audit records for a given skill version.
     * Called during hard delete when the entire skill is being permanently removed.
     */
    @Transactional
    public void hardDeleteByVersionId(Long versionId) {
        auditRepository.deleteBySkillVersionId(versionId);
    }
}
