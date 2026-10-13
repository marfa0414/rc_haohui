#!/bin/sh
set -eu

# Only the isolated Compose database initializer receives these bootstrap credentials.
# psql reads password values from its environment, never from shell-expanded SQL or command arguments.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1 <<'SQL'
\getenv migrator_password DATABASE_MIGRATOR_PASSWORD
\getenv application_password DATABASE_APP_PASSWORD
CREATE ROLE notification_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'migrator_password';
CREATE ROLE notification_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'application_password';
REVOKE ALL ON DATABASE notifications FROM PUBLIC;
GRANT CONNECT,CREATE ON DATABASE notifications TO notification_migrator;
GRANT CONNECT ON DATABASE notifications TO notification_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA notifications AUTHORIZATION notification_migrator;
REVOKE ALL ON SCHEMA notifications FROM PUBLIC;
GRANT USAGE ON SCHEMA notifications TO notification_app;
ALTER ROLE notification_migrator IN DATABASE notifications SET search_path=notifications;
ALTER ROLE notification_app IN DATABASE notifications SET search_path=notifications;
SQL
