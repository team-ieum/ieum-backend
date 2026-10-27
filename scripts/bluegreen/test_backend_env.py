"""Prefixed-secret and workflow regressions; no network, Docker or real credentials."""

import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name('backend_env.py')
REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY / '.github/workflows/backend-dev-server.yml'
SECRET = 'synthetic-credential-never-print-this'
PREFIX = 'DEV_'
TRANSPORT = 'IEUM_BACKEND_SECRETS_JSON'
DEFAULT_PAYLOAD = object()
spec = importlib.util.spec_from_file_location('backend_env_under_test', SCRIPT)
backend_env = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backend_env)

# Existing suffix aliases remain usable after adding the deployment namespace.
EXPECTED_ALIASES = {
    'RDS_DB_URL': 'DB_URL', 'RDS_DB_USERNAME': 'DB_USERNAME', 'RDS_DB_PASSWORD': 'DB_PASSWORD',
    'GH_APP_CLIENT_ID': 'GITHUB_APP_CLIENT_ID', 'GH_APP_CLIENT_SECRET': 'GITHUB_APP_CLIENT_SECRET',
    'GH_APP_TOKEN_URL': 'GITHUB_APP_TOKEN_URL', 'GH_APP_REDIRECT_URI': 'GITHUB_APP_REDIRECT_URI',
    'GH_APP_FRONTEND_REDIRECT_URI': 'GITHUB_APP_FRONTEND_REDIRECT_URI',
    'GH_APP_WEBHOOK_SECRET': 'GITHUB_APP_WEBHOOK_SECRET',
}


def valid_env():
    values = {key: 'synthetic-' + key.lower() for key in backend_env.REQUIRED_KEYS}
    values['DB_PASSWORD'] = SECRET
    return values


def secret_data(values=None):
    return {PREFIX + key: value for key, value in (valid_env() if values is None else values).items()}


def secret_payload(values=None, extra=None):
    data = secret_data(values)
    data.update(extra or {})
    return json.dumps(data, ensure_ascii=True)


def records(content):
    return dict(line.split('=', 1) for line in content.decode('utf-8').splitlines())


