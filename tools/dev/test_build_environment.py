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

    def test_real_group_requires_real_mode(self):
        with self.assertRaises(ValueError):
            BUILD.run(REPO, Path('/fixture/jdk21'), ['validate', '-Dgroups=real-provider'])


if __name__ == '__main__':
    unittest.main()
