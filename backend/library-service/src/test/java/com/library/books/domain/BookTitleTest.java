package com.library.books.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BookTitleTest {

    @Test
    void normalisesWhitespaceAndMatchesCaseInsensitively() {
        BookTitle title = BookTitle.of("  The   Great Gatsby ");
        assertThat(title.value()).isEqualTo("The Great Gatsby");
        assertThat(title.key()).isEqualTo(BookTitle.of("the great gatsby").key());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\n"})
    void rejectsBlank(String raw) {
        assertThatThrownBy(() -> BookTitle.of(raw)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsOverlongTitles() {
        assertThatThrownBy(() -> BookTitle.of("a".repeat(BookTitle.MAX_LENGTH + 1))).isInstanceOf(ValidationException.class);
    }

    @Test
    void keepsPunctuationAndUnicode() {
        assertThat(BookTitle.of("Cien años de soledad: 50th ed.").value()).isEqualTo("Cien años de soledad: 50th ed.");
    }
}
