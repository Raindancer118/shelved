package de.raindancer118.shelved.pricing;

import de.raindancer118.shelved.book.BookFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceParserTest {

    @Test
    void takesTheGermanEuroPrice() {
        assertThat(PriceParser.germanEuroCents("Broschur : EUR 12.00 (DE), EUR 12.40 (AT), CHF 16.50 (freier Preis)")).contains(1200);
        assertThat(PriceParser.germanEuroCents("kart. : EUR 9.99 (DE), EUR 10.30 (AT), sfr 14.90 (freier Pr.)")).contains(999);
    }

    @Test
    void prefersTheBoundPriceOverTheFreePrice() {
        assertThat(PriceParser.germanEuroCents(": EUR 18.68 (DE), EUR 19.99 (DE) (freier Preis), EUR 18.73 (AT)")).contains(1868);
    }

    @Test
    void acceptsAPlainEuroPriceWithoutCountry() {
        assertThat(PriceParser.germanEuroCents("Pp. : EUR 14,95")).contains(1495);
    }

    @Test
    void noEuroNoPrice() {
        assertThat(PriceParser.germanEuroCents("CHF 16.50")).isEmpty();
        assertThat(PriceParser.germanEuroCents(null)).isEmpty();
    }

    @Test
    void readsTheBindingFromThePriceQualifier() {
        assertThat(PriceParser.format("kart. : EUR 9.99 (DE)")).contains(BookFormat.PAPERBACK);
        assertThat(PriceParser.format("Broschur : EUR 12.00 (DE)")).contains(BookFormat.PAPERBACK);
        assertThat(PriceParser.format("Pp. : EUR 14.95 (DE)")).contains(BookFormat.HARDCOVER);
        assertThat(PriceParser.format("Gb. : EUR 24.00 (DE)")).contains(BookFormat.HARDCOVER);
        assertThat(PriceParser.format(": EUR 18.68 (DE)")).isEmpty();
    }
}
