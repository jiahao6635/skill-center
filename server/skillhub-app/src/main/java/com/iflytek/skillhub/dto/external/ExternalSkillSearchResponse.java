package com.iflytek.skillhub.dto.external;

import java.util.List;

public record ExternalSkillSearchResponse(List<ExternalSkillSummary> items, long total, int page, int size) {}
