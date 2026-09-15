package com.iflytek.skillhub.domain.skill;

/** Resolves current platform privileges again when a delayed sharing operation becomes effective. */
public interface SharingActorPrivileges {
    boolean isSuperAdmin(String userId);
}
