#!/usr/bin/env bash
# Real PostgreSQL and independent JVMs are required. This is a targeted diagnostic run.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
: "${TEST_DATABASE_URL:?Set a dedicated real PostgreSQL JDBC URL}"
: "${TEST_DATABASE_USER:?Set the test PostgreSQL user with CREATE SCHEMA permission}"
: "${TEST_DATABASE_PASSWORD:?Set the test PostgreSQL password}"
# Fixed fixtures only; child processes override these when exercising credential rotation.
export CP04_TEST_SECRET='cp04-test-only-secret'
export CP04_TEST_SECRET_VERSION='fixture-v1'
export CP06_CLIENT_SECRET='cp06 fake:+密钥'
export CP06_CLIENT_SECRET_VERSION='fixture-oauth-v1'
export CP06_ROTATED_SECRET='cp06 rotated secret'
export CP06_ROTATED_SECRET_VERSION='fixture-oauth-v2'
exec ./mvnw --batch-mode --no-transfer-progress '-Dtest=Process*Test' test "$@"
