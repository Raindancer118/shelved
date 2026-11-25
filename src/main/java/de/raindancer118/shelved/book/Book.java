package de.raindancer118.shelved.book;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dynamic updates: background jobs (cover download) must only write the columns they changed, not clobber a parallel move. */
@Entity
@DynamicUpdate
@Table(name = "book")
public class Book {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false)
    private String ownerId;
    private String isbn13;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String titleSort;
    private String subtitle;
    @Convert(converter = StringListConverter.class)
    private List<String> authors = new ArrayList<>();
    private String authorSort;
    private String publisher;
    private Integer publishedYear;
    private Integer pageCount;
    private Integer heightMm;
    private Integer thicknessMm;
    /** Measured on a shelf photo; a hand-entered thicknessMm wins. */
    private Integer measuredThicknessMm;
    @Enumerated(EnumType.STRING)
    private BookFormat format;
    private String language;
    private String description;
    private Integer listPriceCents;
    private String coverFile;
    private String metadataSource;
    private UUID shelfId;
    private Integer boardNumber;
    private Integer boardPosition;
    private int copies = 1;
    private Instant createdAt;
    private Instant updatedAt;

    protected Book() {
    }

    public Book(String ownerId, String title) {
        this.ownerId = ownerId;
        setTitle(title);
    }

    @PrePersist
    void onCreate() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String firstAuthorSurname() {
        if (authorSort == null) {
            return "";
        }
        int comma = authorSort.indexOf(',');
        return comma > 0 ? authorSort.substring(0, comma) : authorSort;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getIsbn13() { return isbn13; }
    public void setIsbn13(String isbn13) { this.isbn13 = isbn13; }
    public String getTitle() { return title; }
    public void setTitle(String raw) {
        this.title = Titles.display(raw);
        this.titleSort = Titles.sortForm(raw);
    }
    public String getTitleSort() { return titleSort; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) {
        this.authors = authors == null ? new ArrayList<>() : new ArrayList<>(authors.stream().map(Names::displayForm).toList());
        this.authorSort = this.authors.isEmpty() ? null : Names.sortForm(this.authors.getFirst());
    }
    public String getAuthorSort() { return authorSort; }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public Integer getPublishedYear() { return publishedYear; }
    public void setPublishedYear(Integer publishedYear) { this.publishedYear = publishedYear; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Integer getHeightMm() { return heightMm; }
    public void setHeightMm(Integer heightMm) { this.heightMm = heightMm; }
    public Integer getThicknessMm() { return thicknessMm; }
    public void setThicknessMm(Integer thicknessMm) { this.thicknessMm = thicknessMm; }
    public Integer getMeasuredThicknessMm() { return measuredThicknessMm; }
    public void setMeasuredThicknessMm(Integer measuredThicknessMm) { this.measuredThicknessMm = measuredThicknessMm; }
    public BookFormat getFormat() { return format; }
    public void setFormat(BookFormat format) { this.format = format; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getListPriceCents() { return listPriceCents; }
    public void setListPriceCents(Integer listPriceCents) { this.listPriceCents = listPriceCents; }
    public String getCoverFile() { return coverFile; }
    public void setCoverFile(String coverFile) { this.coverFile = coverFile; }
    public String getMetadataSource() { return metadataSource; }
    public void setMetadataSource(String metadataSource) { this.metadataSource = metadataSource; }
    public UUID getShelfId() { return shelfId; }
    public Integer getBoardNumber() { return boardNumber; }
    public Integer getBoardPosition() { return boardPosition; }
    public void placeAt(UUID shelfId, Integer boardNumber, Integer boardPosition) {
        this.shelfId = shelfId;
        this.boardNumber = boardNumber;
        this.boardPosition = boardPosition;
    }
    public int getCopies() { return copies; }
    public void setCopies(int copies) {
        if (copies < 1 || copies > 99) {
            throw new IllegalArgumentException("Anzahl Exemplare muss zwischen 1 und 99 liegen");
        }
        this.copies = copies;
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
