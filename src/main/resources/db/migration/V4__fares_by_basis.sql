-- An index for the one fare lookup that has no market to narrow on.
--
-- The night run reads a ticket's fare basis and needs the rule behind it. Every other fare query
-- knows the market and takes the market index; this one has only the carrier, so it took that
-- index for its first column and read most of the table to find one row.
--
-- Cheap to add and cheap to keep: fares are written once per filing file and read on every
-- reprice.

CREATE INDEX ix_fares_basis ON fares (carrier, fare_basis);
