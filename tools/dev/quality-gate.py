#!/usr/bin/env python3
"""Fail-closed quality baseline gate over Checkstyle, PMD and SpotBugs XML reports (standard library only).

A finding blocks when it is in main code, has blocking severity and its (tool, rule, file) triple is
not registered in config/quality/baseline.json. The baseline may only shrink.
"""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

TOOLS = ('checkstyle', 'pmd', 'spotbugs')
GATES = {'checkstyle': 'G2', 'pmd': 'G3', 'spotbugs': 'G4'}
REPORTS = {'checkstyle': 'target/checkstyle-result.xml', 'pmd': 'target/pmd.xml', 'spotbugs': 'target/spotbugsXml.xml'}
BASELINE = 'config/quality/baseline.json'
SUPPRESSIONS = ('config/checkstyle/suppressions.xml', 'config/spotbugs/exclude.xml',
                'config/dependency-check/suppressions.xml', 'config/pmd/exclude-pmd.properties')
DATE = re.compile(r'registered:\s*\d{4}-\d{2}-\d{2}')
REASON = re.compile(r'reason:\s*\S')


class Finding:
    def __init__(self, tool, rule, file, line, message, blocking_severity=True):
        self.tool, self.rule, self.file, self.line, self.message = tool, rule, file, line, ' '.join(message.split())
        self.scope = 'test' if '/src/test/' in '/' + file else 'main'
        self.blocking_severity = blocking_severity

    def key(self):
        return self.tool, self.rule, self.file

    def __str__(self):
        return f'{self.tool} {self.rule} {self.file}:{self.line} {self.message}'


def local(tag):
    return tag.rsplit('}', 1)[-1]


def relative(repo, path):
    try:
        return Path(path).resolve().relative_to(repo.resolve()).as_posix()
    except ValueError:
        return Path(path).as_posix()


def expected(repo, module, tool):
    main = (repo / module / 'src/main/java').is_dir()
    return main if tool == 'spotbugs' else main or (repo / module / 'src/test/java').is_dir()


def parse(repo, tool, report):
    root = ET.parse(report).getroot()
    findings = []
    if tool == 'checkstyle':
        for file in root.iter('file'):
            for error in file.iter('error'):
                severity = error.get('severity', 'error')
                if severity == 'ignore':
                    continue
                findings.append(Finding(tool, error.get('source', 'unknown').rsplit('.', 1)[-1], relative(repo, file.get('name')),
                                        error.get('line', '0'), error.get('message', ''), severity in ('warning', 'error')))
    elif tool == 'pmd':
        for node in root.iter():
            if local(node.tag) == 'error':
                raise ValueError(f'G3 PMD processing error in {relative(repo, node.get("filename", "?"))}: {node.get("msg", "")}')
            if local(node.tag) == 'file':
                for violation in node:
                    if local(violation.tag) == 'violation':
                        findings.append(Finding(tool, violation.get('rule'), relative(repo, node.get('name')),
                                                violation.get('beginline', '0'), violation.text or ''))
    else:
        roots = [Path(e.text) for e in root.iter('SrcDir') if e.text]
        for bug in root.iter('BugInstance'):
            line = bug.find('SourceLine')
            if line is None:
                line = bug.find('Class/SourceLine')
            source = line.get('sourcepath', '?') if line is not None else '?'
            match = next((d / source for d in roots if (d / source).is_file()), Path(source))
            message = bug.findtext('ShortMessage') or bug.findtext('LongMessage') or bug.get('category', '')
            findings.append(Finding(tool, bug.get('type'), relative(repo, match),
                                    line.get('start', '0') if line is not None else '0', message))
    return findings


def pmd_exclusions(repo):
    path = repo / 'config/pmd/exclude-pmd.properties'
    excluded = {}
    if path.is_file():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                name, rules = line.split('=', 1)
                excluded[name.strip()] = {r.strip() for r in rules.split(',')}
    return excluded


def class_of(file):
    for root in ('/src/main/java/', '/src/test/java/'):
        if root in '/' + file:
            return ('/' + file).split(root, 1)[1].removesuffix('.java').replace('/', '.')
    return None


def collect(repo, modules, tools):
    excluded = pmd_exclusions(repo)
    return [f for f in collect_reports(repo, modules, tools)
            if not (f.tool == 'pmd' and f.rule in excluded.get(class_of(f.file), ()))]


def collect_reports(repo, modules, tools):
    findings = []
    for module in modules:
        for tool in tools:
            report = repo / module / REPORTS[tool]
            if report.is_file():
                findings.extend(parse(repo, tool, report))
            elif expected(repo, module, tool):
                raise ValueError(f'G6 expected {tool} report is missing: {module}/{REPORTS[tool]}')
    return findings


def load_baseline(text):
    data = json.loads(text)
    if data.get('schema') != 1:
        raise ValueError('G6 unsupported baseline schema')
    return {(e['tool'], e['rule'], e['file']) for e in data['entries']}


