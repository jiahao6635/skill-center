package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.namespace.SlugValidator;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Publishes packaged skill artifacts into persisted skill and version records.
 *
 * <p>The service validates archive contents, parses metadata, stores files,
 * creates review tasks when needed, and updates the skill's lifecycle pointer.
 */
@Service
public class SkillPublishService {

    private static final String INITIAL_AUTO_VERSION = "0.1.0";
    private static final Logger log = LoggerFactory.getLogger(SkillPublishService.class);

    public record PublishResult(
            Long skillId,
            String slug,
            SkillVersion version
    ) {}

    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillFileRepository skillFileRepository;
    private final ObjectStorageService objectStorageService;
    private final SkillPackageValidator skillPackageValidator;
    private final SkillMetadataParser skillMetadataParser;
    private final PrePublishValidator prePublishValidator;
    private final ObjectMapper objectMapper;
    private final ReviewTaskRepository reviewTaskRepository;
    private final SecurityScanService securityScanService;
    private final SkillStorageDeletionCompensationService compensationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SkillPublishService(
            NamespaceRepository namespaceRepository,
            NamespaceMemberRepository namespaceMemberRepository,
            SkillRepository skillRepository,
            SkillVersionRepository skillVersionRepository,
            SkillFileRepository skillFileRepository,
            ObjectStorageService objectStorageService,
            SkillPackageValidator skillPackageValidator,
            SkillMetadataParser skillMetadataParser,
            PrePublishValidator prePublishValidator,
            ObjectMapper objectMapper,
            ReviewTaskRepository reviewTaskRepository,
            SecurityScanService securityScanService,
            SkillStorageDeletionCompensationService compensationService,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillFileRepository = skillFileRepository;
        this.objectStorageService = objectStorageService;
        this.skillPackageValidator = skillPackageValidator;
        this.skillMetadataParser = skillMetadataParser;
        this.prePublishValidator = prePublishValidator;
        this.objectMapper = objectMapper;
        this.reviewTaskRepository = reviewTaskRepository;
        this.securityScanService = securityScanService;
        this.compensationService = compensationService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public record DryRunResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            String resolvedSlug,
            String resolvedVersion
    ) {}

    /**
     * Validates a package without persisting anything. Used by the --dry-run CLI flow.
     *
     * <p>Warnings make the result invalid because the CLI publish flow uses
     * {@code confirmWarnings=false}, which causes real publish to reject any
     * package with warnings. Treating warnings as making the dry-run invalid
     * keeps the two flows in lockstep.
     */
    @Transactional(readOnly = true)
    public DryRunResult validateOnly(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles) {

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String resolvedSlug = null;
        String resolvedVersion = null;

        // 1. Find namespace
        var namespaceOpt = namespaceRepository.findBySlug(namespaceSlug);
        if (namespaceOpt.isEmpty()) {
            errors.add("Namespace not found: " + namespaceSlug);
            return new DryRunResult(false, errors, warnings, null, null);
        }
        Namespace namespace = namespaceOpt.get();
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            errors.add("Namespace is frozen: " + namespaceSlug);
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            errors.add("Namespace is archived: " + namespaceSlug);
        }

