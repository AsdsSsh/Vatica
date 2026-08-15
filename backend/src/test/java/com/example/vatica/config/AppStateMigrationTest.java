package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 迭代 11 I11-1：data/ → 工作区根 + .vatica/ 迁移测试。 */
class AppStateMigrationTest {

    @TempDir
    Path cwd;

    @Test
    void movesInternalFilesToStateDirAndArtifactsToWorkspace() throws Exception {
        Path oldData = cwd.resolve("data");
        Path state = cwd.resolve(".vatica");
        Files.createDirectories(oldData);
        Files.writeString(oldData.resolve("calendar.ics"), "ICS");
        Files.writeString(oldData.resolve("todos.json"), "[]");
        Files.writeString(oldData.resolve("models.json"), "{}");
        Files.writeString(oldData.resolve("周报.docx"), "doc");

        AppStateMigration.run(cwd, oldData, state);

        assertThat(state.resolve("calendar.ics")).exists();
        assertThat(state.resolve("todos.json")).exists();
        assertThat(state.resolve("models.json")).exists();
        assertThat(cwd.resolve("周报.docx")).exists();
        assertThat(oldData).doesNotExist();
    }

    @Test
    void migrationIsIdempotentWhenDataMissing() throws Exception {
        Path missing = cwd.resolve("data");
        Path state = cwd.resolve(".vatica");

        AppStateMigration.run(cwd, missing, state);
        AppStateMigration.run(cwd, missing, state);

        assertThat(missing).doesNotExist();
    }

    @Test
    void collisionGetsSuffixedInsteadOfOverwriting() throws Exception {
        Path oldData = cwd.resolve("data");
        Path state = cwd.resolve(".vatica");
        Files.createDirectories(oldData);
        Files.createDirectories(state);
        Files.writeString(oldData.resolve("todos.json"), "new");
        Files.writeString(state.resolve("todos.json"), "old");

        AppStateMigration.run(cwd, oldData, state);

        assertThat(state.resolve("todos.json")).hasContent("old");
        assertThat(state.resolve("todos-迁移1.json")).hasContent("new");
        assertThat(oldData).doesNotExist();
    }
}
