"""Launch the disposable real-backend browser fixture with the isolated JDK/classpath."""
import argparse, os, pathlib, sys, tempfile, xml.etree.ElementTree as ET
jdk, repo = map(pathlib.Path, sys.argv[1:3])
p = argparse.ArgumentParser(description='LOCAL TEST ONLY: real SQLite/HTTP, simulated loopback model and recipient.')
p.add_argument('--root', type=pathlib.Path)
p.add_argument('--port', type=int, default=18080)
a = p.parse_args(sys.argv[3:])
reports = list((repo / 'oryxos-boot/target/surefire-reports').glob('TEST-*.xml'))
if not reports:
    p.error('Build first: tools/dev/mvnw -B -ntp clean test -Dgroups=us5-admin')
props = {x.get('name'): x.get('value') for x in ET.parse(reports[0]).findall('./properties/property')}
classpath = props.get('java.class.path')
if not classpath or not (repo / 'oryxos-boot/target/test-classes/io/github/oryxlabs/oryxos/boot/support/AdminBrowserProbe.class').exists():
    p.error('Browser probe missing. Build first with the us5-admin test group.')
root = a.root or pathlib.Path(tempfile.mkdtemp(prefix='admin-browser-', dir=repo / '.oryxos-dev/tmp'))
root = root.resolve()
if not root.is_relative_to((repo / '.oryxos-dev').resolve()):
    p.error('Local browser fixture must stay under this project’s .oryxos-dev/')
os.execve(str(jdk / 'bin/java'), [str(jdk / 'bin/java'), '-Duser.home=' + str(repo / '.oryxos-dev/home'), '-Djava.io.tmpdir=' + str(repo / '.oryxos-dev/tmp'), '-cp', classpath, 'io.github.oryxlabs.oryxos.boot.support.AdminBrowserProbe', str(root), str(a.port)], {'PATH': '/usr/bin:/bin', 'LANG': 'en_US.UTF-8'})
