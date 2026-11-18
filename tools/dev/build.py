#!/usr/bin/env python3
"""Project-local Maven environment and fail-closed test evidence gate."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


def run(repo, java, args):
    dev = repo / '.oryxos-dev'
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    modules = [e.text for e in ET.parse(repo / 'pom.xml').findall('m:modules/m:module', ns)]
    mode, groups, selected = 'local', None, None
    forbidden = ('-Dmaven.repo.local', '-Duser.home', '-Djava.home', '-Dmaven.multiModuleProjectDirectory',
                 '-Dmaven.test.skip', '-DskipTests', '-Dsurefire.', '-Doryxos.test.runId', '-Doryxos.test.evidenceDir', '-DexcludedGroups')
    for i, arg in enumerate(args):
        if arg in ('-f', '--file', '-s', '--settings', '-gs', '--global-settings', '-rf', '--resume-from') or arg.startswith(forbidden):
            raise ValueError('Isolation/test-gate override is not supported: ' + arg.split('=')[0])
        if arg.startswith(('--file=', '--settings=', '--global-settings=')):
            raise ValueError('Build must use the personal repository and settings.')
        if arg.startswith('-Doryxos.tests.mode='):
            mode = arg.split('=', 1)[1]
        if arg.startswith('-Dgroups='):
            groups = arg.split('=', 1)[1].split(',')
        if arg in ('-pl', '--projects'):
            selected = args[i + 1].split(',')
        if arg.startswith('--projects='):
            selected = arg.split('=', 1)[1].split(',')
    if selected is not None:
        selected = [x.removeprefix(':').rstrip('/') for x in selected]
        if any(x not in modules for x in selected):
            raise ValueError('Select explicit module names with -pl (no exclusions).')
        if '-am' in args or '--also-make' in args:
            pending = list(selected)
            while pending:
                name = pending.pop()
                for e in ET.parse(repo / name / 'pom.xml').findall('m:dependencies/m:dependency/m:artifactId', ns):
                    if e.text in modules and e.text not in selected:
                        selected.append(e.text)
                        pending.append(e.text)
    else:
        selected = modules
    manifest = json.loads((repo / 'tools/dev/test-groups.json').read_text())['groups']
    if mode not in ('local', 'real', 'soak'):
        raise ValueError('Unknown test mode.')
    if groups is None:
        if mode != 'local':
            raise ValueError('real/soak mode requires explicit registered groups.')
        groups = [key for key, value in manifest.items() if value['mode'] == 'local']
    if not groups or any(g not in manifest or manifest[g]['mode'] != mode for g in groups):
        raise ValueError('Unknown/unimplemented group or mode/group mismatch.')
    expected = [t for g in groups for t in manifest[g]['tests'] if t['module'] in selected]
    testing = any(a in ('test', 'package', 'verify', 'install', 'deploy', 'surefire:test') for a in args)
    if any(a == 'deploy' or a.startswith('deploy:') for a in args):
        raise ValueError('This entry point does not publish artifacts.')
    if testing and not expected:
        raise ValueError('No registered tests for this reactor selection; select a covered module/group.')
    run_id = str(uuid.uuid4())
    if testing:
        for name in selected:
            shutil.rmtree(repo / name / 'target/surefire-reports', ignore_errors=True)
    # Ordinary builds inherit no API keys or personal Maven/Java options.
    env = {k: os.environ[k] for k in ('HOME', 'USER', 'LOGNAME') if k in os.environ}
    env.update(PATH=f'{java}/bin:/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(java),
               MAVEN_USER_HOME=str(dev / 'maven'), MAVEN_SKIP_RC='1',
               MAVEN_OPTS=f'-Duser.home={dev / "home"} -Djava.io.tmpdir={dev / "tmp"}',
               JAVA_TOOL_OPTIONS=f'-Duser.home={dev / "home"} -Djava.io.tmpdir={dev / "tmp"}',
               TMPDIR=str(dev / 'tmp') + '/', LANG='en_US.UTF-8', TZ='UTC')
    if mode == 'real' and {'real-provider', 'real-provider-tools'}.intersection(groups):
        # Explicit opt-in: only the two names documented for this acceptance group.
        # Alternatively use the real workspace's permission-checked secrets.local.yaml.
        for key in ('DEEPSEEK_API_KEY', 'KIMI_API_KEY'):
            if key in os.environ:
                env[key] = os.environ[key]
    if mode == 'real' and 'real-mem0' in groups:
        for key in ('MEM0_API_KEY', 'ORYXOS_DOCKER_HOST'):
            if key in os.environ:
                env[key] = os.environ[key]
    excluded = {'local': 'real-integration,kernel-soak,kernel-short-gate,kernel-writer-probe,kernel-redactor-probe,kernel-reader-probe,kernel-provider-probe', 'real': 'kernel-soak', 'soak': 'real-integration'}[mode]
    command = [str(repo / 'mvnw'), *args, '-s', str(repo / 'tools/dev/maven-settings.xml'),
               '-gs', str(repo / 'tools/dev/maven-global-settings.xml'), f'-Dmaven.repo.local={dev / "repository"}',
               f'-Doryxos.test.runId={run_id}', f'-Doryxos.test.evidenceDir={dev / "evidence"}', f'-DexcludedGroups={excluded}']
    result = subprocess.run(command, cwd=repo, env=env)
    if result.returncode:
        return result.returncode
    if testing:
        spec = importlib.util.spec_from_file_location('reports', repo / 'tools/dev/check-test-reports.py')
        reports = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reports)
        reports.check(repo, expected, run_id)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(run(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:]))
    except (ValueError, IndexError, OSError) as error:
        print(f'Build gate: {error}', file=sys.stderr)
        sys.exit(2)
