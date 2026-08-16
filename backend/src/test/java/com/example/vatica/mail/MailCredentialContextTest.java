package com.example.vatica.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MailCredentialContextTest {

    @AfterEach
    void clear() {
        MailCredentialContext.clear();
    }

    @Test
    void callWithShouldClearEmptySnapshotAndRestoreOuterCredential() {
        MailConnectionSettings outer = settings("outer-secret");
        MailCredentialContext.set(outer);

        String value = MailCredentialContext.callWith(null, () -> {
            assertThat(MailCredentialContext.current()).isNull();
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(MailCredentialContext.current()).isSameAs(outer);
    }

    private static MailConnectionSettings settings(String password) {
        return new MailConnectionSettings("imap.example.com", 993,
                "smtp.example.com", 465, "user@example.com", password);
    }
}
