#!/usr/bin/env python3
"""Check required classes AND methods from this invocation, using Surefire run IDs."""
import xml.etree.ElementTree as ET


def check(repo, expected, run_id):
    count = 0
    for test in expected:
        report = repo / test['module'] / 'target/surefire-reports' / ('TEST-' + test['class'] + '.xml')
        if not report.is_file():
            raise ValueError('Missing required report: ' + test['class'])
        suite = ET.parse(report).getroot()
        properties = {p.get('name'): p.get('value') for p in suite.findall('properties/property')}
        if properties.get('oryxos.test.runId') != run_id:
            raise ValueError('Stale report: ' + test['class'])
        cases = suite.findall('testcase')
        for method in test['methods']:
            matches = [c for c in cases if c.get('name') == method and c.get('classname') == test['class']]
            if not matches or any(list(c.findall('skipped')) + list(c.findall('failure')) + list(c.findall('error')) for c in matches):
                raise ValueError('Required case missing/skipped/failed: ' + test['class'] + '#' + method)
            count += len(matches)
    print(f'Test evidence PASS: {count} required cases; run {run_id}')
