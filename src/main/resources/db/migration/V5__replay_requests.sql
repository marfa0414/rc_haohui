CREATE TABLE replay_request (
    task_id uuid NOT NULL REFERENCES notification_task(id),
    replay_request_id varchar(128) NOT NULL CHECK (replay_request_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_digest bytea NOT NULL CHECK (octet_length(request_digest)=32),
    expected_run_number integer NOT NULL CHECK (expected_run_number>0),
    result_run_number integer NOT NULL CHECK (result_run_number=expected_run_number+1),
    actor_id varchar(128) NOT NULL,
    reason varchar(512) NOT NULL CHECK (length(btrim(reason))>0),
    response_snapshot jsonb NOT NULL CHECK (jsonb_typeof(response_snapshot)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(task_id,replay_request_id)
);

CREATE FUNCTION reject_replay_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Replay operation records are immutable';
END $$;
CREATE TRIGGER replay_request_immutable BEFORE UPDATE OR DELETE ON replay_request
    FOR EACH ROW EXECUTE FUNCTION reject_replay_mutation();
