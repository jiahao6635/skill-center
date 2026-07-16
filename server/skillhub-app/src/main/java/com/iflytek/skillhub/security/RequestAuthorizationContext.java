package com.iflytek.skillhub.security;

import com.iflytek.skillhub.auth.token.ApiTokenGrantContext;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import java.util.Map;
import java.util.Set;

public record RequestAuthorizationContext(String userId, Set<String> platformRoles,
                                          Map<Long, NamespaceRole> namespaceRoles,
                                          String authSource, ApiTokenGrantContext tokenGrant) {}
