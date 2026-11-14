package de.raindancer118.shelved;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V8 moves existing data from login subjects to libraries: nothing may get lost or end up with someone else. */
class MigrationV8Test {

    @Test
    void everyOwnerBecomesAnAccountWithItsOwnLibraryAndKeepsItsData() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17-alpine")) {
            pg.start();
            var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway.configure().dataSource(ds).target("7").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID shelf = UUID.randomUUID();
            jdbc.update("insert into shelf (id, owner_id, name, position, created_at) values (?, 'tom-sub', 'Flur', 0, now())", shelf);
            for (String[] b : new String[][]{{"tom-sub", "Gevatter Tod"}, {"tom-sub", "Mort"}, {"anna-sub", "Momo"}}) {
                jdbc.update("""
                        insert into book (id, owner_id, title, title_sort, series_checked, created_at, updated_at, shelf_id)
                        values (?, ?, ?, ?, true, now(), now(), ?)""", UUID.randomUUID(), b[0], b[1], b[1], b[0].equals("tom-sub") ? shelf : null);
            }
            jdbc.update("insert into scan (id, owner_id, mode, status, created_at) values (?, 'anna-sub', 'SHELF', 'ACCEPTED', now())", UUID.randomUUID());

            Flyway.configure().dataSource(ds).load().migrate();

            assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from library", Integer.class)).isEqualTo(2);
            String tomLibrary = jdbc.queryForObject("""
                    select m.library_id::text from membership m join account a on a.id = m.account_id
                    where a.oidc_subject = 'tom-sub' and m.role = 'OWNER'""", String.class);
            String annaLibrary = jdbc.queryForObject("""
                    select m.library_id::text from membership m join account a on a.id = m.account_id
                    where a.oidc_subject = 'anna-sub'""", String.class);
            assertThat(jdbc.queryForList("select title from book where owner_id = ? order by title", String.class, tomLibrary))
                    .containsExactly("Gevatter Tod", "Mort");
            assertThat(jdbc.queryForList("select title from book where owner_id = ?", String.class, annaLibrary)).containsExactly("Momo");
            assertThat(jdbc.queryForObject("select owner_id from shelf", String.class)).isEqualTo(tomLibrary);
            assertThat(jdbc.queryForObject("select owner_id from scan", String.class)).isEqualTo(annaLibrary);
            // books still stand on their shelf
            assertThat(jdbc.queryForObject("select count(*) from book where shelf_id = ?", Integer.class, shelf)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from book where owner_id in ('tom-sub', 'anna-sub')", Integer.class)).isZero();
        }
    }
}
