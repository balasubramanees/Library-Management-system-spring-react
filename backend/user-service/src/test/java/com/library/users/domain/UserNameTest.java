package com.library.users.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserNameTest {

    @Test
    void trimsAndCollapsesWhitespaceAndKeepsDisplaySpelling() {
        UserName name = UserName.of("  Mary   Jane ");
        assertThat(name.value()).isEqualTo("Mary Jane");
        assertThat(name.key()).isEqualTo("mary jane");
    }

    @Test
    void identityIsCaseInsensitive() {
        assertThat(UserName.of("ALICE").key()).isEqualTo(UserName.of("alice").key());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsBlankNames(String raw) {
        assertThatThrownBy(() -> UserName.of(raw)).isInstanceOf(ValidationException.class).hasMessageContaining("empty");
    }

    @Test
    void rejectsOverlongNames() {
        assertThatThrownBy(() -> UserName.of("x".repeat(UserName.MAX_LENGTH + 1))).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> UserName.of("bob\u0007")).isInstanceOf(ValidationException.class);
    }

    @Test
    void adminIsRecognisedRegardlessOfCase() {
        assertThat(User.register(UserName.of("Admin")).isAdmin()).isTrue();
        assertThat(User.register(UserName.of("administrator")).isAdmin()).isFalse();
    }
}
