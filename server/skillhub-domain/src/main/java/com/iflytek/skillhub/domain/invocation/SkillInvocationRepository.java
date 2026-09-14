package com.iflytek.skillhub.domain.invocation;

/** Each save commits independently; duplicate acknowledgments may enrich timestamp evidence. */
public interface SkillInvocationRepository {
    enum Outcome { ACCEPTED, DUPLICATE, CONFLICT }
    Outcome save(SkillInvocation invocation);
}
