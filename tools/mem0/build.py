#!/usr/bin/env python3
"""Prepare pinned sources, or explicitly build an isolated personal image. No service startup or publishing."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import os

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/mem0'
DEST = ROOT / '.oryxos-dev/mem0-build'


def run(build=False):
    lock = json.loads((SOURCE / 'container-lock.json').read_text())
    requirements = SOURCE / 'requirements.lock'
    if hashlib.sha256(requirements.read_bytes()).hexdigest() != lock['requirementsSha256']:
        raise RuntimeError('Dependency lock changed; review and update its checksum first')
    DEST.mkdir(parents=True, exist_ok=True)
    archive = DEST / 'source.tar.gz'
    cached = ROOT / '.oryxos-dev/mem0-source/source.tar.gz'
    if not archive.exists():
        if cached.exists():
            shutil.copyfile(cached, archive)
        else:
            subprocess.run(['curl', '-fLsS', '--max-time', '60', lock['sourceUrl'], '-o', str(archive)], check=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sourceSha256']:
        raise RuntimeError('Source checksum mismatch; no source was extracted')
    with tarfile.open(archive) as tar:
        prefix = 'mem0-' + lock['sourceCommit'] + '/'
        for member in tar.getmembers():
            if member.isdir() and member.name == prefix.rstrip('/'):
                continue
            if not member.name.startswith(prefix):
                raise RuntimeError('Unexpected archive prefix')
            relative = Path(member.name[len(prefix):])
            if not relative.parts or (relative.parts[0] != 'server' and str(relative) != 'LICENSE'):
                continue
            if relative.name in ('AGENTS.md', 'CLAUDE.md'):
                continue
            if '..' in relative.parts or relative.is_absolute() or member.issym() or member.islnk():
                raise RuntimeError('Unsafe source path')
            if member.isfile():
                target = DEST / ('LICENSE.mem0' if str(relative) == 'LICENSE' else relative)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(member).read())
    for name in ('requirements.lock', 'Dockerfile', '.dockerignore'):
        shutil.copyfile(SOURCE / name, DEST / name)
    if not (DEST / 'server/alembic.ini').is_file():
        raise RuntimeError('Pinned server migrations are missing')
    print('Pinned source and dependency checksum verified; prepared:', DEST)
    if not build:
        return
    # Do not inherit foreign Docker contexts, credentials, Java options or project secrets.
    env = {key: os.environ[key] for key in ('HOME', 'USER', 'LOGNAME') if key in os.environ}
    env.update(PATH='/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin', LANG='en_US.UTF-8', TMPDIR=str(ROOT / '.oryxos-dev/tmp'))
    docker_config = ROOT / '.oryxos-dev/docker'
    docker_config.mkdir(parents=True, exist_ok=True)
    config = docker_config / 'config.json'
    plugins = Path('/Applications/Docker.app/Contents/Resources/cli-plugins')
    if not config.exists() and plugins.is_dir():
        config.write_text(json.dumps({'cliPluginsExtraDirs': [str(plugins)]}))
    env['DOCKER_CONFIG'] = str(docker_config)
    socket = Path.home() / '.docker/run/docker.sock'
    env['DOCKER_HOST'] = os.environ.get('ORYXOS_DOCKER_HOST', 'unix://' + str(socket) if socket.exists() else 'unix:///var/run/docker.sock')
    subprocess.run(['docker', 'version', '--format', '{{.Server.Version}}'], env=env, check=True)
    subprocess.run(['docker', 'buildx', 'build', '--platform', lock['platform'], '--load',
                    '--metadata-file', str(DEST / 'metadata.json'), '--iidfile', str(DEST / 'image.id'),
                    '--tag', 'oryxos-personal/mem0:2.2.1-94c3fe9', str(DEST)], env=env, check=True)
    image_id = (DEST / 'image.id').read_text().strip()
    inspected = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', image_id], env=env, text=True).strip()
    metadata = json.loads((DEST / 'metadata.json').read_text())
    if image_id != inspected or metadata.get('containerimage.config.digest') != image_id or not metadata.get('containerimage.digest', '').startswith('sha256:'):
        raise RuntimeError('Image digest verification failed')
    (DEST / 'image.env').write_text('ORYXOS_MEM0_IMAGE=' + image_id + '\n')
    (DEST / 'verified-image.json').write_text(json.dumps({'sourceCommit': lock['sourceCommit'], 'requirementsSha256': lock['requirementsSha256'], 'imageId': image_id, 'manifestDigest': metadata['containerimage.digest']}, indent=2) + '\n')
    print('Verified local image ID:', image_id)
    print('BuildKit image manifest digest:', metadata['containerimage.digest'])
    print('Evidence remains local. No image was pushed and no container was started.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--build', action='store_true', help='Requires an already available Docker daemon')
    args = parser.parse_args()
    run(args.build)