        // 2. Check membership
        boolean isSuperAdmin = platformRoles.contains("SUPER_ADMIN");
        if (!isSuperAdmin) {
            var member = namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), publisherId);
            if (member.isEmpty()) {
                errors.add("Publisher is not a member of namespace: " + namespaceSlug);
            }
        }
        if (requiresSecurityScanner(visibility) && !securityScanService.isEnabled()) {
            errors.add("error.security.scanner.required");
        }

        // 3. Package validation
        ValidationResult packageValidation = skillPackageValidator.validate(entries);
        errors.addAll(packageValidation.errors());
        warnings.addAll(packageValidation.warnings());

        if (!packageValidation.passed()) {
            return new DryRunResult(false, errors, warnings, null, null);
        }

        // 4. Parse SKILL.md
        PackageEntry skillMd = entries.stream()
                .filter(e -> e.path().equals("SKILL.md"))
                .findFirst()
                .orElse(null);
        if (skillMd == null) {
            errors.add("Missing required file: SKILL.md at root");
            return new DryRunResult(false, errors, warnings, null, null);
        }

        SkillMetadata metadata;
        try {
            metadata = skillMetadataParser.parse(new String(skillMd.content(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            errors.add("Invalid SKILL.md: " + e.getMessage());
            return new DryRunResult(false, errors, warnings, null, null);
        }

        try {
            resolvedSlug = SlugValidator.slugify(metadata.name());
        } catch (Exception e) {
            errors.add("Invalid skill name for slug generation: " + e.getMessage());
            return new DryRunResult(false, errors, warnings, resolvedSlug, resolvedVersion);
        }

        if (metadata.version() == null || metadata.version().isBlank()) {
            resolvedVersion = resolveAutoVersion(namespace.getId(), resolvedSlug, publisherId);
        } else {
            resolvedVersion = metadata.version();
        }

        // 5. Pre-publish validation (credential scan)
        PrePublishValidator.SkillPackageContext context = new PrePublishValidator.SkillPackageContext(
                entries, metadata, publisherId, namespace.getId());
        ValidationResult prePublishValidation = prePublishValidator.validate(context);
        errors.addAll(prePublishValidation.errors());
        warnings.addAll(prePublishValidation.warnings());

        // 6. Slug conflict, archived skill, and version-exists checks
        if (resolvedSlug != null && errors.isEmpty()) {
            List<Skill> existingSkills = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), resolvedSlug);
            for (Skill existing : existingSkills) {
                if (existing.getOwnerId().equals(publisherId)) {
                    if (existing.getStatus() == SkillStatus.ARCHIVED) {
                        errors.add("Cannot publish to archived skill: " + resolvedSlug);
                    }
                    if (resolvedVersion != null) {
                        var existingVersion = skillVersionRepository.findBySkillIdAndVersion(existing.getId(), resolvedVersion);
                        if (existingVersion.isPresent() && existingVersion.get().getStatus() == SkillVersionStatus.PUBLISHED) {
                            errors.add("Version already published: " + resolvedVersion);
                        }
                    }
                } else {
                    boolean hasPublished = !skillVersionRepository
                            .findBySkillIdAndStatus(existing.getId(), SkillVersionStatus.PUBLISHED)
                            .isEmpty();
                    if (hasPublished) {
                        errors.add("Name conflict: slug \"" + resolvedSlug + "\" is already published by another user");
                        break;
                    }
                }
            }
        }

        // Warnings make valid=false: real publish rejects them when confirmWarnings=false,
        // which is the only mode the CLI uses today.
        boolean valid = errors.isEmpty() && warnings.isEmpty();
        return new DryRunResult(valid, errors, warnings, resolvedSlug, resolvedVersion);
    }

    /**
     * Publishes an extracted package into the target namespace.
     *
     * <p>Super administrators may auto-publish, while regular publishers
     * usually create a pending-review version.
     */
    @Transactional
    public PublishResult publishFromEntries(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            java.util.Set<String> platformRoles) {
        return publishFromEntries(namespaceSlug, entries, publisherId, visibility, platformRoles, false);
    }

    @Transactional
    public PublishResult publishFromEntries(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            java.util.Set<String> platformRoles,
            boolean confirmWarnings) {
        return publishFromEntriesInternal(namespaceSlug, entries, publisherId, visibility, platformRoles, confirmWarnings, false, false);
    }

    /**
     * Rebuilds a new version from an already published version by copying its
     * stored files and rewriting the embedded metadata version field.
     */
    @Transactional
    public PublishResult rereleasePublishedVersion(
            Long skillId,
            String sourceVersion,
            String targetVersion,
            String publisherId,
            Map<Long, NamespaceRole> userNamespaceRoles,
            boolean confirmWarnings) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.notFound", skillId));
        assertCanManageLifecycle(skill, publisherId, userNamespaceRoles);

        SkillVersion publishedVersion = skillVersionRepository.findBySkillIdAndVersion(skillId, sourceVersion)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.version.notFound", sourceVersion));
        if (publishedVersion.getStatus() != SkillVersionStatus.PUBLISHED) {
            throw new DomainBadRequestException("error.skill.version.notPublished", sourceVersion);
        }
        if (skillVersionRepository.findBySkillIdAndVersion(skillId, targetVersion).isPresent()) {
            throw new DomainBadRequestException("error.skill.version.exists", targetVersion);
        }

        List<PackageEntry> entries = rebuildEntriesForRerelease(skillId, publishedVersion.getId(), targetVersion);

        // Rerelease follows the same visibility-based workflow as normal publish:
        // - PRIVATE skills go to UPLOADED status
        // - PUBLIC/NAMESPACE_ONLY skills go to PENDING_REVIEW (or UPLOADED after scan)
        return publishFromEntriesInternal(
                resolveNamespaceSlug(skill.getNamespaceId()),
                entries,
                publisherId,
                skill.getVisibility(),
                Set.of(),
                confirmWarnings,  // confirmWarnings: honour caller's choice for rerelease
                false,  // forceAutoPublish=false: respect visibility rules
                true
        );
    }

    private PublishResult publishFromEntriesInternal(
            String namespaceSlug,
            List<PackageEntry> entries,
            String publisherId,
            SkillVisibility visibility,
            Set<String> platformRoles,
            boolean confirmWarnings,
            boolean forceAutoPublish,
            boolean bypassMembershipCheck) {

        // 1. Find namespace by slug
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespaceSlug));
        assertNamespaceWritable(namespace);

        boolean isSuperAdmin = platformRoles.contains("SUPER_ADMIN");

        // 2. Check publisher is member unless SUPER_ADMIN short-circuits permission checks
        if (!isSuperAdmin && !bypassMembershipCheck) {
            namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), publisherId)
                    .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.publisher.notMember", namespaceSlug));
        }

        // 3. Validate package
        ValidationResult packageValidation = skillPackageValidator.validate(entries);
        if (!packageValidation.passed()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.package.invalid",
                    String.join(", ", packageValidation.errors()));
        }

        // 4. Parse SKILL.md
        PackageEntry skillMd = entries.stream()
                .filter(e -> e.path().equals("SKILL.md"))
                .findFirst()
                .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.skillMd.notFound"));

        String skillMdContent = new String(skillMd.content());
        SkillMetadata metadata = skillMetadataParser.parse(skillMdContent);
        String skillSlug = SlugValidator.slugify(metadata.name());
        if (metadata.version() == null || metadata.version().isBlank()) {
            String autoVersion = resolveAutoVersion(namespace.getId(), skillSlug, publisherId);
            metadata = new SkillMetadata(metadata.name(), metadata.description(), autoVersion, metadata.body(), metadata.frontmatter());
        }

        // 5. Run PrePublishValidator
        PrePublishValidator.SkillPackageContext context = new PrePublishValidator.SkillPackageContext(
                entries, metadata, publisherId, namespace.getId());
        ValidationResult prePublishValidation = prePublishValidator.validate(context);
        if (!prePublishValidation.passed()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.precheck.failed",
                    String.join(", ", prePublishValidation.errors()));
        }
        List<String> publishWarnings = new ArrayList<>(packageValidation.warnings());
        publishWarnings.addAll(prePublishValidation.warnings());
        if (!confirmWarnings && !publishWarnings.isEmpty()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.precheck.confirmRequired",
                    formatValidationMessages(publishWarnings));
        }
        if (requiresSecurityScanner(visibility) && !securityScanService.isEnabled()) {
            throw new DomainBadRequestException("error.security.scanner.required");
        }

        // 6. Find or create Skill record (with owner isolation)
        List<Skill> existingSkills = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), skillSlug);

        // Check if any other owner's skill has published versions
        // Only PUBLISHED status blocks same-name publishing (UPLOADED/PENDING_REVIEW allowed)
        for (Skill existing : existingSkills) {
            if (!existing.getOwnerId().equals(publisherId)) {
                boolean hasPublished = !skillVersionRepository
                        .findBySkillIdAndStatus(existing.getId(), SkillVersionStatus.PUBLISHED)
                        .isEmpty();
                if (hasPublished) {
                    // Distinguish between PRIVATE and PUBLIC/NAMESPACE_ONLY conflicts
                    if (existing.getVisibility() == SkillVisibility.PRIVATE) {
                        throw new DomainBadRequestException("error.skill.publish.nameConflict.private", skillSlug);
                    } else {
                        throw new DomainBadRequestException("error.skill.publish.nameConflict", skillSlug);
                    }
                }
            }
        }

        // Find or create skill for current user
        Skill skill = skillRepository.findByNamespaceIdAndSlugAndOwnerId(namespace.getId(), skillSlug, publisherId)
                .orElseGet(() -> {
                    Skill newSkill = new Skill(namespace.getId(), skillSlug, publisherId, visibility);
                    newSkill.setCreatedBy(publisherId);
                    return skillRepository.save(newSkill);
                });

        if (skill.getStatus() == SkillStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.skill.publish.archived", skillSlug);
        }

        // 6c. Auto-withdraw pending review versions
        // When publishing a new version, existing PENDING_REVIEW versions are withdrawn to UPLOADED status
        List<SkillVersion> pendingVersions = skillVersionRepository
                .findBySkillIdAndStatus(skill.getId(), SkillVersionStatus.PENDING_REVIEW);
        for (SkillVersion pending : pendingVersions) {
            reviewTaskRepository.findBySkillVersionIdAndStatus(pending.getId(), ReviewTaskStatus.PENDING)
                    .ifPresent(reviewTaskRepository::delete);
            pending.setStatus(SkillVersionStatus.UPLOADED);
            skillVersionRepository.save(pending);
        }

        // 7. Check version doesn't already exist
        java.util.Optional<SkillVersion> existingVersion = skillVersionRepository.findBySkillIdAndVersion(skill.getId(), metadata.version());
        if (existingVersion.isPresent()) {
            SkillVersion matchedVersion = existingVersion.get();
            if (matchedVersion.getStatus() == SkillVersionStatus.PUBLISHED) {
                throw new DomainBadRequestException("error.skill.version.exists", metadata.version());
            }
            deleteReplaceableVersionArtifacts(skill, matchedVersion, namespaceSlug);
        }

        // 7b. Determine review-exempt fast path: a trusted TEAM manager updating an already-published
        // skill may skip human review, letting the security scan act as the sole publish gate.
        boolean autoReviewExempt = isAutoReviewExempt(namespace, skill, publisherId, visibility, isSuperAdmin);

        // 8. Create SkillVersion
        SkillVersion version = new SkillVersion(skill.getId(), metadata.version(), publisherId);
        version.setRequestedVisibility(visibility);
        boolean autoPublish = forceAutoPublish || isSuperAdmin;
        if (autoPublish) {
            version.setStatus(SkillVersionStatus.PUBLISHED);
            version.setPublishedAt(currentTime());
        } else if (visibility == SkillVisibility.PRIVATE) {
            // PRIVATE skill goes to UPLOADED status, no review task created
            version.setStatus(SkillVersionStatus.UPLOADED);
            version.setPublishedAt(currentTime());
        } else if (autoReviewExempt) {
            // Review-exempt: enter SCANNING now; the scan verdict decides publish vs. fallback review.
            version.setStatus(SkillVersionStatus.SCANNING);
            version.setAutoPublishOnScanPass(true);
        } else {
            version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        }

        // Store metadata as JSON
        try {
            String metadataJson = objectMapper.writeValueAsString(metadata);
            version.setParsedMetadataJson(metadataJson);
            version.setManifestJson(objectMapper.writeValueAsString(buildManifest(entries)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize metadata", e);
        }

        version = skillVersionRepository.save(version);

        // 9. Upload each file to storage and compute SHA-256
        List<SkillFile> skillFiles = new ArrayList<>();
        long totalSize = 0;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            HexFormat hexFormat = HexFormat.of();

            for (PackageEntry entry : entries) {
                String storageKey = String.format("skills/%d/%d/%s", skill.getId(), version.getId(), entry.path());

                // Upload to storage
                objectStorageService.putObject(
                        storageKey,
                        new ByteArrayInputStream(entry.content()),
                        entry.size(),
                        entry.contentType()
                );

                // Compute SHA-256
                byte[] hash = digest.digest(entry.content());
                String sha256 = hexFormat.formatHex(hash);

                // Create SkillFile record
                SkillFile skillFile = new SkillFile(
                        version.getId(),
                        entry.path(),
                        entry.size(),
                        entry.contentType(),
                        sha256,
                        storageKey
                );
                skillFiles.add(skillFile);
                totalSize += entry.size();

                digest.reset();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to process files", e);
        }

        // 10. Save SkillFile records
        skillFileRepository.saveAll(skillFiles);

        // 10.5 Build and upload bundle zip for download endpoints
        byte[] bundleZip = buildBundle(entries);
        String bundleKey = String.format("packages/%d/%d/bundle.zip", skill.getId(), version.getId());
        objectStorageService.putObject(
                bundleKey,
                new ByteArrayInputStream(bundleZip),
                bundleZip.length,
                "application/zip"
        );

        // 11. Update version stats
        version.setFileCount(skillFiles.size());
        version.setTotalSize(totalSize);
        version.setBundleReady(true);
        version.setDownloadReady(!skillFiles.isEmpty());
        skillVersionRepository.save(version);

        // Create review task for PUBLIC/NAMESPACE_ONLY (not PRIVATE, not review-exempt fast path)
        if (!autoPublish && visibility != SkillVisibility.PRIVATE && !autoReviewExempt) {
            ReviewTask reviewTask = new ReviewTask(version.getId(), namespace.getId(), publisherId);
            ReviewTask savedReviewTask = reviewTaskRepository.save(reviewTask);
            eventPublisher.publishEvent(new ReviewSubmittedEvent(
                    savedReviewTask.getId(),
                    skill.getId(),
                    version.getId(),
                    savedReviewTask.getSubmittedBy(),
                    savedReviewTask.getNamespaceId()
            ));
        }

        // Trigger security scan for all versions (including auto-publish)
        if (securityScanService.isEnabled()) {
            securityScanService.triggerScan(version.getId(), entries, publisherId);
        }

        // 12. Update skill metadata and move the published pointer for auto-publish flows
        skill.setDisplayName(metadata.name());
        skill.setSummary(metadata.description());
        if (autoPublish || visibility == SkillVisibility.PRIVATE) {
            // Update latestVersionId for autoPublish or PRIVATE skill (UPLOADED status)
            skill.setLatestVersionId(version.getId());
            skill.setVisibility(visibility);
        }
        skill.setUpdatedBy(publisherId);
        skillRepository.save(skill);

        if (autoPublish) {
            eventPublisher.publishEvent(new SkillPublishedEvent(skill.getId(), version.getId(), publisherId));
        }

        // 13. Return identifiers for the created version
        return new PublishResult(skill.getId(), skill.getSlug(), version);
    }

    private String resolveAutoVersion(Long namespaceId, String skillSlug, String publisherId) {
        if (skillSlug == null || skillSlug.isBlank()) {
            return INITIAL_AUTO_VERSION;
        }
        return skillRepository.findByNamespaceIdAndSlugAndOwnerId(namespaceId, skillSlug, publisherId)
                .map(this::nextAutoVersion)
                .orElse(INITIAL_AUTO_VERSION);
    }

    private String nextAutoVersion(Skill skill) {
        return skillVersionRepository.findBySkillId(skill.getId()).stream()
                .map(SkillVersion::getVersion)
                .max(this::compareSemver)
                .map(this::bumpPatchOrFallback)
                .orElse(INITIAL_AUTO_VERSION);
    }

    private String bumpPatchOrFallback(String currentVersion) {
        Semver parsed = Semver.tryParse(currentVersion);
        if (parsed == null) {
            return INITIAL_AUTO_VERSION;
        }
        return parsed.bumpPatch().toString();
    }

    private int compareSemver(String left, String right) {
        Semver leftVersion = Semver.tryParse(left);
        Semver rightVersion = Semver.tryParse(right);
        if (leftVersion == null && rightVersion == null) {
            return 0;
        }
        if (leftVersion == null) {
            return -1;
        }
        if (rightVersion == null) {
            return 1;
        }
        return leftVersion.compareTo(rightVersion);
    }

    private record Semver(int major, int minor, int patch) implements Comparable<Semver> {
        static Semver tryParse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String core = raw.strip();
            int plus = core.indexOf('+');
            if (plus >= 0) {
                core = core.substring(0, plus);
            }
            int dash = core.indexOf('-');
            if (dash >= 0) {
                core = core.substring(0, dash);
            }
            String[] parts = core.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            try {
                return new Semver(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])
                );
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        Semver bumpPatch() {
            return new Semver(major, minor, patch + 1);
        }

        @Override
        public int compareTo(Semver other) {
            int majorCompare = Integer.compare(major, other.major);
            if (majorCompare != 0) {
                return majorCompare;
            }
            int minorCompare = Integer.compare(minor, other.minor);
            if (minorCompare != 0) {
                return minorCompare;
            }
            return Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private void deleteReplaceableVersionArtifacts(Skill skill, SkillVersion version, String namespaceSlug) {
        if (version.getStatus() == SkillVersionStatus.PUBLISHED) {
            throw new DomainBadRequestException("error.skill.version.exists", version.getVersion());
        }

        // PostgreSQL prevents deleting a skill_version while skill.latest_version_id still references it.
        if (version.getId().equals(skill.getLatestVersionId())) {
            skill.setLatestVersionId(null);
            skillRepository.save(skill);
            skillRepository.flush();
        }

        reviewTaskRepository.findBySkillVersionIdAndStatus(version.getId(), ReviewTaskStatus.PENDING)
                .ifPresent(reviewTaskRepository::delete);

        List<SkillFile> files = skillFileRepository.findByVersionId(version.getId());
        List<String> storageKeys = new ArrayList<>();
        files.stream()
                .map(SkillFile::getStorageKey)
                .filter(key -> key != null && !key.isBlank())
                .forEach(storageKeys::add);
        storageKeys.add(buildBundleStorageKey(skill.getId(), version.getId()));
        deleteStorageAfterCommit(skill, namespaceSlug, storageKeys);
        skillFileRepository.deleteByVersionId(version.getId());
        securityScanService.softDeleteByVersionId(version.getId());
        skillVersionRepository.delete(version);
        skillVersionRepository.flush();
    }

    private boolean requiresSecurityScanner(SkillVisibility visibility) {
        return visibility == SkillVisibility.PUBLIC || visibility == SkillVisibility.NAMESPACE_ONLY;
    }

    /**
     * Determines whether this publish qualifies for the review-exempt fast path.
     *
     * <p>All of the following must hold: the namespace is a TEAM (not GLOBAL); the publisher is an
     * OWNER or ADMIN of that namespace; the skill already has a PUBLISHED version (this is an update,
     * not a first release); the visibility is not PRIVATE; the security scanner is enabled (so the
     * scan can act as the publish gate); and the publisher is not a super admin (super admins already
     * auto-publish). Exemption only skips human review — the security scan remains mandatory.
     */
    private boolean isAutoReviewExempt(Namespace namespace,
                                       Skill skill,
                                       String publisherId,
                                       SkillVisibility visibility,
                                       boolean isSuperAdmin) {
        if (isSuperAdmin) {
            return false;
        }
        if (visibility == SkillVisibility.PRIVATE) {
            return false;
        }
        if (!securityScanService.isEnabled()) {
            return false;
        }
        if (namespace.getType() != NamespaceType.TEAM) {
            return false;
        }
        NamespaceRole role = namespaceMemberRepository
                .findByNamespaceIdAndUserId(namespace.getId(), publisherId)
                .map(NamespaceMember::getRole)
                .orElse(null);
        if (role != NamespaceRole.OWNER && role != NamespaceRole.ADMIN) {
            return false;
        }
        return !skillVersionRepository
                .findBySkillIdAndStatus(skill.getId(), SkillVersionStatus.PUBLISHED)
                .isEmpty();
    }

    private String resolveNamespaceSlug(Long namespaceId) {
        return namespaceRepository.findById(namespaceId)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.notFound", namespaceId))
                .getSlug();
    }

    private void deleteStorageAfterCommit(Skill skill, String namespaceSlug, List<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStorageWithCompensation(skill, namespaceSlug, storageKeys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStorageWithCompensation(skill, namespaceSlug, storageKeys);
            }
        });
    }

    private void deleteStorageWithCompensation(Skill skill, String namespaceSlug, List<String> storageKeys) {
        try {
            objectStorageService.deleteObjects(storageKeys);
        } catch (RuntimeException ex) {
            compensationService.recordFailure(
                    skill.getId(),
                    namespaceSlug,
                    skill.getSlug(),
                    storageKeys,
                    ex.getMessage()
            );
            log.error("Failed to delete replaced version storage after commit [skillId={}, versionKeys={}]",
                    skill.getId(), storageKeys, ex);
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    private String formatValidationMessages(List<String> warnings) {
        return warnings.stream()
                .map(warning -> "- " + warning)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    }

    private void assertNamespaceWritable(Namespace namespace) {
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            throw new DomainBadRequestException("error.namespace.frozen", namespace.getSlug());
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.namespace.archived", namespace.getSlug());
        }
    }

    private void assertCanManageLifecycle(Skill skill,
                                          String actorUserId,
                                          Map<Long, NamespaceRole> userNamespaceRoles) {
        NamespaceRole namespaceRole = userNamespaceRoles.get(skill.getNamespaceId());
        boolean canManage = skill.getOwnerId().equals(actorUserId)
                || namespaceRole == NamespaceRole.ADMIN
                || namespaceRole == NamespaceRole.OWNER;
        if (!canManage) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
    }

    private Instant currentTime() {
        return Instant.now(clock);
    }

    private List<PackageEntry> rebuildEntriesForRerelease(Long skillId, Long versionId, String targetVersion) {
        List<SkillFile> files = skillFileRepository.findByVersionId(versionId).stream()
                .sorted(Comparator.comparing(SkillFile::getFilePath))
                .toList();
        List<PackageEntry> entries = new ArrayList<>(files.size());
        for (SkillFile file : files) {
            byte[] content = readAllBytes(objectStorageService.getObject(file.getStorageKey()));
            if ("SKILL.md".equals(file.getFilePath())) {
                content = rewriteSkillMdVersion(content, targetVersion);
            }
            entries.add(new PackageEntry(
                    file.getFilePath(),
                    content,
                    content.length,
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream"
            ));
        }
        return entries;
    }

    private byte[] readAllBytes(InputStream inputStream) {
        try (InputStream in = inputStream) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read stored skill file", e);
        }
    }

    private byte[] rewriteSkillMdVersion(byte[] content, String targetVersion) {
        String skillMdContent = new String(content);
        SkillMetadata metadata = skillMetadataParser.parse(skillMdContent);
        Map<String, Object> frontmatter = new LinkedHashMap<>(metadata.frontmatter());
        frontmatter.put("version", targetVersion);
        String rewritten = "---\n"
                + new Yaml().dump(frontmatter).trim()
                + "\n---\n"
                + metadata.body();
        return rewritten.getBytes();
    }

    private List<Map<String, Object>> buildManifest(List<PackageEntry> entries) {
        return entries.stream()
                .map(entry -> Map.<String, Object>of(
                        "path", entry.path(),
                        "size", entry.size(),
                        "contentType", entry.contentType()))
                .toList();
    }

    private byte[] buildBundle(List<PackageEntry> entries) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (PackageEntry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.path());
                zipOutputStream.putNextEntry(zipEntry);
                zipOutputStream.write(entry.content());
                zipOutputStream.closeEntry();
            }
            zipOutputStream.finish();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build bundle zip", e);
        }
    }
}
