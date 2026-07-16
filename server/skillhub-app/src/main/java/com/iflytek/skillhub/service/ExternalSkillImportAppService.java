package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.origin.SkillOrigin;
import com.iflytek.skillhub.domain.skill.origin.SkillOriginRepository;
import com.iflytek.skillhub.domain.skill.origin.SkillVersionProvenance;
import com.iflytek.skillhub.domain.skill.origin.SkillVersionProvenanceRepository;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.dto.external.ExternalSkillDetail;
import com.iflytek.skillhub.dto.external.ExternalSkillFile;
import com.iflytek.skillhub.dto.external.ExternalSkillImportRequest;
import com.iflytek.skillhub.dto.external.ExternalSkillImportResult;
import com.iflytek.skillhub.dto.external.ExternalSkillImportValidation;
import com.iflytek.skillhub.external.skillhubcn.SkillHubCnClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalSkillImportAppService {
    private final SkillHubCnClient client;
    private final SkillPackageArchiveExtractor archiveExtractor;
    private final SkillPackageValidator packageValidator;
    private final SkillMetadataParser metadataParser;
    private final NamespaceRepository namespaceRepository;
    private final SkillRepository skillRepository;
    private final SkillOriginRepository originRepository;
    private final SkillVersionProvenanceRepository provenanceRepository;
    private final SkillPublishService publishService;
    private final ObjectMapper objectMapper;
    private final PrePublishValidator prePublishValidator;

    public ExternalSkillImportAppService(SkillHubCnClient client, SkillPackageArchiveExtractor archiveExtractor,
            SkillPackageValidator packageValidator, SkillMetadataParser metadataParser,
            NamespaceRepository namespaceRepository, SkillRepository skillRepository,
            SkillOriginRepository originRepository, SkillVersionProvenanceRepository provenanceRepository,
            SkillPublishService publishService, ObjectMapper objectMapper, PrePublishValidator prePublishValidator) {
        this.client = client;
        this.archiveExtractor = archiveExtractor;
        this.packageValidator = packageValidator;
        this.metadataParser = metadataParser;
        this.namespaceRepository = namespaceRepository;
        this.skillRepository = skillRepository;
        this.originRepository = originRepository;
        this.provenanceRepository = provenanceRepository;
        this.publishService = publishService;
        this.objectMapper = objectMapper;
        this.prePublishValidator = prePublishValidator;
    }

    public ExternalSkillImportValidation validate(String provider, String slug, String version,
            ExternalSkillImportRequest request, String userId) {
        assertProvider(provider);
        Namespace namespace = namespace(request.namespace());
        PackageSnapshot snapshot = snapshot(slug, version);
        Lineage lineage = resolveLineage(namespace, provider, slug);
        List<String> errors = new ArrayList<>(snapshot.validation().errors());
        ValidationResult prePublish = prePublishValidator.validate(new PrePublishValidator.SkillPackageContext(
                snapshot.entries(), snapshot.metadata(), userId, namespace.getId()));
        errors.addAll(prePublish.errors());
        if (lineage.conflict()) errors.add("A different local skill already uses this slug");
        List<String> warnings = new ArrayList<>(snapshot.validation().warnings());
        warnings.addAll(prePublish.warnings());
        if (!snapshot.metadata().name().equals(slug))
            warnings.add("Package name differs from the source slug; the source slug will be preserved");
        if (snapshot.metadata().version() == null || !snapshot.metadata().version().equals(version))
            warnings.add("Package version differs from the selected source version; the source version will be preserved");
        String warningDigest = digest(String.join("\n", warnings.stream().sorted().toList()) + "\n" + snapshot.sha256());
        return new ExternalSkillImportValidation(errors.isEmpty(), snapshot.sha256(), slug, version,
                List.copyOf(errors), List.copyOf(warnings), warningDigest, snapshot.license().status(),
                snapshot.license().expression(), lineage.status(), !snapshot.metadata().name().equals(slug),
                snapshot.metadata().version() == null || !snapshot.metadata().version().equals(version));
    }

    @Transactional
    public ExternalSkillImportResult importVersion(String provider, String slug, String version,
            ExternalSkillImportRequest request, String userId, Set<String> platformRoles,
            Map<Long, NamespaceRole> userNsRoles) {
        ExternalSkillImportValidation validation = validate(provider, slug, version, request, userId);
        boolean superAdmin = platformRoles != null && platformRoles.contains("SUPER_ADMIN");
        if (!validation.packageSha256().equalsIgnoreCase(request.packageSha256() == null ? "" : request.packageSha256()))
            throw new DomainBadRequestException("error.externalSkill.import.packageChanged");
        if (!validation.errors().isEmpty()) throw new DomainBadRequestException("error.externalSkill.import.invalid", String.join(", ", validation.errors()));
        if ("MISSING".equals(validation.licenseStatus()) && !(superAdmin && request.confirmMissingLicense()))
            throw new DomainForbiddenException("error.externalSkill.import.licenseRequired");
        if (!validation.warnings().isEmpty() && (!request.confirmWarnings()
                || !validation.warningDigest().equals(request.warningDigest())))
            throw new DomainBadRequestException("error.externalSkill.import.warningConfirmationRequired", validation.warningDigest());

        Namespace namespace = namespace(request.namespace());
        Lineage lineage = resolveLineage(namespace, provider, slug);
        if (lineage.conflict()) throw new DomainBadRequestException("error.externalSkill.import.lineageConflict", slug);
        if (lineage.origin() != null) {
            assertCanExtend(lineage.skill(), namespace.getId(), userId, platformRoles, userNsRoles);
            Optional<SkillVersionProvenance> existing = provenanceRepository
                    .findBySkillOriginIdAndSourceVersion(lineage.origin().getId(), version);
            if (existing.isPresent()) {
                if (existing.get().getPackageSha256().equalsIgnoreCase(validation.packageSha256()))
                    return new ExternalSkillImportResult("ALREADY_IMPORTED", namespace.getSlug(), slug, version,
                            lineage.skill().getId(), existing.get().getSkillVersionId(), "UNCHANGED", "NONE");
                throw new DomainBadRequestException("error.externalSkill.import.sourceVersionChanged", version);
            }
        }

        PackageSnapshot snapshot = snapshot(slug, version);
        if (!snapshot.sha256().equalsIgnoreCase(validation.packageSha256()))
            throw new DomainBadRequestException("error.externalSkill.import.packageChanged");
        SkillVisibility visibility = parseVisibility(request.visibility());
        SkillPublishService.PublishResult result = publishService.publishFromEntries(
                namespace.getSlug(), snapshot.entries(), userId, visibility, platformRoles, true,
                SkillPublishService.PublishOptions.trustedImport(slug, version,
                        lineage.skill() != null ? lineage.skill().getId() : null));
        SkillOrigin origin = lineage.origin();
        if (origin == null) {
            ExternalSkillDetail detail = client.detailFresh(slug);
            origin = originRepository.save(new SkillOrigin(result.skillId(), namespace.getId(), provider, slug,
                    detail.skill().owner(), detail.skill().sourceUrl(), detail.skill().sourceUrl()));
        }
        String securityReport;
        try { securityReport = objectMapper.writeValueAsString(client.detailFresh(slug).securityReports()); }
        catch (Exception ignored) { securityReport = null; }
        provenanceRepository.save(new SkillVersionProvenance(origin.getId(), result.version().getId(), version,
                snapshot.sha256(), snapshot.license().status(), snapshot.license().expression(), securityReport, userId));
        String status = result.version().getStatus().name();
        String nextAction = switch (status) {
            case "PENDING_REVIEW" -> "WAIT_FOR_REVIEW";
            case "SCANNING" -> "WAIT_FOR_SCAN";
            case "UPLOADED" -> "NONE";
            default -> "VIEW_SKILL";
        };
        return new ExternalSkillImportResult("IMPORTED", namespace.getSlug(), result.slug(), version,
                result.skillId(), result.version().getId(), status, nextAction);
    }

    private PackageSnapshot snapshot(String slug, String version) {
        byte[] archive = client.download(slug, version);
        try {
            List<PackageEntry> entries = archiveExtractor.extract(archive);
            ValidationResult validation = packageValidator.validate(entries);
            SkillMetadata metadata = entries.stream().filter(entry -> "SKILL.md".equals(entry.path())).findFirst()
                    .map(entry -> metadataParser.parse(new String(entry.content(), StandardCharsets.UTF_8)))
                    .orElse(new SkillMetadata("", "", null, "", Map.of()));
            List<ExternalSkillFile> expected = client.filesFresh(slug, version);
            List<String> integrityErrors = new ArrayList<>(validation.errors());
            for (ExternalSkillFile file : expected) {
                entries.stream().filter(entry -> entry.path().equals(file.path())).findFirst().ifPresentOrElse(entry -> {
                    if (!digest(entry.content()).equalsIgnoreCase(file.sha256()))
                        integrityErrors.add("File checksum mismatch: " + file.path());
                }, () -> integrityErrors.add("File missing from package: " + file.path()));
            }
            Set<String> expectedPaths = expected.stream().map(ExternalSkillFile::path).collect(java.util.stream.Collectors.toSet());
            entries.stream().map(PackageEntry::path).filter(path -> !expectedPaths.contains(path))
                    .forEach(path -> integrityErrors.add("Unexpected file in package: " + path));
            ValidationResult combined = ValidationResult.of(integrityErrors, validation.warnings());
            return new PackageSnapshot(archive, entries, digest(archive), metadata, combined,
                    license(entries, metadata, client.declaredLicenseFresh(slug)));
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new DomainBadRequestException("error.externalSkill.package.invalid");
        }
    }

    private License license(List<PackageEntry> entries, SkillMetadata metadata, String externalDeclaration) {
        if (externalDeclaration != null && !externalDeclaration.isBlank())
            return new License("DECLARED", externalDeclaration);
        Object declared = metadata.frontmatter().get("license");
        if (declared != null && !declared.toString().isBlank()) return new License("DECLARED", declared.toString());
        boolean file = entries.stream().map(PackageEntry::path).filter(path -> !path.contains("/"))
                .map(path -> path.toUpperCase(Locale.ROOT)).anyMatch(path -> path.startsWith("LICENSE") || path.startsWith("COPYING"));
        return file ? new License("FILE_PRESENT", null) : new License("MISSING", null);
    }

    private Lineage resolveLineage(Namespace namespace, String provider, String slug) {
        Optional<SkillOrigin> byCoordinate = originRepository
                .findByNamespaceIdAndProviderAndSourceSlug(namespace.getId(), provider, slug);
        if (byCoordinate.isPresent()) {
            Skill skill = skillRepository.findById(byCoordinate.get().getSkillId()).orElse(null);
            return new Lineage(skill, byCoordinate.get(), skill == null, skill == null ? "CONFLICT" : "EXISTING_LINEAGE");
        }
        List<Skill> sameSlug = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), slug);
        return sameSlug.isEmpty() ? new Lineage(null, null, false, "NEW")
                : new Lineage(sameSlug.getFirst(), null, true, "CONFLICT");
    }

    private void assertCanExtend(Skill skill, Long namespaceId, String userId, Set<String> roles, Map<Long, NamespaceRole> nsRoles) {
        NamespaceRole role = nsRoles == null ? null : nsRoles.get(namespaceId);
        if (!skill.getOwnerId().equals(userId) && role != NamespaceRole.ADMIN && role != NamespaceRole.OWNER
                && (roles == null || !roles.contains("SUPER_ADMIN")))
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
    }

    private Namespace namespace(String slug) {
        String clean = slug != null && slug.startsWith("@") ? slug.substring(1) : slug;
        return namespaceRepository.findBySlug(clean).orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", clean));
    }
    private SkillVisibility parseVisibility(String value) {
        if (value == null || value.isBlank()) return SkillVisibility.PRIVATE;
        try { return SkillVisibility.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException e) { throw new DomainBadRequestException("error.skill.visibility.invalid", value); }
    }
    private void assertProvider(String provider) { if (!SkillHubCnClient.PROVIDER.equals(provider)) throw new DomainBadRequestException("error.externalSkill.provider.unknown", provider); }
    private String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private record PackageSnapshot(byte[] archive, List<PackageEntry> entries, String sha256, SkillMetadata metadata, ValidationResult validation, License license) {}
    private record License(String status, String expression) {}
    private record Lineage(Skill skill, SkillOrigin origin, boolean conflict, String status) {}
}
