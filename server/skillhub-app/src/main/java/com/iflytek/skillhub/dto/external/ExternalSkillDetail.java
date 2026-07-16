package com.iflytek.skillhub.dto.external;

import com.fasterxml.jackson.databind.JsonNode;

public record ExternalSkillDetail(ExternalSkillSummary skill, ExternalSkillVersion latestVersion, JsonNode securityReports) {}
