-- =====================================================================
-- V41: Retention of the location a medicine question was asked from.
--
-- medicine_requests.latitude / longitude were NOT NULL and kept for ever.
-- Nothing reads them after the question is answered (stores see a
-- distance, held in request_recipients), so a retention job now clears or
-- coarsens them once the question has been closed for a configured time.
--
--  * the columns become nullable: NULL means "cleared";
--  * both or neither, like every other coordinate pair in the schema;
--  * location_minimised_at marks a row the job has handled, so it is
--    visited once and "coarsen" is not re-applied to its own output.
-- =====================================================================

ALTER TABLE medicine_requests
    ALTER COLUMN latitude  DROP NOT NULL,
    ALTER COLUMN longitude DROP NOT NULL,
    ADD COLUMN location_minimised_at TIMESTAMPTZ,
    ADD CONSTRAINT requests_location_pair CHECK ((latitude IS NULL) = (longitude IS NULL));

-- The job's scan: closed questions whose location has not been handled yet.
CREATE INDEX idx_requests_location_retention ON medicine_requests (closed_at)
    WHERE location_minimised_at IS NULL AND closed_at IS NOT NULL;
