package de.raindancer118.shelved.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.Duration;

/** A cover file as HTTP answer. Our own covers carry a version in the URL and never change; catalogue covers may. */
public final class CoverResponse {

    private CoverResponse() {
    }

    public static ResponseEntity<FileSystemResource> of(Path path, boolean versioned) {
        MediaType type = path.toString().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        CacheControl cache = versioned ? CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable()
                : CacheControl.maxAge(Duration.ofDays(30)).cachePrivate();
        return ResponseEntity.ok().contentType(type).cacheControl(cache).body(new FileSystemResource(path));
    }
}