class BuildEnvironmentTest(unittest.TestCase):
    def assert_rejected(self, payload):
        with self.assertRaises(backend_env.BackendEnvironmentError) as caught:
            backend_env.build_environment(payload)
        self.assertNotIn(SECRET, str(caught.exception))
        self.assertRegex(str(caught.exception), r'\A[a-z_]+\Z')
        return caught.exception

    def test_selects_prefixed_required_values_and_defaults_profile_to_dev(self):
        values = valid_env()
        self.assertEqual(backend_env.SECRET_PREFIX, PREFIX)
        self.assertEqual(records(backend_env.build_environment(secret_payload(values))),
                         {**values, 'SPRING_PROFILES_ACTIVE': 'dev'})

    def test_every_required_key_rejects_missing_empty_and_whitespace_only_values(self):
        for key in backend_env.REQUIRED_KEYS:
            for empty in (None, '', ' \t '):
                with self.subTest(key=key, value_kind='missing' if empty is None else 'empty'):
                    values = valid_env()
                    if empty is None:
                        del values[key]
                    else:
                        values[key] = empty
                    self.assertEqual(self.assert_rejected(secret_payload(values)).code,
                                     'missing_required_configuration')

    def test_unprefixed_individual_values_and_old_blob_are_not_fallbacks(self):
        values = valid_env()
        values['BACKEND_ENV'] = 'DB_PASSWORD=' + SECRET
        self.assert_rejected(json.dumps(values))
        self.assert_rejected('{}')

    def test_unprefixed_credentials_and_control_values_are_ignored(self):
        extra = {'GITHUB_TOKEN': SECRET, 'AWS_SECRET_ACCESS_KEY': SECRET,
                 'BACKEND_ENV': 'LEAK=' + SECRET, 'DB_URL': 'untrusted',
                 'AGENT_SERVICE_URL': 'untrusted', 'SPRING_JPA_HIBERNATE_DDL_AUTO': 'create-drop',
                 'OTHER_SERVICE_TOKEN': SECRET, 'MAIN_DB_PASSWORD': SECRET,
                 'DEVELOPMENT_TOKEN': SECRET, 'DEVX_TOKEN': SECRET}
        actual = records(backend_env.build_environment(secret_payload(extra=extra)))
        self.assertEqual(actual, {**valid_env(), 'SPRING_PROFILES_ACTIVE': 'dev'})
        for key in extra:
            if key not in valid_env():
                self.assertNotIn(key, actual)

    def test_empty_known_optional_values_are_omitted_to_keep_app_defaults(self):
        values = valid_env()
        values.update({key: '' for key in backend_env.OPTIONAL_KEYS})
        values['SPRING_PROFILES_ACTIVE'] = ''
        self.assertEqual(records(backend_env.build_environment(secret_payload(values))),
                         {**valid_env(), 'SPRING_PROFILES_ACTIVE': 'dev'})

    def test_present_known_optional_values_and_explicit_profile_are_forwarded(self):
        values = valid_env()
        values.update({key: 'synthetic-' + key.lower() for key in backend_env.OPTIONAL_KEYS})
        values['SPRING_PROFILES_ACTIVE'] = 'dev,custom'
        self.assertEqual(records(backend_env.build_environment(secret_payload(values))), values)

    def test_new_prefixed_suffixes_are_automatically_forwarded_including_empty_values(self):
        values = valid_env()
        values.update({'NEW_SERVICE_TOKEN': SECRET, 'FEATURE_EXPERIMENT': '', '_CUSTOM_SETTING': 'enabled'})
        self.assertEqual(records(backend_env.build_environment(secret_payload(values))),
                         {**values, 'SPRING_PROFILES_ACTIVE': 'dev'})

    def test_literals_unicode_spaces_quotes_and_equals_are_not_expanded_or_trimmed(self):
        values = valid_env()
        values['NEW_SERVICE_TOKEN'] = '  ' + SECRET + '=서울 # literal "${HOME} $(echo value)" `echo other`  '
        self.assertEqual(records(backend_env.build_environment(secret_payload(values)))['NEW_SERVICE_TOKEN'],
                         values['NEW_SERVICE_TOKEN'])
        values['NEW_SERVICE_TOKEN'] = ' \t '
        self.assertEqual(records(backend_env.build_environment(secret_payload(values)))['NEW_SERVICE_TOKEN'], ' \t ')

    def test_nine_legacy_suffix_aliases_map_to_application_keys(self):
        values = valid_env()
        values['GITHUB_APP_WEBHOOK_SECRET'] = SECRET
        data = secret_data(values)
        for alias, target in EXPECTED_ALIASES.items():
            data[PREFIX + alias] = data.pop(PREFIX + target)
        self.assertEqual(records(backend_env.build_environment(json.dumps(data))),
                         {**values, 'SPRING_PROFILES_ACTIVE': 'dev'})

    def test_case_insensitive_secret_names_are_normalized_to_uppercase_app_keys(self):
        data = {key.lower(): value for key, value in secret_data().items()}
        data['Dev_New_Service_Token'] = SECRET
        self.assertEqual(records(backend_env.build_environment(json.dumps(data))),
                         {**valid_env(), 'SPRING_PROFILES_ACTIVE': 'dev', 'NEW_SERVICE_TOKEN': SECRET})

    def test_alias_collisions_are_rejected_even_when_values_are_equal_or_empty(self):
        for alias, target in EXPECTED_ALIASES.items():
            for second in (SECRET, ''):
                with self.subTest(alias=alias, value_kind='empty' if second == '' else 'same'):
                    values = valid_env()
                    values[target] = SECRET
                    data = secret_data(values)
                    data[PREFIX + alias] = second
                    self.assert_rejected(json.dumps(data))

    def test_case_collisions_are_rejected_even_when_values_match(self):
        data = secret_data()
        data[(PREFIX + 'DB_PASSWORD').lower()] = SECRET
        self.assert_rejected(json.dumps(data))

    def test_relaxed_binding_collisions_are_rejected_before_empty_values_are_omitted(self):
        for alternate in ('DBPASSWORD', 'DB__PASSWORD', '_DB_PASSWORD'):
            for value in ('', SECRET):
                with self.subTest(alternate=alternate, value_kind='empty' if value == '' else 'same'):
                    self.assert_rejected(secret_payload(extra={PREFIX + alternate: value}))

    def test_duplicate_json_keys_are_rejected_instead_of_taking_last_value(self):
        base = secret_payload()
        for duplicate in ('"' + PREFIX + 'DB_PASSWORD":' + json.dumps(SECRET),
                          '"GITHUB_TOKEN":' + json.dumps(SECRET)):
            if 'GITHUB_TOKEN' in duplicate:
                source = base[:-1] + ',"GITHUB_TOKEN":"first",' + duplicate + '}'
            else:
                source = base[:-1] + ',' + duplicate + '}'
            self.assert_rejected(source)

    def test_prefixed_reserved_control_and_transport_names_are_rejected(self):
        reserved = ('AGENT_SERVICE_URL', 'SPRING_JPA_HIBERNATE_DDL_AUTO', 'TZ',
                    'IEUM_DEPLOYMENT_INSTANCE', 'IEUM_DEPLOYMENT_START_DRAINED',
                    'IEUM_SCHEDULER_AUTO_STARTUP', 'GITHUB_TOKEN', TRANSPORT)
        for key in reserved:
            with self.subTest(key=key):
                self.assert_rejected(secret_payload(extra={PREFIX + key: SECRET}))

    def test_relaxed_names_cannot_override_spring_jvm_or_deployment_controls(self):
        for key in ('IEUM_AGENT_URL', 'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
                    '_JAVA_OPTIONS', 'JAVA_OPTS', 'SERVER_SHUTDOWN',
                    'SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE',
                    'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE',
                    'IEUM_WORKFLOW_QUEUE_CONSUMER_NAME',
                    'IEUM_WORKFLOW_QUEUE_PENDING_RECOVERY_NOT_BEFORE',
                    'IEUM_WORKFLOW_QUEUE_PENDING_RECOVERY_EXPECTED_ZONE'):
            for suffix in (key, key.replace('_', ''), key.lower()):
                with self.subTest(key=key, form='reserved override'):
                    self.assert_rejected(secret_payload(extra={PREFIX + suffix: SECRET}))

    def test_new_deployment_and_scheduler_control_suffixes_are_reserved(self):
        for suffix in ('IEUM_DEPLOYMENT_FUTURE_CONTROL', 'IEUM_SCHEDULER_FUTURE_CONTROL',
                       'IEUMDEPLOYMENTFUTURECONTROL', 'IEUMSCHEDULERFUTURECONTROL'):
            with self.subTest(suffix=suffix):
                self.assert_rejected(secret_payload(extra={PREFIX + suffix: SECRET}))

    def test_external_spring_config_sources_and_relaxed_aliases_are_reserved_even_when_empty(self):
        for key in ('SPRING_CONFIG_IMPORT', 'SPRING_CONFIG_LOCATION',
                    'SPRING_CONFIG_ADDITIONAL_LOCATION', 'SPRING_CONFIG_NAME'):
            for suffix in (key, key.lower(), key.replace('_', ''), key.replace('_', '__')):
                for value in ('', 'env:OTHER_CONFIG'):
                    with self.subTest(key=key, form='external config override', empty=value == ''):
                        self.assertEqual(self.assert_rejected(secret_payload(extra={PREFIX + suffix: value})).code,
                                         'reserved_configuration')

    def test_jpa_schema_initializers_and_migrations_are_reserved_even_when_empty(self):
        for key in ('SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO', 'SPRING_JPA_GENERATE_DDL',
                    'SPRING_JPA_FUTURE_SCHEMA_CONTROL', 'SPRING_SQL_INIT_MODE',
                    'SPRING_SQL_INIT_SCHEMA_LOCATIONS', 'SPRING_FLYWAY_ENABLED',
                    'SPRING_FLYWAY_LOCATIONS', 'SPRING_LIQUIBASE_ENABLED',
                    'SPRING_LIQUIBASE_CHANGE_LOG'):
            for suffix in (key, key.lower(), key.replace('_', ''), key.replace('_', '__')):
                for value in ('', SECRET):
                    with self.subTest(key=key, form='schema initializer override', empty=value == ''):
                        self.assertEqual(self.assert_rejected(secret_payload(extra={PREFIX + suffix: value})).code,
                                         'reserved_configuration')

    def test_hikari_sql_hooks_and_relaxed_aliases_are_reserved_even_when_empty(self):
        for key in ('SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL',
                    'SPRING_DATASOURCE_HIKARI_CONNECTION_TEST_QUERY'):
            for suffix in (key, key.lower(), key.replace('_', ''), key.replace('_', '__')):
                for value in ('', 'SELECT 1'):
                    with self.subTest(key=key, form='connection SQL hook', empty=value == ''):
                        self.assertEqual(self.assert_rejected(secret_payload(extra={PREFIX + suffix: value})).code,
                                         'reserved_configuration')

    def test_normal_pool_and_queue_reclaim_settings_are_automatically_forwarded(self):
        values = valid_env()
        values.update({'DB_POOL_MAX_SIZE': '35', 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '35',
                       'IEUM_WORKFLOW_QUEUE_RECLAIM_MIN_IDLE': 'PT10M',
                       'IEUM_WORKFLOW_QUEUE_RECLAIM_INTERVAL': 'PT1M'})
        self.assertEqual(records(backend_env.build_environment(secret_payload(values))),
                         {**values, 'SPRING_PROFILES_ACTIVE': 'dev'})

    def test_invalid_selected_suffixes_are_rejected_without_echo(self):
        for suffix in ('', '1KEY', 'KEY-NAME', 'KEY NAME', 'KEY=OTHER', '서울', 'ß', 'KEY\nHIDDEN'):
            with self.subTest(kind='invalid suffix'):
                self.assert_rejected(secret_payload(extra={PREFIX + suffix: SECRET}))

    def test_all_record_separators_and_nul_are_rejected_for_each_selected_key_kind(self):
        for key in ('DB_PASSWORD', 'MAIL_PASSWORD', 'SPRING_PROFILES_ACTIVE', 'NEW_SERVICE_TOKEN'):
            for control in ('\x00', '\n', '\r', '\v', '\f', '\x1c', '\x1d', '\x1e', '\x85', '\u2028', '\u2029'):
                with self.subTest(key=key, codepoint=ord(control)):
                    values = valid_env()
                    values[key] = SECRET + control + 'HIDDEN=not-a-second-record'
                    self.assertEqual(self.assert_rejected(secret_payload(values)).code,
                                     'unsupported_control_character')

    def test_crlf_in_an_individual_secret_is_rejected_instead_of_creating_records(self):
        values = valid_env()
        values['DB_PASSWORD'] = SECRET + '\r\nNEXT=not-a-record'
        self.assert_rejected(secret_payload(values))

    def test_missing_nonstring_malformed_and_nonobject_json_inputs_are_sanitized(self):
        for payload in (None, '', {}, b'{}', '[', '{' + SECRET, '[]', 'null', 'true', '123', json.dumps(SECRET)):
            with self.subTest(kind=type(payload).__name__):
                self.assert_rejected(payload)

    def test_selected_nonstring_values_including_nested_objects_are_sanitized(self):
        for invalid in (None, 0, True, [], {}, {'nested': SECRET}):
            with self.subTest(kind=type(invalid).__name__):
                values = valid_env()
                values['DB_PASSWORD'] = invalid
                self.assert_rejected(secret_payload(values))

    def test_nonstandard_json_constants_are_rejected_even_outside_selected_namespace(self):
        base = secret_payload()
        for constant in ('NaN', 'Infinity', '-Infinity'):
            with self.subTest(constant=constant):
                self.assert_rejected(base[:-1] + ',"UNSELECTED_CONSTANT":' + constant + '}')

    def test_excessive_json_nesting_is_sanitized_without_a_traceback(self):
        payload = '{"' + PREFIX + 'NESTED":' + '[' * 1500 + '0' + ']' * 1500 + '}'
        self.assert_rejected(payload)

    def test_selected_invalid_unicode_is_sanitized(self):
        values = valid_env()
        values['DB_PASSWORD'] = SECRET + '\ud800'
        self.assert_rejected(secret_payload(values))
        self.assert_rejected(secret_payload() + '\ud800')

    def test_transport_limit_is_enforced_even_when_padding_is_not_selected(self):
        limit = backend_env.MAX_JSON_BYTES
        self.assertEqual(limit, 96 * 1024)
        data = secret_data()
        data['UNSELECTED_PADDING'] = ''
        overhead = len(json.dumps(data).encode('utf-8'))
        data['UNSELECTED_PADDING'] = 'x' * (limit - overhead)
        payload = json.dumps(data)
        self.assertEqual(len(payload.encode('utf-8')), limit)
        self.assertEqual(records(backend_env.build_environment(payload)),
                         {**valid_env(), 'SPRING_PROFILES_ACTIVE': 'dev'})
        data['UNSELECTED_PADDING'] += 'x'
        self.assert_rejected(json.dumps(data))

    def test_transport_limit_counts_raw_utf8_bytes(self):
        data = secret_data()
        data['UNSELECTED_PADDING'] = '한' * (backend_env.MAX_JSON_BYTES // 2)
        payload = json.dumps(data, ensure_ascii=False)
        self.assertLess(len(payload), backend_env.MAX_JSON_BYTES)
        self.assert_rejected(payload)

    def test_total_candidate_limit_accepts_boundary_and_rejects_one_byte_more(self):
        values = valid_env()
        limit = backend_env.MAX_ENV_BYTES
        self.assertEqual(limit, 48 * 1024)
        overhead = len(backend_env.build_environment(secret_payload(values))) + len('NEW_SERVICE_TOKEN=\n')
        values['NEW_SERVICE_TOKEN'] = 'x' * (limit - overhead)
        self.assertEqual(len(backend_env.build_environment(secret_payload(values))), limit)
        values['NEW_SERVICE_TOKEN'] += 'x'
        self.assertEqual(self.assert_rejected(secret_payload(values)).code, 'configuration_too_large')

    def test_candidate_limit_counts_decoded_utf8_bytes_instead_of_character_count(self):
        values = valid_env()
        values['NEW_SERVICE_TOKEN'] = '한' * (backend_env.MAX_ENV_BYTES // 2)
        payload = json.dumps(secret_data(values), ensure_ascii=False)
        self.assertLess(len(values['NEW_SERVICE_TOKEN']), backend_env.MAX_ENV_BYTES)
        self.assert_rejected(payload)

    def test_rebuilding_after_removing_known_and_dynamic_secrets_drops_old_values(self):
        values = valid_env()
        values.update({'MAIL_PASSWORD': SECRET, 'NEW_SERVICE_TOKEN': SECRET})
        before = records(backend_env.build_environment(secret_payload(values)))
        self.assertIn('MAIL_PASSWORD', before)
        self.assertIn('NEW_SERVICE_TOKEN', before)
        del values['MAIL_PASSWORD']
        del values['NEW_SERVICE_TOKEN']
        after = records(backend_env.build_environment(secret_payload(values)))
        self.assertNotIn('MAIL_PASSWORD', after)
        self.assertNotIn('NEW_SERVICE_TOKEN', after)


class WriteCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.candidate = self.root / 'candidate.env'
        self.candidate.touch(mode=0o600)
        self.candidate.chmod(0o600)

    def assert_private_rejection(self, destination, payload=DEFAULT_PAYLOAD):
        with self.assertRaises(backend_env.BackendEnvironmentError) as caught:
            backend_env.write_candidate(destination, secret_payload() if payload is DEFAULT_PAYLOAD else payload)
        self.assertNotIn(SECRET, str(caught.exception))

    def invoke_cli(self, payload=DEFAULT_PAYLOAD, destination=None):
        environment = {'PATH': os.defpath}
        if payload is DEFAULT_PAYLOAD:
            payload = secret_payload()
        if payload is not None:
            environment[TRANSPORT] = payload
        return subprocess.run([sys.executable, str(SCRIPT), str(destination or self.candidate)],
            env=environment, text=True, capture_output=True, timeout=3)

    def test_writes_canonical_bytes_and_preserves_private_mode(self):
        payload = secret_payload()
        backend_env.write_candidate(self.candidate, payload)
        self.assertEqual(self.candidate.read_bytes(), backend_env.build_environment(payload))
        self.assertEqual(stat.S_IMODE(self.candidate.stat().st_mode), 0o600)
        self.assertEqual(self.candidate.stat().st_nlink, 1)

    def test_existing_nonempty_file_is_never_overwritten(self):
        original = b'ORIGINAL=untouched\n'
        self.candidate.write_bytes(original)
        self.assert_private_rejection(self.candidate)
        self.assertEqual(self.candidate.read_bytes(), original)

    def test_invalid_payload_is_rejected_before_existing_destination_is_changed(self):
        original = b'ORIGINAL=untouched\n'
        self.candidate.write_bytes(original)
        self.assert_private_rejection(self.candidate, json.dumps({'DB_PASSWORD': SECRET}))
        self.assertEqual(self.candidate.read_bytes(), original)

    def test_invalid_payload_is_rejected_before_any_destination_is_opened(self):
        with patch.object(backend_env.os, 'open') as opening:
            self.assert_private_rejection(self.candidate, '{' + SECRET)
        opening.assert_not_called()

    def test_short_writes_are_completed_without_losing_bytes(self):
        original_write = os.write
        calls = []
        def short_write(descriptor, content):
            calls.append(len(content))
            return original_write(descriptor, content[:3])
        with patch.object(backend_env.os, 'write', side_effect=short_write):
            backend_env.write_candidate(self.candidate, secret_payload())
        self.assertGreater(len(calls), 1)
        self.assertEqual(self.candidate.read_bytes(), backend_env.build_environment(secret_payload()))

    def test_write_sync_and_zero_write_failures_never_echo_raw_io_errors(self):
        for operation in ('write', 'fsync'):
            self.candidate.write_bytes(b'')
            with self.subTest(operation=operation), patch.object(backend_env.os, operation, side_effect=OSError(SECRET)):
                self.assert_private_rejection(self.candidate)
        self.candidate.write_bytes(b'')
        with patch.object(backend_env.os, 'write', return_value=0):
            self.assert_private_rejection(self.candidate)

    def test_main_reports_io_failure_as_only_the_fixed_code(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(backend_env.os.environ, {TRANSPORT: secret_payload()}, clear=True), \
                patch.object(backend_env.os, 'write', side_effect=OSError(SECRET)), \
                patch.object(backend_env.sys, 'stdout', stdout), patch.object(backend_env.sys, 'stderr', stderr):
            self.assertEqual(backend_env.main([str(self.candidate)]), 1)
        self.assertEqual(stdout.getvalue(), '')
        self.assertEqual(stderr.getvalue(), 'Backend environment rejected: candidate_io_failed\n')

    def test_close_failure_is_sanitized_and_does_not_leak_a_descriptor(self):
        original_close = os.close
        def close_then_fail(descriptor):
            original_close(descriptor)
            raise OSError(SECRET)
        with patch.object(backend_env.os, 'close', side_effect=close_then_fail):
            self.assert_private_rejection(self.candidate)

    def test_missing_path_is_not_created(self):
        missing = self.root / ('missing-' + SECRET)
        self.assert_private_rejection(missing)
        self.assertFalse(missing.exists())

    def test_symlink_is_rejected_without_modifying_its_target(self):
        original = self.candidate.read_bytes()
        link = self.root / 'candidate-link.env'
        link.symlink_to(self.candidate)
        self.assert_private_rejection(link)
        self.assertEqual(self.candidate.read_bytes(), original)

    def test_directory_is_rejected(self):
        self.assert_private_rejection(self.root)

    def test_nonprivate_or_nonwritable_modes_are_rejected(self):
        for mode in (0o644, 0o640, 0o620, 0o400):
            with self.subTest(mode=oct(mode)):
                self.candidate.chmod(mode)
                self.assert_private_rejection(self.candidate)

    def test_foreign_owner_is_rejected(self):
        with patch.object(backend_env.os, 'geteuid', return_value=os.geteuid() + 1):
            self.assert_private_rejection(self.candidate)

    def test_multiple_hardlinks_are_rejected(self):
        linked = self.root / 'other-link.env'
        os.link(self.candidate, linked)
        self.assert_private_rejection(self.candidate)
        self.assertEqual(linked.read_bytes(), b'')

    def test_oversized_existing_candidate_is_not_truncated(self):
        self.candidate.write_bytes(b'x' * (backend_env.MAX_ENV_BYTES + 1))
        size = self.candidate.stat().st_size
        self.assert_private_rejection(self.candidate)
        self.assertEqual(self.candidate.stat().st_size, size)

    def test_cli_refuses_fifo_without_blocking(self):
        fifo = self.root / 'fifo.env'
        os.mkfifo(fifo, 0o600)
        result = self.invoke_cli(destination=fifo)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '')
        self.assertNotIn(SECRET, result.stderr)
        self.assertNotIn('Traceback', result.stderr)

    def test_cli_success_outputs_no_environment_values(self):
        result = self.invoke_cli()
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, '', ''))
        self.assertEqual(self.candidate.read_bytes(), backend_env.build_environment(secret_payload()))

    def test_cli_failure_exposes_only_a_fixed_code(self):
        values = valid_env()
        values['DB_PASSWORD'] = SECRET + '\nHIDDEN=not-a-record'
        result = self.invoke_cli(secret_payload(values))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '')
        self.assertRegex(result.stderr, r'\ABackend environment rejected: [A-Za-z0-9_-]+\n\Z')
        self.assertNotIn(SECRET, result.stderr)
        self.assertEqual(self.candidate.read_bytes(), b'')

    def test_missing_transport_has_no_traceback_or_destination_leak(self):
        private_path = self.root / ('private-' + SECRET)
        result = self.invoke_cli(None, private_path)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '')
        self.assertRegex(result.stderr, r'\ABackend environment rejected: [A-Za-z0-9_-]+\n\Z')
        self.assertNotIn(SECRET, result.stderr)
        self.assertFalse(private_path.exists())


class BackendWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text()
        marker = '      - name: Deploy with prefixed GitHub Secrets\n'
        if cls.source.count(marker) != 1:
            raise AssertionError('Expected exactly one prefixed-Secrets deployment step')
        cls.step = cls.source.split(marker, 1)[1]
        header, body = cls.step.split('        run: |\n', 1)
        if '      - name:' in body:
            raise AssertionError('The audited deployment step must remain the last workflow step')
        cls.header = header
        cls.shell = textwrap.dedent(body)

    def invoke(self, values=None, controller_exit=0, start_xtrace=False, transport=DEFAULT_PAYLOAD, extra=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = root / 'runner temp'
            runner.mkdir()
            log = root / 'controller.json'
            marker = root / 'shell-expansion-must-not-run'
            controller = root / 'fake-controller.py'
            controller.write_text('import json, os, pathlib, stat, sys\n'
                'args=sys.argv[1:]\n'
                'candidate=pathlib.Path(args[args.index("--env-file")+1])\n'
                'observed={"args":args,"path":str(candidate),"mode":stat.S_IMODE(candidate.stat().st_mode),'
                '"content":candidate.read_text(),"transportInherited":' + repr(TRANSPORT) + ' in os.environ,'
                '"appKeysInherited":[k for k in ' + repr(list(backend_env.REQUIRED_KEYS)) + ' if k in os.environ]}\n'
                'pathlib.Path(' + repr(str(log)) + ').write_text(json.dumps(observed))\n'
                'sys.exit(' + str(controller_exit) + ')\n')
            script = self.shell.replace('/home/kwung/deploy/bluegreen/bluegreen.py', shlex.quote(str(controller)))
            script = script.replace('scripts/bluegreen/backend_env.py', shlex.quote(str(SCRIPT)))
            environment = {'PATH': str(Path(sys.executable).parent) + os.pathsep + os.defpath,
                'RUNNER_TEMP': str(runner), 'IMAGE_NAME': 'ieum-backend-dev', 'IMAGE_TAG': 'synthetic-sha',
                'IEUM_BLUEGREEN_JOB_DEADLINE_EPOCH': str(int(time.time()) + 21600)}
            if values is None:
                values = valid_env()
            elif callable(values):
                values = values(marker)
            if transport is DEFAULT_PAYLOAD:
                transport = secret_payload(values, extra)
            if transport is not None:
                environment[TRANSPORT] = transport
            command = ['bash'] + (['-x'] if start_xtrace else []) + ['-c', script]
            result = subprocess.run(command, cwd=root, env=environment, text=True, capture_output=True, timeout=5)
            observed = json.loads(log.read_text()) if log.exists() else None
            return result, observed, list(runner.iterdir()), marker.exists()

    def test_prefixed_transport_is_step_scoped_and_no_secret_expression_enters_shell(self):
        expected = TRANSPORT + ': ${{ toJSON(secrets) }}'
        self.assertIn(expected, self.header)
        self.assertEqual(self.source.count('${{ toJSON(secrets) }}'), 1)
        self.assertNotIn('toJSON(secrets)', self.source.split('      - name: Deploy with prefixed GitHub Secrets\n', 1)[0])
        self.assertNotIn('${{ secrets.', self.source)
        self.assertNotIn('${{', self.shell)
        self.assertNotIn('/home/kwung/deploy/ieum-backend/.env', self.shell)
        self.assertIn('unset ' + TRANSPORT, self.shell)
        self.assertLess(self.shell.index('scripts/bluegreen/backend_env.py'), self.shell.index('unset ' + TRANSPORT))
        self.assertLess(self.shell.index('unset ' + TRANSPORT), self.shell.index('/home/kwung/deploy/bluegreen/bluegreen.py'))

    def test_success_forwards_private_full_candidate_and_cleans_it_after_deploy(self):
        values = valid_env()
        values.update({key: 'synthetic-' + key.lower() for key in backend_env.OPTIONAL_KEYS})
        result, observed, remaining, _ = self.invoke(values)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, '', ''))
        self.assertEqual(records(observed['content'].encode()), {**values, 'SPRING_PROFILES_ACTIVE': 'dev'})
        self.assertEqual(observed['mode'], 0o600)
        self.assertFalse(observed['transportInherited'])
        self.assertEqual(observed['appKeysInherited'], [])
        self.assertIn('--replace-env', observed['args'])
        self.assertEqual(observed['args'][observed['args'].index('--service') + 1], 'backend')
        self.assertEqual(observed['args'][observed['args'].index('--image') + 1], 'ieum-backend-dev:synthetic-sha')
        self.assertIn('--job-deadline-epoch', observed['args'])
        self.assertEqual(remaining, [])
        self.assertNotIn(SECRET, ' '.join(observed['args']))

    def test_new_prefixed_secret_is_used_without_workflow_mapping_changes(self):
        values = valid_env()
        values['NEW_SERVICE_TOKEN'] = SECRET
        result, observed, remaining, _ = self.invoke(values)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(records(observed['content'].encode())['NEW_SERVICE_TOKEN'], SECRET)
        self.assertEqual(remaining, [])

    def test_unprefixed_other_credentials_never_enter_candidate(self):
        extra = {'GITHUB_TOKEN': SECRET, 'AWS_SECRET_ACCESS_KEY': SECRET, 'OTHER_SERVICE_TOKEN': SECRET, 'MAIN_DB_PASSWORD': SECRET,
                 'DEVELOPMENT_TOKEN': SECRET, 'DEVX_TOKEN': SECRET}
        result, observed, remaining, _ = self.invoke(extra=extra)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(records(observed['content'].encode()), {**valid_env(), 'SPRING_PROFILES_ACTIVE': 'dev'})
        self.assertFalse(observed['transportInherited'])
        self.assertEqual(remaining, [])

    def test_controller_failure_preserves_status_and_cleans_temporary_candidate(self):
        result, observed, remaining, _ = self.invoke(controller_exit=23)
        self.assertEqual(result.returncode, 23)
        self.assertIsNotNone(observed)
        self.assertFalse(observed['transportInherited'])
        self.assertEqual(remaining, [])
        self.assertNotIn(SECRET, result.stdout + result.stderr)

    def test_missing_required_secret_prevents_controller_and_cleans_candidate(self):
        for key in backend_env.REQUIRED_KEYS:
            with self.subTest(key=key):
                values = valid_env()
                del values[key]
                result, observed, remaining, _ = self.invoke(values)
                self.assertEqual(result.returncode, 1)
                self.assertIsNone(observed)
                self.assertEqual(remaining, [])
                self.assertEqual(result.stdout, '')
                self.assertEqual(result.stderr, 'Backend environment rejected: missing_required_configuration\n')

    def test_invalid_transport_prevents_controller_and_cleans_candidate(self):
        for invalid in (None, '', '{' + SECRET, '[]', secret_payload(extra={PREFIX + 'AGENT_SERVICE_URL': SECRET})):
            with self.subTest(kind='invalid transport'):
                result, observed, remaining, _ = self.invoke(transport=invalid)
                self.assertEqual(result.returncode, 1)
                self.assertIsNone(observed)
                self.assertEqual(remaining, [])
                self.assertEqual(result.stdout, '')
                self.assertRegex(result.stderr, r'\ABackend environment rejected: [A-Za-z0-9_-]+\n\Z')
                self.assertNotIn(SECRET, result.stderr)

    def test_invalid_selected_value_prevents_controller_and_cleans_candidate(self):
        for invalid in (SECRET + '\rhidden', SECRET + '\nHIDDEN=extra', SECRET + '\u2028HIDDEN=extra',
                        SECRET + 'x' * backend_env.MAX_ENV_BYTES):
            with self.subTest(kind='invalid value'):
                values = valid_env()
                values['MAIL_PASSWORD'] = invalid
                result, observed, remaining, _ = self.invoke(values)
                self.assertEqual(result.returncode, 1)
                self.assertIsNone(observed)
                self.assertEqual(remaining, [])
                self.assertEqual(result.stdout, '')
                self.assertRegex(result.stderr, r'\ABackend environment rejected: [A-Za-z0-9_-]+\n\Z')
                self.assertNotIn(SECRET, result.stderr)

    def test_values_with_shell_syntax_are_not_executed_or_expanded(self):
        def values(marker):
            output = valid_env()
            output['NEW_SERVICE_TOKEN'] = SECRET + '$(touch ' + shlex.quote(str(marker)) + ')`touch ' + shlex.quote(str(marker)) + '`'
            return output
        result, observed, remaining, expanded = self.invoke(values)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, '', ''))
        self.assertIn('$(touch ', records(observed['content'].encode())['NEW_SERVICE_TOKEN'])
        self.assertIn('`touch ', records(observed['content'].encode())['NEW_SERVICE_TOKEN'])
        self.assertFalse(expanded)
        self.assertFalse(observed['transportInherited'])
        self.assertEqual(remaining, [])

    def test_each_new_run_removes_deleted_dynamic_and_empty_optional_values(self):
        values = valid_env()
        values.update({'MAIL_PASSWORD': SECRET, 'NEW_SERVICE_TOKEN': SECRET})
        _, first, _, _ = self.invoke(values)
        del values['MAIL_PASSWORD']
        del values['NEW_SERVICE_TOKEN']
        result, second, remaining, _ = self.invoke(values)
        self.assertEqual(result.returncode, 0)
        self.assertIn('MAIL_PASSWORD', records(first['content'].encode()))
        self.assertIn('NEW_SERVICE_TOKEN', records(first['content'].encode()))
        self.assertNotIn('MAIL_PASSWORD', records(second['content'].encode()))
        self.assertNotIn('NEW_SERVICE_TOKEN', records(second['content'].encode()))
        self.assertIn('--replace-env', second['args'])
        self.assertEqual(remaining, [])

    def test_xtrace_is_disabled_before_transport_processing(self):
        result, observed, remaining, _ = self.invoke(start_xtrace=True)
        self.assertEqual(result.returncode, 0)
        self.assertIsNotNone(observed)
        self.assertNotIn(SECRET, result.stdout + result.stderr)
        self.assertEqual(result.stderr.strip(), '+ set +x')
        self.assertEqual(remaining, [])

    def test_tests_budget_and_release_pin_checks_precede_secret_deploy_step(self):
        deploy = self.source.index('      - name: Deploy with prefixed GitHub Secrets\n')
        for marker in ('      - name: Establish deployment job budget\n', '      - name: Test\n',
                       '      - name: Build Docker image\n', '      - name: Verify installed blue-green release\n'):
            self.assertLess(self.source.index(marker), deploy)
        self.assertIn("python3 -m unittest discover -s scripts/bluegreen -p 'test_*.py'", self.source)
        self.assertIn('--lock-timeout 14400 deploy', self.shell)
        self.assertIn('--job-deadline-epoch "$IEUM_BLUEGREEN_JOB_DEADLINE_EPOCH"', self.shell)
        self.assertNotIn('IEUM_BLUEGREEN_JOB_DEADLINE_EPOCH=', self.shell)


if __name__ == '__main__':
    unittest.main()