def write_baseline(repo, entries):
    head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=repo, capture_output=True, text=True).stdout.strip()
    data = {'schema': 1, 'createdAt': datetime.date.today().isoformat(), 'baseCommit': head,
            'entries': [{'tool': t, 'rule': r, 'file': f} for t, r, f in sorted(entries, key=lambda e: (e[0], e[2], e[1]))]}
    path = repo / BASELINE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')


def check_suppressions(repo):
    problems = []
    for name in SUPPRESSIONS:
        path = repo / name
        if not path.is_file():
            continue
        if path.suffix == '.properties':
            notes = ''
            for raw in path.read_text().splitlines():
                line = raw.strip()
                if line.startswith('#'):
                    notes += line + '\n'
                elif line:
                    if not (REASON.search(notes) and DATE.search(notes)):
                        problems.append(f'{name}: entry "{line}" lacks "# reason:" and "# registered: YYYY-MM-DD"')
                    notes = ''
                else:
                    notes = ''
            continue
        root = ET.parse(path, ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))).getroot()
        entry = 'Match' if 'spotbugs' in name else 'suppress'
        for parent in root.iter():
            previous = None
            for child in parent:
                if child.tag is ET.Comment:
                    previous = child.text or ''
                    continue
                if local(child.tag) == entry:
                    notes = (previous or '') + ' '.join(n.text or '' for n in child.iter() if local(str(n.tag)) == 'notes')
                    if not (REASON.search(notes) and DATE.search(notes)):
                        problems.append(f'{name}: <{entry}> near "{ET.tostring(child, encoding="unicode")[:80]}" lacks reason/registered date')
                previous = None
    if problems:
        raise ValueError('G6 suppressions without reason:\n' + '\n'.join(problems))


def check(repo, modules, tools=TOOLS):
    check_suppressions(repo)
    findings = collect(repo, modules, tools)
    path = repo / BASELINE
    baseline = load_baseline(path.read_text()) if path.is_file() else set()
    blocking = [f for f in findings if f.scope == 'main' and f.blocking_severity and f.key() not in baseline]
    if blocking:
        counts = {}
        for f in blocking:
            counts[f.tool] = counts.get(f.tool, 0) + 1
        summary = ', '.join(f'{GATES[t]}({t}) {n} new' for t, n in sorted(counts.items(), key=lambda i: GATES[i[0]]))
        raise ValueError(f'Quality gate FAIL: {summary}\n' + '\n'.join(str(f) for f in blocking))
    print(f'Quality gate PASS: {len(baseline)} baseline entries, {len(findings)} report-only findings')
    return len(baseline), len(findings)


def main_entries(findings):
    return {f.key() for f in findings if f.scope == 'main' and f.blocking_severity}


def modules_of(repo):
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    return [e.text for e in ET.parse(repo / 'pom.xml').findall('m:modules/m:module', ns)]


def baseline_command(repo, options):
    path = repo / BASELINE
    if options.init:
        entries = main_entries(collect(repo, modules_of(repo), TOOLS))
        write_baseline(repo, entries)
        print(f'Baseline initialized: {len(entries)} entries')
    elif options.prune:
        current = load_baseline(path.read_text())
        kept = current & main_entries(collect(repo, modules_of(repo), TOOLS))
        write_baseline(repo, kept)
        print(f'Baseline pruned: {len(current) - len(kept)} removed, {len(kept)} kept')
    else:
        shown = subprocess.run(['git', 'show', f'{options.check_shrink}:{BASELINE}'], cwd=repo, capture_output=True, text=True)
        current = load_baseline(path.read_text()) if path.is_file() else set()
        if shown.returncode:
            print(f'No baseline on {options.check_shrink}; accepting initial baseline with {len(current)} entries')
            return
        added = sorted(current - load_baseline(shown.stdout))
        if added:
            raise ValueError(f'G6 baseline grew against {options.check_shrink}:\n' + '\n'.join(' '.join(e) for e in added))
        print(f'Baseline did not grow against {options.check_shrink}: {len(current)} entries')


def main(argv):
    parser = argparse.ArgumentParser(prog='quality-gate')
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    commands = parser.add_subparsers(dest='command', required=True)
    gate = commands.add_parser('check')
    gate.add_argument('--tools', default=','.join(TOOLS))
    gate.add_argument('--modules')
    baseline = commands.add_parser('baseline')
    mode = baseline.add_mutually_exclusive_group(required=True)
    mode.add_argument('--init', action='store_true')
    mode.add_argument('--prune', action='store_true')
    mode.add_argument('--check-shrink', metavar='REF')
    options = parser.parse_args(argv)
    repo = options.repo.resolve()
    if options.command == 'check':
        tools = options.tools.split(',')
        if any(t not in TOOLS for t in tools):
            raise ValueError('Unknown tool in --tools: ' + options.tools)
        check(repo, options.modules.split(',') if options.modules else modules_of(repo), tools)
    else:
        baseline_command(repo, options)


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
