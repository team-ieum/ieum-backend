#!/usr/bin/env python3
"""Build a private DEV_COM_ or MAIN_COM_ deployment candidate from Actions secrets.

This module never reads a server .env file, expands shell expressions, or prints
configuration values. The controller owns the final per-color runtime snapshots.
"""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import sys


MAX_ENV_BYTES = 48 * 1024
MAX_JSON_BYTES = 96 * 1024
SECRET_PREFIX = 'DEV_COM_'
ENVIRONMENT_PREFIXES = {'dev': SECRET_PREFIX, 'main': 'MAIN_COM_'}
MAIN_BUILD_KEYS = ('JAVA_VERSION', 'IMAGE_NAME')
SECRETS_VARIABLE = 'IEUM_BACKEND_SECRETS_JSON'
FORBIDDEN_CHARACTERS = '\x00\n\r\v\f\x1c\x1d\x1e\x85\u2028\u2029'

# Existing app requirements are validation rules, not an allowlist for new keys.
REQUIRED_KEYS = (
    'DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
    'REDIS_HOST', 'REDIS_PORT', 'MONGO_URI',
    'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'CORS_ALLOWED_ORIGINS',
    'JWT_SECRET', 'JWT_EXPIRATION', 'JWT_REFRESH_EXPIRATION', 'AES_SECRET_KEY',
    'NOTION_CLIENT_ID', 'NOTION_CLIENT_SECRET', 'NOTION_AUTH_URL',
    'NOTION_TOKEN_URL', 'NOTION_REDIRECT_URI', 'NOTION_FRONTEND_REDIRECT_URI',
    'GITHUB_APP_CLIENT_ID', 'GITHUB_APP_CLIENT_SECRET', 'GITHUB_APP_TOKEN_URL',
    'GITHUB_APP_REDIRECT_URI', 'GITHUB_APP_FRONTEND_REDIRECT_URI',
    'SLACK_CLIENT_ID', 'SLACK_CLIENT_SECRET', 'SLACK_REDIRECT_URI', 'SLACK_FRONTEND_REDIRECT_URI',
    'DISCORD_CLIENT_ID', 'DISCORD_CLIENT_SECRET', 'DISCORD_BOT_TOKEN',
    'DISCORD_REDIRECT_URI', 'DISCORD_FRONTEND_REDIRECT_URI', 'AGENT_INTERNAL_TOKEN',
)
DEFAULTS = {'SPRING_PROFILES_ACTIVE': 'dev'}
OPTIONAL_KEYS = (
    'GOOGLE_REDIRECT_URI', 'GITHUB_APP_WEBHOOK_SECRET',
    'MAIL_HOST', 'MAIL_PORT', 'MAIL_USERNAME', 'MAIL_PASSWORD', 'OAUTH2_REDIRECT_URI',
)
ALIASES = {
    'RDS_DB_URL': 'DB_URL', 'RDS_DB_USERNAME': 'DB_USERNAME', 'RDS_DB_PASSWORD': 'DB_PASSWORD',
    'GH_APP_CLIENT_ID': 'GITHUB_APP_CLIENT_ID', 'GH_APP_CLIENT_SECRET': 'GITHUB_APP_CLIENT_SECRET',
    'GH_APP_TOKEN_URL': 'GITHUB_APP_TOKEN_URL', 'GH_APP_REDIRECT_URI': 'GITHUB_APP_REDIRECT_URI',
    'GH_APP_FRONTEND_REDIRECT_URI': 'GITHUB_APP_FRONTEND_REDIRECT_URI',
    'GH_APP_WEBHOOK_SECRET': 'GITHUB_APP_WEBHOOK_SECRET',
}
# Spring relaxed binding can ignore underscores. Reserve equivalent spellings
# too, and reject higher-priority configuration / JVM launch escape hatches.
RESERVED_KEYS = (
    'AGENT_SERVICE_URL', 'IEUM_AGENT_URL', 'SPRING_JPA_HIBERNATE_DDL_AUTO', 'TZ',
    'IEUM_WORKFLOW_QUEUE_CONSUMER_NAME', 'IEUM_WORKFLOW_QUEUE_PENDING_RECOVERY_NOT_BEFORE',
    'IEUM_WORKFLOW_QUEUE_PENDING_RECOVERY_EXPECTED_ZONE', 'SERVER_SHUTDOWN',
    'SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE', 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE',
    'SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL', 'SPRING_DATASOURCE_HIKARI_CONNECTION_TEST_QUERY',
    'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
    'JAVA_OPTS', 'GITHUB_TOKEN', 'BACKEND_ENV', SECRETS_VARIABLE,
)
RESERVED_PREFIXES = (
    'IEUM_DEPLOYMENT_', 'IEUM_SCHEDULER_', 'SPRING_CONFIG_',
    'SPRING_JPA_', 'SPRING_SQL_INIT_', 'SPRING_FLYWAY_', 'SPRING_LIQUIBASE_',
)


def canonical_key(key):
    return key.replace('_', '').upper()


