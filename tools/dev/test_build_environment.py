#!/usr/bin/env python3
"""Verify real-mode secret opt-in without launching Maven or contacting providers."""
import importlib.util
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('personal_build', REPO / 'tools/dev/build.py')
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class EnvironmentIsolationTest(unittest.TestCase):
    def captured(self, args):
        with patch.dict(os.environ, {'HOME': '/fixture/home', 'JAVA_TOOL_OPTIONS': 'untrusted',
                                    'MAVEN_OPTS': 'untrusted', 'DEEPSEEK_API_KEY': 'fixture-deepseek',
                                    'KIMI_API_KEY': 'fixture-kimi', 'MEM0_API_KEY': 'fixture-mem0', 'MEM0_MODEL_API_KEY': 'must-not-forward', 'ACSP_TOKEN': 'fixture-unrelated'}, clear=True):
            with patch.object(BUILD.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run:
                self.assertEqual(0, BUILD.run(REPO, Path('/fixture/jdk21'), args))
                return run.call_args.kwargs['env']

    def test_local_never_inherits_secrets_or_foreign_java_options(self):
        env = self.captured(['validate'])
        self.assertNotIn('DEEPSEEK_API_KEY', env)
        self.assertNotIn('KIMI_API_KEY', env)
        self.assertNotIn('ACSP_TOKEN', env)
        self.assertNotIn('MEM0_API_KEY', env)
        self.assertNotIn('MEM0_MODEL_API_KEY', env)
        self.assertNotIn('untrusted', env.values())
        self.assertIn(str(REPO / '.oryxos-dev/home'), env['JAVA_TOOL_OPTIONS'])

    def test_real_provider_only_forwards_explicit_allowlist(self):
        env = self.captured(['validate', '-Doryxos.tests.mode=real', '-Dgroups=real-provider'])
        self.assertEqual('fixture-deepseek', env['DEEPSEEK_API_KEY'])
        self.assertEqual('fixture-kimi', env['KIMI_API_KEY'])
        self.assertNotIn('ACSP_TOKEN', env)
        self.assertNotIn('MEM0_API_KEY', env)
        self.assertNotIn('MEM0_MODEL_API_KEY', env)
        self.assertNotIn('untrusted', env.values())

    def test_real_tools_only_forwards_explicit_allowlist(self):
        env = self.captured(['validate', '-Doryxos.tests.mode=real', '-Dgroups=real-provider-tools'])
        self.assertEqual('fixture-deepseek', env['DEEPSEEK_API_KEY'])
        self.assertEqual('fixture-kimi', env['KIMI_API_KEY'])
        self.assertNotIn('ACSP_TOKEN', env)

    def test_real_mem0_forwards_only_its_client_key(self):
        env = self.captured(['validate', '-Doryxos.tests.mode=real', '-Dgroups=real-mem0'])
        self.assertEqual('fixture-mem0', env['MEM0_API_KEY'])
        for key in ('DEEPSEEK_API_KEY', 'KIMI_API_KEY', 'MEM0_MODEL_API_KEY', 'ACSP_TOKEN'):
            self.assertNotIn(key, env)

    def test_quality_gate_skips_and_profile_deactivation_are_rejected(self):
        for arg in ('-Dspotless.check.skip=true', '-Dcheckstyle.skip', '-Dpmd.skip=true', '-Dcpd.skip=true',
                    '-Dspotbugs.skip=true', '-Ddependency-check.skip=true', '-Dformat.skip'):
            with self.assertRaises(ValueError, msg=arg):
                BUILD.run(REPO, Path('/fixture/jdk21'), ['validate', arg])
        for args in (['-P!dependency-scan'], ['-P', 'a,-b'], ['--activate-profiles=!x']):
            with self.assertRaises(ValueError, msg=args):
                BUILD.run(REPO, Path('/fixture/jdk21'), ['validate', *args])

    def test_nvd_key_only_forwarded_for_dependency_scan(self):
        with patch.dict(os.environ, {'NVD_API_KEY': 'fixture-nvd'}):
            self.assertNotIn('NVD_API_KEY', self.captured(['validate']))
        with patch.dict(os.environ, {'HOME': '/fixture/home', 'NVD_API_KEY': 'fixture-nvd'}, clear=True):
            with patch.object(BUILD.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run:
                BUILD.run(REPO, Path('/fixture/jdk21'), ['-Pdependency-scan', 'dependency-check:aggregate'])
                self.assertEqual('fixture-nvd', run.call_args.kwargs['env']['NVD_API_KEY'])

    def test_ci_settings_selected_only_on_github_actions(self):
        for flag, expected in ((None, 'maven-settings.xml'), ('true', 'maven-settings-ci.xml')):
            values = {'HOME': '/fixture/home'} if flag is None else {'HOME': '/fixture/home', 'GITHUB_ACTIONS': flag}
            with patch.dict(os.environ, values, clear=True):
                with patch.object(BUILD.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run:
                    BUILD.run(REPO, Path('/fixture/jdk21'), ['validate'])
                    command = run.call_args.args[0]
                    self.assertEqual(str(REPO / 'tools/dev' / expected), command[command.index('-s') + 1])

    def test_real_group_requires_real_mode(self):
        with self.assertRaises(ValueError):
            BUILD.run(REPO, Path('/fixture/jdk21'), ['validate', '-Dgroups=real-provider'])


if __name__ == '__main__':
    unittest.main()
