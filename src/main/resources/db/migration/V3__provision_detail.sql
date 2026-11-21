-- Somewhere for the fields six columns could not hold.
--
-- Category 15 files a country list, an excluded country list, a form of payment and two dates.
-- The named columns cover two of those, and widening the table for every category to suit one of
-- them costs more than it is worth.
--
-- So one text column takes the overflow, written as key equals value pairs. It is a blob and it
-- is meant to be the exception: nothing that a report filters on goes in it, and every provision
-- kind that uses it has to survive the round trip test.
--
-- Empty by default, which is the right answer for every row already stored: those are categories
-- 5, 6, 7, 16 and 31, and none of them has ever needed it.

ALTER TABLE provisions ADD COLUMN detail VARCHAR(512) DEFAULT '' NOT NULL;
