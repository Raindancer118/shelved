package de.raindancer118.shelved.book;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Fields a user can type or correct by hand. */
public record BookInput(
        @NotBlank @Size(max = 500) String title,
        @Size(max = 1000) String subtitle,
        @Size(max = 10) List<@Size(max = 200) String> authors,
        @Size(max = 20) String isbn,
        @Size(max = 300) String publisher,
        @Min(1400) @Max(2100) Integer publishedYear,
        @Min(1) @Max(20000) Integer pageCount,
        @Min(1) @Max(300) Integer thicknessMm,
        BookFormat format,
        @Size(max = 300) String seriesName,
        @Min(0) @Max(10000) Integer seriesVolume,
        @Min(0) @Max(10_000_00) Integer listPriceCents,
        @Size(max = 20000) String description) {
}
