ALTER TABLE audit_event ALTER COLUMN object_id TYPE varchar(256);

CREATE FUNCTION protect_published_configuration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'published' THEN
        RAISE EXCEPTION 'published configuration is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER template_published_immutable BEFORE UPDATE OR DELETE ON notification_template_version
    FOR EACH ROW EXECUTE FUNCTION protect_published_configuration();
CREATE TRIGGER auth_published_immutable BEFORE UPDATE OR DELETE ON auth_profile
    FOR EACH ROW EXECUTE FUNCTION protect_published_configuration();

CREATE FUNCTION protect_notification_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.tenant_id, NEW.operation, NEW.event_id, NEW.request_hash,
           NEW.template_version_id, NEW.auth_profile_id, NEW.route_revision, NEW.provider_id,
           NEW.endpoint_group, NEW.quota_key, NEW.request_url, NEW.request_method,
           NEW.ordinary_headers, NEW.content_type, NEW.body_bytes, NEW.created_at)
        IS DISTINCT FROM
       ROW(OLD.id, OLD.tenant_id, OLD.operation, OLD.event_id, OLD.request_hash,
           OLD.template_version_id, OLD.auth_profile_id, OLD.route_revision, OLD.provider_id,
           OLD.endpoint_group, OLD.quota_key, OLD.request_url, OLD.request_method,
           OLD.ordinary_headers, OLD.content_type, OLD.body_bytes, OLD.created_at) THEN
        RAISE EXCEPTION 'notification snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER notification_snapshot_immutable BEFORE UPDATE ON notification_task
    FOR EACH ROW EXECUTE FUNCTION protect_notification_snapshot();

CREATE FUNCTION protect_audit_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit events are append only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION protect_audit_event();

CREATE INDEX template_query_idx ON notification_template_version(owner_tenant_id, created_at, id);
CREATE INDEX auth_query_idx ON auth_profile(tenant_id, created_at, id);
