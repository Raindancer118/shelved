package de.raindancer118.shelved;

import de.raindancer118.shelved.book.CoverStore;
import de.raindancer118.shelved.metadata.MetadataService;
import de.raindancer118.shelved.metadata.WikidataClient;
import de.raindancer118.shelved.book.BookRepository;
import de.raindancer118.shelved.scan.ScanRepository;
import de.raindancer118.shelved.series.SeriesCatalogRepository;
import de.raindancer118.shelved.shelf.ShelfRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "shelved.data-dir=target/test-data")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({TestcontainersConfig.class, IntegrationTestBase.AiConfig.class})
public abstract class IntegrationTestBase {

    @TestConfiguration
    static class AiConfig {
        @Bean
        @Primary
        FakeLibraryAi fakeLibraryAi() {
            return new FakeLibraryAi();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected FakeLibraryAi ai;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private ShelfRepository shelfRepository;
    @Autowired
    private ScanRepository scanRepository;
    @Autowired
    private SeriesCatalogRepository catalogRepository;
    @Autowired
    private de.raindancer118.shelved.wishlist.WishRepository wishRepository;
    @MockitoBean
    protected MetadataService metadata;
    @MockitoBean
    protected CoverStore covers;
    @MockitoBean
    protected WikidataClient wikidata;
    @MockitoBean
    protected de.raindancer118.shelved.metadata.DnbClient dnb;
    @MockitoBean
    protected de.raindancer118.shelved.metadata.K10plusClient k10plus;
    @MockitoBean
    protected de.raindancer118.shelved.metadata.LocClient loc;
    @MockitoBean
    protected de.raindancer118.shelved.metadata.OpenLibraryClient openLibrary;
    @MockitoBean
    protected de.raindancer118.shelved.metadata.HardcoverClient hardcover;


    @BeforeEach
    void cleanSlate() {
        wishRepository.deleteAll();
        bookRepository.deleteAll();
        scanRepository.deleteAll();
        shelfRepository.deleteAll();
        catalogRepository.deleteAll();
        ai.tileCounts.clear();
        ai.seriesQuestions.clear();
        ai.catalogQuestions.clear();
    }
}
