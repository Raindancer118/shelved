package de.raindancer118.shelved.series;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Public knowledge about a series (all volumes), shared by all users. */
@Entity
@Table(name = "series_catalog")
public class SeriesCatalogEntry {

    public enum Status { PENDING, DONE, FAILED }

    @Id
    private String seriesKey;
    private String seriesName;
    private String author;
    private String volumesJson = "[]";
    private String status;
    private String error;
    private Instant fetchedAt;
    private String source;
    private String seriesRef;

    protected SeriesCatalogEntry() {
    }

    public SeriesCatalogEntry(String seriesKey, String seriesName, String author) {
        this.seriesKey = seriesKey;
        this.seriesName = seriesName;
        this.author = author;
        this.status = Status.PENDING.name();
        this.fetchedAt = Instant.now();
    }

    public String getSeriesKey() { return seriesKey; }
    public String getSeriesName() { return seriesName; }
    public String getAuthor() { return author; }
    public String getVolumesJson() { return volumesJson; }
    public Status getStatus() { return Status.valueOf(status); }
    public String getError() { return error; }
    public Instant getFetchedAt() { return fetchedAt; }

    public void pending() {
        status = Status.PENDING.name();
        error = null;
        fetchedAt = Instant.now();
    }

    public String getSource() { return source; }
    public String getSeriesRef() { return seriesRef; }
    public void setSeriesRef(String seriesRef) { this.seriesRef = seriesRef; }

    public void done(String volumesJson, String note, String source) {
        this.source = source;
        this.volumesJson = volumesJson;
        this.status = Status.DONE.name();
        this.error = note;
        this.fetchedAt = Instant.now();
    }

    public void failed(String error) {
        this.status = Status.FAILED.name();
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        this.fetchedAt = Instant.now();
    }
}
