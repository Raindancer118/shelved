package de.raindancer118.shelved.book;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsbnTest {

    @Test
    void normalizesHyphenatedIsbn13() {
        assertThat(Isbn.toIsbn13("978-3-492-28198-0")).contains("9783492281980");
    }

    @Test
    void convertsIsbn10ToIsbn13() {
        assertThat(Isbn.toIsbn13("3-492-28198-2")).contains("9783492281980");
        assertThat(Isbn.toIsbn13("055213945X")).isEmpty(); // wrong check digit
        assertThat(Isbn.toIsbn13("0552139459")).contains("9780552139458");
    }

    @Test
    void rejectsBadChecksumsAndGarbage() {
        assertThat(Isbn.toIsbn13("9783492281981")).isEmpty();
        assertThat(Isbn.toIsbn13("hello")).isEmpty();
        assertThat(Isbn.toIsbn13(null)).isEmpty();
    }

    @Test
    void acceptsEanFromBarcodeScanner() {
        assertThat(Isbn.toIsbn13(" 9780552166591 ")).contains("9780552166591");
    }
}
