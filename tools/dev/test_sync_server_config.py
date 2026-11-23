"""Release-time server config sync and rollback paths, run against a temporary root with stubbed system tools."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / 'deploy/tencent-cloud/scripts'
SERVER_CONFIG = REPO / 'deploy/tencent-cloud/server-config'
DROPIN = 'etc/systemd/system/oryxos.service.d/10-observability.conf'

STUBS = {
    'systemctl': '#!/bin/bash\necho "systemctl $*" >> "$ORYXOS_SYNC_ROOT/calls.log"\n'
                 '[[ -n ${SYSTEMCTL_FAIL:-} && "$*" == ${SYSTEMCTL_FAIL}* ]] && exit 1\nexit 0\n',
    'curl': '#!/bin/bash\ncurrent=$(basename "$(readlink "$ORYXOS_SYNC_ROOT/opt/oryxos/current")")\n'
            '[[ $current == "${CURL_FAIL_RELEASE:-none}" ]] && exit 7\necho \'{"state":"RUNNING"}\'\n',
    'id': '#!/bin/bash\necho 0\n',
    'journalctl': '#!/bin/bash\nexit 0\n',
}


class Harness(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name).resolve()
        stubs = self.root / 'stubs'
        stubs.mkdir()
        for name, body in STUBS.items():
            (stubs / name).write_text(body)
            (stubs / name).chmod(0o755)
        (self.root / 'etc/systemd/system').mkdir(parents=True)
        self.env = {'PATH': f'{stubs}:/usr/bin:/bin:/usr/sbin:/sbin', 'ORYXOS_SYNC_ROOT': str(self.root),
                    'ORYXOS_SYNC_SETTLE_SECONDS': '0', 'ORYXOS_HEALTH_ATTEMPTS': '1', 'HOME': str(self.root)}

    def tearDown(self):
        self.directory.cleanup()

    def install_monitoring(self, promtool_fails=False):
        for path in ('opt/monitoring/prometheus/current/prometheus', 'opt/monitoring/grafana/current/bin/grafana', 'opt/monitoring/prometheus/current/promtool'):
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_text('#!/bin/bash\nexit %d\n' % (1 if promtool_fails and path.endswith('promtool') else 0))
            (self.root / path).chmod(0o755)

    def release(self, name, dropin=None):
        source = self.root / 'releases' / name / 'server-config'
        shutil.copytree(SERVER_CONFIG, source)
        if dropin is not None:
            (source / 'systemd/oryxos.service.d/10-observability.conf').write_text(dropin)
        return source

    def sync(self, *args, **extra):
        return subprocess.run(['bash', str(SCRIPTS / 'sync-server-config.sh'), *map(str, args)],
                              env={**self.env, **extra}, capture_output=True, text=True)

    def calls(self):
        log = self.root / 'calls.log'
        return log.read_text().splitlines() if log.exists() else []

    def restarts(self):
        return [c for c in self.calls() if c.startswith('systemctl restart')]


class SyncServerConfigTest(Harness):
    def test_unchanged_config_restarts_nothing_and_manifest_matches_repository(self):
        self.install_monitoring()
        source = self.release('r1')
        self.assertEqual(0, self.sync('oryxos-dropin', source).returncode)
        first = self.sync('monitoring', source)
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(['systemctl restart prometheus', 'systemctl restart grafana'], self.restarts())
        (self.root / 'calls.log').unlink()
        second = self.sync('monitoring', source)
        self.assertIn('monitoring: unchanged', second.stdout)
        self.assertEqual([], self.restarts())
        manifest = (self.root / 'opt/oryxos/monitoring-config/server-config.sha256').read_text().splitlines()
        expected = sorted(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(SERVER_CONFIG).as_posix()}'
                          for p in SERVER_CONFIG.rglob('*') if p.is_file())
        self.assertEqual(expected, sorted(manifest))

    def test_dashboard_only_change_restarts_only_grafana(self):
        self.install_monitoring()
        self.sync('monitoring', self.release('r1'))
        (self.root / 'calls.log').unlink()
        source = self.release('r2')
        dashboard = source / 'monitoring/grafana/dashboards/oryxos-jvm-http.json'
        dashboard.write_text(dashboard.read_text().replace('"version": 1', '"version": 2'))
        result = self.sync('monitoring', source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('monitoring: grafana changed, restarted', result.stdout)
        self.assertEqual(['systemctl restart grafana'], self.restarts())

    def test_dashboard_json_is_validated_as_utf8_under_ascii_locale(self):
        self.install_monitoring()
        self.sync('monitoring', self.release('r1'))
        link = self.root / 'opt/oryxos/monitoring-config/current'
        ascii_locale = {'LC_ALL': 'C', 'LANG': 'C', 'PYTHONUTF8': '0'}
        source = self.release('r2')
        dashboard = source / 'monitoring/grafana/dashboards/oryxos-jvm-http.json'
        text = dashboard.read_text(encoding='utf-8')
        self.assertFalse(text.isascii(), 'fixture must carry non-ASCII text')
        dashboard.write_text(text.replace('"version": 1', '"version": 2'), encoding='utf-8')
        result = self.sync('monitoring', source, **ascii_locale)
        self.assertEqual(0, result.returncode, result.stderr)
        before = os.readlink(link)
        broken = self.release('r3')
        (broken / 'monitoring/grafana/dashboards/oryxos-jvm-http.json').write_text('{"title": "中文",', encoding='utf-8')
        result = self.sync('monitoring', broken, **ascii_locale)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('invalid dashboard JSON', result.stderr)
        self.assertEqual(before, os.readlink(link))

    def test_promtool_failure_keeps_previous_link(self):
        self.install_monitoring()
        self.sync('monitoring', self.release('r1'))
        link = self.root / 'opt/oryxos/monitoring-config/current'
        before = os.readlink(link)
        self.install_monitoring(promtool_fails=True)
        source = self.release('r2')
        (source / 'monitoring/prometheus/prometheus.yml').write_text('global: {scrape_interval: 1m}\n')
        result = self.sync('monitoring', source)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('promtool rejected', result.stderr)
        self.assertEqual(before, os.readlink(link))

    def test_failed_restart_restores_previous_monitoring_config(self):
        self.install_monitoring()
        self.sync('monitoring', self.release('r1'))
        link = self.root / 'opt/oryxos/monitoring-config/current'
        before = os.readlink(link)
        source = self.release('r2')
        (source / 'monitoring/grafana/grafana.ini').write_text('[server]\nhttp_port = 3300\n')
        result = self.sync('monitoring', source, SYSTEMCTL_FAIL='restart grafana')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, os.readlink(link))

    def test_not_installed_monitoring_syncs_files_and_skips_restart(self):
        result = self.sync('monitoring', self.release('r1'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('monitoring not installed; skipped restart', result.stdout)
        self.assertEqual([], self.restarts())
        self.assertTrue((self.root / 'opt/oryxos/monitoring-config/current/prometheus/prometheus.yml').is_file())

    def test_dropin_failure_restores_previous_and_restore_undoes_creation(self):
        dropin = self.root / DROPIN
        dropin.parent.mkdir(parents=True)
        dropin.write_text('v0\n')
        result = self.sync('oryxos-dropin', self.release('r1', 'v1\n'), SYSTEMCTL_FAIL='daemon-reload')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('v0\n', dropin.read_text())
        self.assertEqual(0, self.sync('oryxos-dropin', self.release('r2', 'v2\n')).returncode)
        self.assertEqual('v2\n', dropin.read_text())
        self.sync('oryxos-dropin', '--restore')
        self.assertEqual('v0\n', dropin.read_text())
        dropin.unlink()
        self.sync('oryxos-dropin', self.release('r3', 'v3\n'))
        self.sync('oryxos-dropin', '--restore')
        self.assertFalse(dropin.exists())


class DeployReleaseRollbackTest(Harness):
    """deploy-release.sh failure paths must leave the previous release running with its own drop-in."""

    def deploy_release(self, name, with_server_config):
        release = self.root / 'opt/oryxos/releases' / name
        (release / 'lib').mkdir(parents=True)
        jar = release / 'oryxos-boot.jar'
        jar.write_bytes(name.encode())
        (release / 'oryxos-boot.jar.sha256').write_text(f'{hashlib.sha256(jar.read_bytes()).hexdigest()}  oryxos-boot.jar\n')
        if with_server_config:
            shutil.copytree(SERVER_CONFIG, release / 'server-config')
        return release

    def prepare(self):
        old = self.deploy_release('old', with_server_config=False)
        new = self.deploy_release('new', with_server_config=True)
        os.symlink(old, self.root / 'opt/oryxos/current')
        (self.root / 'var/lib/oryxos/workspace').mkdir(parents=True)
        (self.root / 'var/lib/oryxos/workspace/workspace.json').write_text('{}')
        (self.root / 'opt/oryxos/bin').mkdir(parents=True)
        shutil.copy(SCRIPTS / 'sync-server-config.sh', self.root / 'opt/oryxos/bin/sync-server-config.sh')
        (self.root / 'opt/oryxos/bin/sync-server-config.sh').chmod(0o755)
        dropin = self.root / DROPIN
        dropin.parent.mkdir(parents=True)
        dropin.write_text('pre-release\n')
        return old, new, dropin

    def deploy(self, release, **extra):
        return subprocess.run(['bash', str(SCRIPTS / 'deploy-release.sh'), str(release)],
                              env={**self.env, **extra}, capture_output=True, text=True)

    def test_dropin_failure_rolls_back_and_restarts_previous(self):
        old, new, dropin = self.prepare()
        result = self.deploy(new, SYSTEMCTL_FAIL='daemon-reload')
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(str(old), os.readlink(self.root / 'opt/oryxos/current'))
        self.assertEqual('systemctl start oryxos', self.calls()[-1])
        self.assertEqual('pre-release\n', dropin.read_text())
        self.assertIn('Auto-rollback succeeded', result.stdout)

    def test_unhealthy_release_restores_dropin_of_target_without_server_config(self):
        old, new, dropin = self.prepare()
        result = self.deploy(new, CURL_FAIL_RELEASE='new')
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(str(old), os.readlink(self.root / 'opt/oryxos/current'))
        self.assertEqual('pre-release\n', dropin.read_text())
        self.assertIn('Auto-rollback succeeded', result.stdout)


if __name__ == '__main__':
    unittest.main()
