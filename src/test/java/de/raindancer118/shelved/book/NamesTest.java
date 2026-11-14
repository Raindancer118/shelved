package de.raindancer118.shelved.book;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamesTest {

    @Test
    void invertsSimpleNames() {
        assertThat(Names.sortForm("Terry Pratchett")).isEqualTo("Pratchett, Terry");
        assertThat(Names.sortForm("J. R. R. Tolkien")).isEqualTo("Tolkien, J. R. R.");
    }

    @Test
    void keepsAlreadyInvertedNames() {
        assertThat(Names.sortForm("Pratchett, Terry")).isEqualTo("Pratchett, Terry");
    }

    @Test
    void movesLowercaseParticlesBehindTheFirstName() {
        assertThat(Names.sortForm("Johann Wolfgang von Goethe")).isEqualTo("Goethe, Johann Wolfgang von");
        assertThat(Names.sortForm("Ludwig van Beethoven")).isEqualTo("Beethoven, Ludwig van");
    }

    @Test
    void keepsCapitalizedPrefixesPartOfTheSurname() {
        assertThat(Names.sortForm("Ursula K. Le Guin")).isEqualTo("Le Guin, Ursula K.");
    }

    @Test
    void singleNamesStayAsTheyAre() {
        assertThat(Names.sortForm("Homer")).isEqualTo("Homer");
    }

    @Test
    void displayFormTurnsInvertedNamesAround() {
        assertThat(Names.displayForm("Pratchett, Terry")).isEqualTo("Terry Pratchett");
        assertThat(Names.displayForm("Terry Pratchett")).isEqualTo("Terry Pratchett");
    }

    @Test
    void sortKeyIgnoresCaseAndUmlauts() {
        assertThat(Names.collationKey("Ärger, Ölaf")).isEqualTo(Names.collationKey("arger, olaf"));
    }
}
