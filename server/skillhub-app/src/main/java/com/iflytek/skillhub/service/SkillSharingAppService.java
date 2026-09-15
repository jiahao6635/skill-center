package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.security.SecurityAudit;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillSharingService;
import com.iflytek.skillhub.domain.skill.validation.*;
import com.iflytek.skillhub.dto.*;
import com.iflytek.skillhub.repository.SkillSharingQueryRepository;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates validation, immutable stored-package scanning and sharing domain operations. */
@Service
public class SkillSharingAppService {
    private final SkillSharingService sharing;
    private final SkillSharingQueryRepository queries;
    private final SkillShareRequestRepository requests;
    private final SkillRepository skills;
    private final SkillFileRepository files;
    private final NamespaceRepository namespaces;
    private final ObjectStorageService storage;
    private final SkillPackageValidator validator;
    private final SkillMetadataParser parser;
    private final PrePublishValidator prePublishValidator;
    private final SecurityScanService scanner;
    private final VisibilityChecker visibilityChecker;

    public SkillSharingAppService(SkillSharingService sharing, SkillSharingQueryRepository queries,
            SkillShareRequestRepository requests, SkillRepository skills, SkillFileRepository files,
            NamespaceRepository namespaces, ObjectStorageService storage, SkillPackageValidator validator,
            SkillMetadataParser parser, PrePublishValidator prePublishValidator, SecurityScanService scanner,
            VisibilityChecker visibilityChecker) {
        this.sharing = sharing; this.queries = queries; this.requests = requests; this.skills = skills;
        this.files = files; this.namespaces = namespaces; this.storage = storage; this.validator = validator;
        this.parser = parser; this.prePublishValidator = prePublishValidator; this.scanner = scanner;
        this.visibilityChecker = visibilityChecker;
    }

    @Transactional(readOnly = true)
    public SkillSharePrecheckResponse precheck(Long skillId, SkillShareCommand command, String actor) {
        Skill skill = sharing.requireOwner(skillId, actor);
        try {
            sharing.validateTarget(skill, command.versionId(), command.targetNamespaceId(), command.targetVisibility(), actor);
            return validatePackage(command, actor, loadPackage(command.versionId()));
        } catch (LocalizedDomainException ex) {
            return new SkillSharePrecheckResponse(false, List.of(ex.messageCode()), List.of());
        }
    }

    @Transactional
    public SkillShareResponse submit(Long skillId, SkillShareCommand command, String actor) {
        if (!scanner.isEnabled()) throw new DomainBadRequestException("error.security.scanner.required");
        SkillShareRequest request = sharing.submit(skillId, command.versionId(), command.targetNamespaceId(),
                command.targetVisibility(), actor, command.idempotencyKey(), command.confirmPublic());
        if (request.getStatus().isActive() && request.getSecurityAuditId() == null) {
            List<PackageEntry> entries = loadPackage(command.versionId());
            SkillSharePrecheckResponse check = validatePackage(command, actor, entries);
            if (!check.errors().isEmpty()) throw new DomainBadRequestException("error.skill.publish.package.invalid", String.join("; ", check.errors()));
            if (!check.warnings().isEmpty() && !command.confirmWarnings()) {
                throw new DomainBadRequestException("error.skill.publish.precheck.confirmRequired", String.join("\n", check.warnings()));
            }
            SecurityAudit audit = scanner.triggerSharingScan(command.versionId(), entries, actor);
            if (audit == null) throw new DomainBadRequestException("error.security.scanner.required");
            request.setSecurityAuditId(audit.getId());
            requests.save(request);
        }
        return queries.response(request);
    }

    @Transactional
    public SkillShareResponse withdraw(Long skillId, Long requestId, String actor) {
        return queries.response(sharing.withdraw(skillId, requestId, actor));
    }

    @Transactional(readOnly = true)
    public SkillLocationResponse location(Long skillId, String actor, Map<Long, NamespaceRole> roles) {
        Skill skill = skills.findById(skillId).orElseThrow(() -> new DomainBadRequestException("error.skill.notFound", skillId));
        if (!visibilityChecker.canAccess(skill, actor, roles == null ? Map.of() : roles)) throw new DomainForbiddenException("sharing.notFound");
        Namespace ns = namespaces.findById(skill.getNamespaceId()).orElseThrow();
        return new SkillLocationResponse(skillId, ns.getSlug(), skill.getSlug());
    }

    private SkillSharePrecheckResponse validatePackage(SkillShareCommand command, String actor, List<PackageEntry> entries) {
        if (!scanner.isEnabled()) throw new DomainBadRequestException("error.security.scanner.required");
        ValidationResult validation = validator.validate(entries);
        List<String> errors = new ArrayList<>(validation.errors());
        List<String> warnings = new ArrayList<>(validation.warnings());
        if (errors.isEmpty()) {
            PackageEntry skillMd = entries.stream().filter(e -> e.path().equals("SKILL.md")).findFirst().orElseThrow();
            SkillMetadata metadata = parser.parse(new String(skillMd.content(), StandardCharsets.UTF_8));
            ValidationResult precheck = prePublishValidator.validate(new PrePublishValidator.SkillPackageContext(
                    entries, metadata, actor, command.targetNamespaceId()));
            errors.addAll(precheck.errors()); warnings.addAll(precheck.warnings());
        }
        return new SkillSharePrecheckResponse(errors.isEmpty(), errors, warnings);
    }

    private List<PackageEntry> loadPackage(Long versionId) {
        List<PackageEntry> entries = new ArrayList<>();
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (SkillFile file : files.findByVersionId(versionId)) {
                try (var stream = storage.getObject(file.getStorageKey())) {
                    byte[] bytes = stream.readNBytes(10 * 1024 * 1024 + 1);
                    if (bytes.length != file.getFileSize() || !HexFormat.of().formatHex(sha256.digest(bytes)).equals(file.getSha256())) {
                        throw new DomainBadRequestException("sharing.packageChanged");
                    }
                    entries.add(new PackageEntry(file.getFilePath(), bytes, bytes.length, file.getContentType()));
                }
            }
        } catch (IOException ex) {
            throw new DomainBadRequestException("sharing.packageUnavailable");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        return entries;
    }
}
