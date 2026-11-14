package de.raindancer118.shelved.inventory;

import de.raindancer118.shelved.book.Book;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Same list for a spreadsheet: semicolons and comma decimals as German Excel expects, BOM for the umlauts. */
public final class InventoryCsv {

    static final String HEADER = "Nr;Titel;Autor;Verlag;Jahr;ISBN;Format;Standort;Exemplare;Neupreis €;Wert €;Preisquelle";

    private InventoryCsv() {
    }

    public static byte[] render(InventoryService.Inventory inv) {
        StringBuilder s = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (InventoryService.Row r : inv.rows()) {
            Book b = r.book();
            s.append(Stream.of(String.valueOf(r.nr()), b.getTitle(), String.join(", ", b.getAuthors()), b.getPublisher(),
                            b.getPublishedYear() == null ? null : b.getPublishedYear().toString(), b.getIsbn13(),
                            b.getFormat() == null ? null : b.getFormat().name().toLowerCase(Locale.ROOT), r.location(),
                            String.valueOf(r.copies()), amount(r.unitCents()), amount(r.valueCents()),
                            r.estimated() ? "geschätzt" : "Neupreis")
                    .map(InventoryCsv::field).collect(Collectors.joining(";"))).append("\r\n");
        }
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String amount(long cents) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setGroupingUsed(false);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(cents / 100.0);
    }

    static String field(String v) {
        if (v == null) {
            return "";
        }
        // a leading = + - @ would be run as a formula by Excel
        String safe = v.matches("^[=+\\-@].*") ? "'" + v : v;
        return safe.contains(";") || safe.contains("\"") || safe.contains("\n")
                ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    static List<String> header() {
        return List.of(HEADER.split(";"));
    }
}
