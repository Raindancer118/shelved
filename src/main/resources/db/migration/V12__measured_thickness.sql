-- How thick a book really is, measured on a shelf photo (its spine's share of the board). A hand-entered
-- thickness_mm still wins; this one replaces the guess from the page count.
alter table book add column measured_thickness_mm int;
