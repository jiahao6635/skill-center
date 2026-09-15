package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.skill.SharingActorPrivileges;
import org.springframework.stereotype.Component;

@Component
public class SharingActorPrivilegesAdapter implements SharingActorPrivileges {
    private final RbacService rbacService;
    public SharingActorPrivilegesAdapter(RbacService rbacService) { this.rbacService = rbacService; }
    public boolean isSuperAdmin(String userId) { return rbacService.getUserRoleCodes(userId).contains("SUPER_ADMIN"); }
}
