-- CP01 foundation only. Task, attempt and business APIs belong to later checkpoints.
CREATE TABLE notification_template_version (
    id uuid PRIMARY KEY,
    template_key varchar(128) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    status varchar(16) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    owner_tenant_id varchar(64),
    shared boolean NOT NULL DEFAULT false,
    provider_id varchar(128) NOT NULL,
    endpoint_group varchar(128) NOT NULL,
    config jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_by varchar(128),
    published_at timestamptz,
    UNIQUE (template_key, version),
    CHECK (shared OR owner_tenant_id IS NOT NULL),
    CHECK ((status = 'draft' AND published_at IS NULL AND published_by IS NULL)
        OR (status = 'published' AND published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE TABLE auth_profile (
    id uuid PRIMARY KEY,
    tenant_id varchar(64) NOT NULL CHECK (tenant_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
    profile_key varchar(128) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    status varchar(16) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    auth_type varchar(48) NOT NULL CHECK (auth_type IN ('api_key', 'oauth2_client_credentials')),
    parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
    secret_ref varchar(256) NOT NULL CHECK (secret_ref LIKE 'env:%' AND length(secret_ref) > 4),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_by varchar(128),
    published_at timestamptz,
    UNIQUE (tenant_id, profile_key, version),
    UNIQUE (id, tenant_id),
    CHECK ((status = 'draft' AND published_at IS NULL AND published_by IS NULL)
        OR (status = 'published' AND published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE TABLE notification_route (
    tenant_id varchar(64) NOT NULL CHECK (tenant_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
    operation varchar(128) NOT NULL CHECK (operation ~ '^[A-Za-z0-9._:-]{1,128}$'),
    template_version_id uuid NOT NULL REFERENCES notification_template_version(id),
    auth_profile_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_by varchar(128) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, operation),
    FOREIGN KEY (auth_profile_id, tenant_id) REFERENCES auth_profile(id, tenant_id)
);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    actor_id varchar(128) NOT NULL,
    tenant_id varchar(64),
    object_type varchar(64) NOT NULL,
    object_id varchar(128) NOT NULL,
    action varchar(64) NOT NULL,
    reason varchar(1024),
    before_summary jsonb,
    after_summary jsonb,
    request_key varchar(128),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (before_summary IS NULL OR jsonb_typeof(before_summary) = 'object'),
    CHECK (after_summary IS NULL OR jsonb_typeof(after_summary) = 'object')
);
