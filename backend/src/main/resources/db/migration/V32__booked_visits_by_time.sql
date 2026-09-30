-- =====================================================================
-- V32: Find visits still open after they happened, without reading them all.
--
-- The hourly job that marks never-closed visits as missed filtered on the
-- slot's end time, which no index on appointments can serve, so it read
-- every appointment and every slot (found by QueryPlanTest at 60,000
-- visits). Only a handful of rows are ever BOOKED and in the past: the job
-- itself keeps it that way. A partial index on exactly those, by time,
-- lets the job read just them; the job adds the matching condition on
-- scheduled_at (a visit that ended before the cutoff also started before it).
-- =====================================================================

CREATE INDEX idx_appointments_booked_time ON appointments (scheduled_at) WHERE status = 'BOOKED';
