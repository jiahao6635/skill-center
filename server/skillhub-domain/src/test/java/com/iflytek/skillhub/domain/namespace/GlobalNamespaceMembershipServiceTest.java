package com.iflytek.skillhub.domain.namespace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GlobalNamespaceMembershipServiceTest {

    @Mock
    private NamespaceRepository namespaceRepository;

    @Mock
    private NamespaceMemberRepository namespaceMemberRepository;

    private GlobalNamespaceMembershipService service;

    @BeforeEach
    void setUp() {
        service = new GlobalNamespaceMembershipService(namespaceRepository, namespaceMemberRepository);
    }

    @Test
    void ensureMember_createsGlobalAndPrivateMembershipWhenMissing() throws Exception {
        Namespace global = new Namespace("global", "Global", "system");
        setNamespaceId(global, 1L);
        Namespace privateNs = new Namespace("private", "Private", "system");
        setNamespaceId(privateNs, 2L);

        when(namespaceRepository.findBySlug("global")).thenReturn(Optional.of(global));
        when(namespaceRepository.findBySlug("private")).thenReturn(Optional.of(privateNs));
        when(namespaceMemberRepository.findByNamespaceIdAndUserId(1L, "usr_1")).thenReturn(Optional.empty());
        when(namespaceMemberRepository.findByNamespaceIdAndUserId(2L, "usr_1")).thenReturn(Optional.empty());

        service.ensureMember("usr_1");

        ArgumentCaptor<NamespaceMember> memberCaptor = ArgumentCaptor.forClass(NamespaceMember.class);
        verify(namespaceMemberRepository, atLeast(2)).save(memberCaptor.capture());
        List<NamespaceMember> savedMembers = memberCaptor.getAllValues();
        assertThat(savedMembers).hasSize(2);
        assertThat(savedMembers).extracting(NamespaceMember::getNamespaceId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(savedMembers).extracting(NamespaceMember::getUserId).containsOnly("usr_1");
        assertThat(savedMembers).extracting(NamespaceMember::getRole).containsOnly(NamespaceRole.MEMBER);
    }

    @Test
    void ensureMember_keepsExistingGlobalAndPrivateMembership() throws Exception {
        Namespace global = new Namespace("global", "Global", "system");
        setNamespaceId(global, 1L);
        Namespace privateNs = new Namespace("private", "Private", "system");
        setNamespaceId(privateNs, 2L);
        NamespaceMember existingGlobal = new NamespaceMember(1L, "usr_1", NamespaceRole.ADMIN);
        NamespaceMember existingPrivate = new NamespaceMember(2L, "usr_1", NamespaceRole.MEMBER);

        when(namespaceRepository.findBySlug("global")).thenReturn(Optional.of(global));
        when(namespaceRepository.findBySlug("private")).thenReturn(Optional.of(privateNs));
        when(namespaceMemberRepository.findByNamespaceIdAndUserId(1L, "usr_1")).thenReturn(Optional.of(existingGlobal));
        when(namespaceMemberRepository.findByNamespaceIdAndUserId(2L, "usr_1")).thenReturn(Optional.of(existingPrivate));

        service.ensureMember("usr_1");

        verify(namespaceMemberRepository, never()).save(any());
    }

    private void setNamespaceId(Namespace namespace, Long id) throws Exception {
        Field field = Namespace.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(namespace, id);
    }
}
