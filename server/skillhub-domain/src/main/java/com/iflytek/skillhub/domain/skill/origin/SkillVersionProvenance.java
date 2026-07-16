package com.iflytek.skillhub.domain.skill.origin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "skill_version_provenance")
public class SkillVersionProvenance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_origin_id", nullable = false)
    private Long skillOriginId;

    @Column(name = "skill_version_id", nullable = false)
    private Long skillVersionId;

    @Column(name = "source_version", nullable = false, length = 128)
    private String sourceVersion;

    @Column(name = "package_sha256", nullable = false, length = 64)
    private String packageSha256;

    @Column(name = "license_status", nullable = false, length = 32)
    private String licenseStatus;

    @Column(name = "license_expression")
    private String licenseExpression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "security_report_json", columnDefinition = "jsonb")
    private String securityReportJson;

    @Column(name = "imported_by", nullable = false)
    private String importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt = Instant.now();

    protected SkillVersionProvenance() {}

    public SkillVersionProvenance(Long skillOriginId, Long skillVersionId, String sourceVersion,
                                  String packageSha256, String licenseStatus, String licenseExpression,
                                  String securityReportJson, String importedBy) {
        this.skillOriginId = skillOriginId;
        this.skillVersionId = skillVersionId;
        this.sourceVersion = sourceVersion;
        this.packageSha256 = packageSha256;
        this.licenseStatus = licenseStatus;
        this.licenseExpression = licenseExpression;
        this.securityReportJson = securityReportJson;
        this.importedBy = importedBy;
    }

    public Long getId() { return id; }
    public Long getSkillOriginId() { return skillOriginId; }
    public Long getSkillVersionId() { return skillVersionId; }
    public String getSourceVersion() { return sourceVersion; }
    public String getPackageSha256() { return packageSha256; }
}
