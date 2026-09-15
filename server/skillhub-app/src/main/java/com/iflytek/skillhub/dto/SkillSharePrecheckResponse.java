package com.iflytek.skillhub.dto;

import java.util.List;

public record SkillSharePrecheckResponse(boolean valid, List<String> errors, List<String> warnings) {}
