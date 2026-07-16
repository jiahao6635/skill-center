package com.iflytek.skillhub.dto.external;

import com.fasterxml.jackson.databind.JsonNode;

public record ExternalSkillVersion(String version, String changelog, Long createdAt, JsonNode securityReports) {}
