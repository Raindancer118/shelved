package de.raindancer118.shelved.wishlist;

import de.raindancer118.shelved.book.Names;
import de.raindancer118.shelved.book.StringListConverter;
import de.raindancer118.shelved.book.Titles;
import de.raindancer118.shelved.series.SeriesNames;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "wish")
public class Wish {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false)
    private String ownerId;
    private String isbn13;
    @Column(nullable = false)
    private String title;
    private String subtitle;
    @Convert(converter = StringListConverter.class)
    private List<String> authors = new ArrayList<>();
    private String publisher;
    private Integer publishedYear;
    private String seriesName;
    private Integer seriesVolume;
    private Integer listPriceCents;
    private String coverFile;
    private boolean keepWhenOwned;
    private Instant createdAt;

    protected Wish() {
    }

    public Wish(String ownerId, String title) {
        this.ownerId = ownerId;
        this.title = Titles.display(title);
    }

    @PrePersist
    void onCreate() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getIsbn13() { return isbn13; }
    public void setIsbn13(String isbn13) { this.isbn13 = isbn13; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) {
        this.authors = authors == null ? new ArrayList<>()
                : new ArrayList<>(authors.stream().filter(a -> a != null && !a.isBlank()).map(a -> Names.displayForm(a.trim())).toList());
    }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public Integer getPublishedYear() { return publishedYear; }
    public void setPublishedYear(Integer publishedYear) { this.publishedYear = publishedYear; }
    public String getSeriesName() { return seriesName; }
    public Integer getSeriesVolume() { return seriesVolume; }
    public void setSeries(String name, Integer volume) {
        this.seriesName = name == null || name.isBlank() ? null : SeriesNames.normalize(name);
        this.seriesVolume = this.seriesName != null && volume != null && volume > 0 ? volume : null;
    }
    public Integer getListPriceCents() { return listPriceCents; }
    public void setListPriceCents(Integer listPriceCents) { this.listPriceCents = listPriceCents; }
    public String getCoverFile() { return coverFile; }
    public void setCoverFile(String coverFile) { this.coverFile = coverFile; }
    public boolean isKeepWhenOwned() { return keepWhenOwned; }
    public void keepWhenOwned() { this.keepWhenOwned = true; }
    public Instant getCreatedAt() { return createdAt; }
}
