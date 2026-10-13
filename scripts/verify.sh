#!/usr/bin/env bash
# Complete automated gate. Real PostgreSQL is required; missing infrastructure fails.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
: "${TEST_DATABASE_URL:?Set a dedicated real PostgreSQL JDBC URL}"
: "${TEST_DATABASE_USER:?Set the test PostgreSQL user with CREATE SCHEMA permission}"
: "${TEST_DATABASE_PASSWORD:?Set the test PostgreSQL password}"
export CP04_TEST_SECRET='cp04-test-only-secret'
export CP04_TEST_SECRET_VERSION='fixture-v1'
export CP06_CLIENT_SECRET='cp06 fake:+密钥'
export CP06_CLIENT_SECRET_VERSION='fixture-oauth-v1'
export CP06_ROTATED_SECRET='cp06 rotated secret'
export CP06_ROTATED_SECRET_VERSION='fixture-oauth-v2'
./mvnw --batch-mode --no-transfer-progress clean verify "$@"
python3 scripts/verify_test_reports.py target/surefire-reports
