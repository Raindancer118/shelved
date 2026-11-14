package de.raindancer118.shelved.web;

import de.raindancer118.shelved.book.NotFoundException;
import de.raindancer118.shelved.shelf.Shelf;
import de.raindancer118.shelved.shelf.ShelfRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/shelves")
public class ShelfController {

    public record ShelfInput(@NotBlank @Size(max = 100) String name,
                             @NotEmpty @Size(max = 20) List<@Min(50) @Max(5000) Integer> boardWidthsMm) {
    }

    public record ShelfView(UUID id, String name, int number, List<Integer> boardWidthsMm) {
    }

    private final ShelfRepository shelves;

    public ShelfController(ShelfRepository shelves) {
        this.shelves = shelves;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ShelfView> list(Authentication auth) {
        var list = shelves.findByOwnerIdOrderByPosition(Owner.of(auth));
        return java.util.stream.IntStream.range(0, list.size())
                .mapToObj(i -> view(list.get(i), i + 1)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ShelfView create(Authentication auth, @Valid @RequestBody ShelfInput in) {
        String owner = Owner.of(auth);
        int next = shelves.findByOwnerIdOrderByPosition(owner).size() + 1;
        Shelf s = shelves.save(new Shelf(owner, in.name().trim(), next, in.boardWidthsMm()));
        return view(s, next);
    }

    @PutMapping("/{id}")
    @Transactional
    public ShelfView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ShelfInput in) {
        String owner = Owner.of(auth);
        Shelf s = shelves.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Regal"));
        s.setName(in.name().trim());
        s.setBoardWidthsMm(in.boardWidthsMm());
        return view(s, numberOf(owner, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(Authentication auth, @PathVariable UUID id) {
        String owner = Owner.of(auth);
        Shelf s = shelves.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Regal"));
        shelves.delete(s);
        shelves.flush();
        var rest = shelves.findByOwnerIdOrderByPosition(owner);
        for (int i = 0; i < rest.size(); i++) {
            rest.get(i).setPosition(i + 1);
        }
    }

    private int numberOf(String owner, UUID id) {
        var list = shelves.findByOwnerIdOrderByPosition(owner);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static ShelfView view(Shelf s, int number) {
        return new ShelfView(s.getId(), s.getName(), number, List.copyOf(s.getBoardWidthsMm()));
    }
}
