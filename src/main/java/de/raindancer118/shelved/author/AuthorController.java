package de.raindancer118.shelved.author;

import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.web.CoverResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AuthorController {

    private final AuthorService authors;
    private final CoverStore covers;

    public AuthorController(AuthorService authors, CoverStore covers) {
        this.authors = authors;
        this.covers = covers;
    }

    @GetMapping("/authors")
    public List<AuthorService.Author> search(@RequestParam String q) {
        return q.length() > 200 ? List.of() : authors.search(q);
    }

    @GetMapping("/authors/profile")
    public AuthorService.Profile profile(@RequestParam String name) {
        if (name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("Kein Autorenname");
        }
        return authors.profile(name);
    }

    /** Portraits are not cut like covers: a white background is a photo, not a mockup. */
    @GetMapping("/covers/author/{olid}")
    public ResponseEntity<FileSystemResource> photo(@PathVariable String olid) {
        return CoverResponse.of(covers.authorPhoto(olid).orElseThrow(() -> new NotFoundException("Foto")), false);
    }
}
