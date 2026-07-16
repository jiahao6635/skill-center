package com.iflytek.skillhub.domain.skill.origin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "skill_origin")
public class SkillOrigin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    @Column(name = "namespace_id", nullable = false)
    private Long namespaceId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "source_slug", nullable = false)
    private String sourceSlug;

    @Column(name = "source_owner")
    private String sourceOwner;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "upstream_url")
    private String upstreamUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SkillOrigin() {}

    public SkillOrigin(Long skillId, Long namespaceId, String provider, String sourceSlug,
                       String sourceOwner, String sourceUrl, String upstreamUrl) {
        this.skillId = skillId;
        this.namespaceId = namespaceId;
        this.provider = provider;
        this.sourceSlug = sourceSlug;
        this.sourceOwner = sourceOwner;
        this.sourceUrl = sourceUrl;
        this.upstreamUrl = upstreamUrl;
    }

    public Long getId() { return id; }
    public Long getSkillId() { return skillId; }
    public Long getNamespaceId() { return namespaceId; }
    public String getProvider() { return provider; }
    public String getSourceSlug() { return sourceSlug; }
    public String getSourceOwner() { return sourceOwner; }
    public String getSourceUrl() { return sourceUrl; }
    public String getUpstreamUrl() { return upstreamUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
