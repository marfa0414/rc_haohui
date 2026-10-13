CREATE INDEX task_active_backlog_idx ON notification_task(id)
    WHERE status IN ('pending','processing');
