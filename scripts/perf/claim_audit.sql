-- CP10 burst/recovery observation only. Separate schema, no task/attempt value changes.
-- Each successful T1 claim adds one row in the SAME transaction, so rollbacks leave no observation.
CREATE SCHEMA IF NOT EXISTS perf_observation;
CREATE TABLE IF NOT EXISTS perf_observation.claim (
    task_id uuid NOT NULL, run_number integer NOT NULL, lease_token uuid NOT NULL,
    due_at timestamptz NOT NULL, claimed_at timestamptz NOT NULL,
    PRIMARY KEY(task_id,run_number,lease_token)
);
CREATE OR REPLACE FUNCTION perf_observation.observe_claim() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,perf_observation AS $$
BEGIN
    IF OLD.status='pending' AND NEW.status='processing' AND OLD.lease_token IS DISTINCT FROM NEW.lease_token THEN
        INSERT INTO perf_observation.claim VALUES(NEW.id,NEW.run_number,NEW.lease_token,OLD.next_attempt_at,clock_timestamp());
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON SCHEMA perf_observation FROM PUBLIC;
REVOKE ALL ON FUNCTION perf_observation.observe_claim() FROM PUBLIC;
DROP TRIGGER IF EXISTS perf_claim_observation ON notification_task;
CREATE TRIGGER perf_claim_observation AFTER UPDATE ON notification_task
FOR EACH ROW EXECUTE FUNCTION perf_observation.observe_claim();