RESERVED_CANONICAL_KEYS = frozenset(canonical_key(key) for key in RESERVED_KEYS)
RESERVED_CANONICAL_PREFIXES = tuple(canonical_key(prefix) for prefix in RESERVED_PREFIXES)


class BackendEnvironmentError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BackendEnvironmentError('duplicate_secret_name')
        result[key] = value
    return result


def invalid_json_constant(_value):
    raise BackendEnvironmentError('invalid_json')


def build_environment(payload, environment='dev'):
    """Select the prefix from JSON and rebuild a complete literal environment."""
    if environment not in ENVIRONMENT_PREFIXES:
        raise BackendEnvironmentError('invalid_environment')
    prefix = ENVIRONMENT_PREFIXES[environment]
    if not isinstance(payload, str) or not payload:
        raise BackendEnvironmentError('missing_configuration')
    try:
        encoded = payload.encode('utf-8')
    except UnicodeError:
        raise BackendEnvironmentError('invalid_encoding') from None
    if len(encoded) > MAX_JSON_BYTES:
        raise BackendEnvironmentError('secrets_payload_too_large')
    try:
        source = json.loads(payload, object_pairs_hook=unique_object,
                                 parse_constant=invalid_json_constant)
    except (json.JSONDecodeError, RecursionError):
        raise BackendEnvironmentError('invalid_json') from None
    if not isinstance(source, dict):
        raise BackendEnvironmentError('invalid_json')
    values = {}
    seen = set()
    for secret_name, value in source.items():
        normalized_name = secret_name.upper()
        if not normalized_name.startswith(prefix):
            continue
        if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', secret_name) is None:
            raise BackendEnvironmentError('invalid_secret_name')
        suffix = normalized_name[len(prefix):]
        if re.fullmatch(r'[A-Z_][A-Z0-9_]*', suffix) is None:
            raise BackendEnvironmentError('invalid_secret_name')
        if environment == 'main' and canonical_key(suffix) in {canonical_key(k) for k in MAIN_BUILD_KEYS}:
            continue
        key = ALIASES.get(suffix, suffix)
        if environment == 'main' and canonical_key(key) == canonical_key('SPRING_PROFILES_ACTIVE'):
            key = 'SPRING_PROFILES_ACTIVE'
        canonical = canonical_key(key)
        if canonical in RESERVED_CANONICAL_KEYS or canonical.startswith(RESERVED_CANONICAL_PREFIXES):
            raise BackendEnvironmentError('reserved_configuration')
        if canonical in seen:
            raise BackendEnvironmentError('duplicate_configuration_key')
        # Check collisions even when a known optional empty value is omitted.
        seen.add(canonical)
        if not isinstance(value, str):
            raise BackendEnvironmentError('invalid_configuration')
        if any(character in value for character in FORBIDDEN_CHARACTERS):
            raise BackendEnvironmentError('unsupported_control_character')
        values[key] = value
    required = REQUIRED_KEYS
    for key in required:
        if not values.get(key) or values[key].isspace():
            raise BackendEnvironmentError('missing_required_configuration')
    defaults = {'SPRING_PROFILES_ACTIVE': 'main'} if environment == 'main' else DEFAULTS
    for key, default in defaults.items():
        if values.get(key, '') == '':
            values[key] = default
    if environment == 'main' and values['SPRING_PROFILES_ACTIVE'] != 'main':
        raise BackendEnvironmentError('invalid_main_profile')
    for key in OPTIONAL_KEYS:
        if values.get(key) == '':
            values.pop(key)
    try:
        result = ''.join(key + '=' + values[key] + '\n' for key in sorted(values)).encode('utf-8')
    except UnicodeError:
        raise BackendEnvironmentError('invalid_encoding') from None
    if len(result) > MAX_ENV_BYTES:
        raise BackendEnvironmentError('configuration_too_large')
    return result


def write_candidate(destination, payload, environment='dev'):
    """Populate only an empty, owned, private regular file created by mktemp."""
    content = build_environment(payload, environment)
    descriptor = None
    try:
        descriptor = os.open(Path(destination), os.O_WRONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or \
                stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_nlink != 1:
            raise BackendEnvironmentError('unsafe_candidate')
        if metadata.st_size != 0:
            raise BackendEnvironmentError('candidate_not_empty')
        remaining = memoryview(content)
        while remaining:
            count = os.write(descriptor, remaining)
            if count <= 0:
                raise OSError()
            remaining = remaining[count:]
        os.fsync(descriptor)
    except BackendEnvironmentError:
        raise
    except (OSError, ValueError, TypeError):
        raise BackendEnvironmentError('candidate_io_failed') from None
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                raise BackendEnvironmentError('candidate_io_failed') from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--environment', choices=tuple(ENVIRONMENT_PREFIXES), default='dev')
    arguments = parser.parse_args(argv)
    try:
        write_candidate(arguments.candidate, os.environ.get(SECRETS_VARIABLE), arguments.environment)
    except BackendEnvironmentError as failure:
        print('Backend environment rejected: ' + failure.code, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
