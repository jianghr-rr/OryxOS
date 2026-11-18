#!/usr/bin/env python3
"""Black-box launcher/storage diagnostics and HTTP health using built JARs and temporary roots."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def verify():
    repo = Path(__file__).resolve().parents[2]
    java_home = os.environ.get('ORYXOS_JAVA_HOME')
    if not java_home:
        raise RuntimeError('Set ORYXOS_JAVA_HOME to an existing JDK 21 first')
    java = str(Path(java_home) / 'bin/java')
    env = {key: os.environ[key] for key in ('HOME', 'USER', 'LOGNAME') if key in os.environ}
    env.update(PATH='/usr/bin:/bin', LANG='en_US.UTF-8', TZ='UTC')
    version = subprocess.run([java, '-version'], env=env, capture_output=True, text=True, check=True).stderr
    assert 'version "21.' in version, 'JDK 21 required'
    boot = repo / 'oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar'
    thin = repo / 'oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar'
    assert boot.is_file() and thin.is_file(), 'Run tools/dev/mvnw clean verify first'
    evidence = repo / '.oryxos-dev/evidence'
    evidence.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory(prefix='f0-smoke-', dir=repo / '.oryxos-dev/tmp') as directory:
        temporary = Path(directory)
        workspace = temporary / 'workspace'
        foreign = temporary / 'foreign'
        foreign.mkdir()
        env['ORYXOS_ROOT'] = str(temporary / 'environment-root')
        command = [java, f'-Duser.home={temporary}', f'-Djava.io.tmpdir={temporary}',
                   f'-Doryxos.root={temporary / "property-root"}', '-Dserver.port=0', '-jar', str(boot)]

        def invoke(args, expected=0):
            result = subprocess.run([*command, *args], cwd=foreign, env=env, capture_output=True, text=True, timeout=20)
            assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
            return result.stdout + result.stderr

        for option in ('--help', '--version'):
            output = invoke([option])
            assert 'Spring Boot' not in output and 'Tomcat' not in output
        assert not workspace.exists()
        thin_result = subprocess.run([java, '-jar', str(thin), '--version'], env=env, capture_output=True, text=True, check=True)
        assert '1.0.0-SNAPSHOT' in thin_result.stdout
        results.append('boot help/version and thin version: PASS, no runtime startup')
        prefix = ['--root', str(workspace)]
        invoke([*prefix, 'init'])
        (workspace / 'USER.md').write_text('Keep user-authored content\n')
        before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in workspace.rglob('*') if p.is_file()}
        invoke([*prefix, 'init'])
        assert all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == value for p, value in before.items())
        output = invoke([*prefix, 'status'])
        assert 'NOT_RUNNING' in output and 'Asia/Shanghai' in output and 'NOT_CHECKED' in output and 'production session/execution storage pending' not in output
        assert not (workspace / 'oryxos.db').exists()
        assert not (temporary / 'property-root').exists() and not (temporary / 'environment-root').exists()
        results.append('idempotent init, status, explicit root from foreign cwd: PASS')

        def service(args, expected_log):
            logfile = temporary / 'server.log'
            with logfile.open('w') as stream:
                process = subprocess.Popen([*command, *args], cwd=foreign, env=env, stdout=stream, stderr=subprocess.STDOUT)
            try:
                until = time.monotonic() + 30
                while time.monotonic() < until:
                    text = logfile.read_text()
                    port_match = re.search(r'Tomcat started on port (\d+)', text)
                    if port_match:
                        break
                    assert process.poll() is None, text
                    time.sleep(0.1)
                else:
                    raise AssertionError('HTTP process did not start: ' + logfile.read_text())
                assert expected_log in text, text
                assert 'RUNNING' in invoke([*prefix, 'status'])
                invoke([*prefix, 'serve'], 4)
                invoke([*prefix, 'chat', '--profile', 'default', '--user', 'smoke'], 4)
                try:
                    response = urllib.request.urlopen(f'http://127.0.0.1:{port_match[1]}/api/v1/health', timeout=5)
                except urllib.error.HTTPError as error:
                    response = error
                health = json.load(response)
                assert health['data']['live'] is True and health['traceId']
                ready = 'storage=AVAILABLE' in expected_log
                assert response.code == (200 if ready else 503)
                assert health['data']['ready'] is ready
                assert health['data']['state'] == ('READY' if ready else 'PAUSED_RECORDING')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            assert 'NOT_RUNNING' in invoke([*prefix, 'status'])

        service([*prefix, 'serve'], 'storage=AVAILABLE admission=STARTING')
        results.append('real HTTP process, second-process/chat rejection, graceful unlock: PASS')
        # Keep the same DB and prove no-argument boot honors JVM root over environment root.
        command[3] = f'-Doryxos.root={workspace}'
        service([], 'storage=AVAILABLE admission=STARTING')
        results.append('no-argument serve and JVM-root precedence: PASS')
        # Damage a disposable database only; retain its bytes through degraded startup.
        (workspace / 'oryxos.db').write_text('deliberately-corrupt-f0-db')
        service([*prefix, 'serve'], 'storage=STORAGE_UNAVAILABLE admission=PAUSED_RECORDING')
        assert (workspace / 'oryxos.db').read_text() == 'deliberately-corrupt-f0-db'
        results.append('corrupt SQLite retains bytes and diagnostic HTTP process: PASS')
        invoke([*prefix, 'chat', '--profile', 'default', '--user', 'smoke'], 7)
    result = {'scope': 'Packaged launcher/storage diagnostics and HTTP health; no Agent, provider, tool or notification calls', 'checks': results}
    (evidence / 'f0-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    for line in results:
        print(line)


if __name__ == '__main__':
    verify()
