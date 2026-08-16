package com.example.vatica.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.vatica.auth.RequestIdentity;

class LocalIsolatedWorkspaceStoreTest {
    @TempDir Path tempDir;

    @Test
    void sameRelativePathUsesDifferentTenantRoots() throws Exception {
        LocalIsolatedWorkspaceStore store = new LocalIsolatedWorkspaceStore(
                new WorkspaceProperties(tempDir.toString()));
        RequestIdentity one = new RequestIdentity(1L, 10L, "MEMBER", "one");
        RequestIdentity two = new RequestIdentity(2L, 10L, "MEMBER", "two");
        store.write(one, "docs/report.txt", new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)));
        store.write(two, "docs/report.txt", new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)));

        assertThat(Files.readString(store.read(one, "docs/report.txt"))).isEqualTo("one");
        assertThat(Files.readString(store.read(two, "docs/report.txt"))).isEqualTo("two");
        assertThat(store.root(one)).isNotEqualTo(store.root(two));
    }

    @Test
    void traversalAndAbsolutePathAreRejected() {
        LocalIsolatedWorkspaceStore store = new LocalIsolatedWorkspaceStore(
                new WorkspaceProperties(tempDir.toString()));
        RequestIdentity identity = new RequestIdentity(1L, 10L, "MEMBER", "one");

        assertThatThrownBy(() -> store.read(identity, "../other/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("边界");
        assertThatThrownBy(() -> store.read(identity, tempDir.resolve("secret.txt").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("相对路径");
    }
}
