-- Two readings the reliability report cannot get anywhere else.
--
-- A run result already said what the outcome was, which covers everything that shows up as a
-- status code. It did not say whether the payload was one the archive already held, and it did
-- not say how many offers inside a payload failed to parse. Both of those are supplier failures
-- that answer with a 200, so a run made entirely of them looks perfect from the outcome column
-- alone.
--
-- Added with a default so the rows already in the table read as fresh and clean, which is the
-- right answer for them: nothing recorded a repeat or a payload problem before this, so claiming
-- otherwise would invent history.

ALTER TABLE run_results ADD COLUMN fresh BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE run_results ADD COLUMN problems INTEGER DEFAULT 0 NOT NULL;
