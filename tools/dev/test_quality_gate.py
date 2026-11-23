"""Acceptance cases for the quality baseline gate (standard library only)."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('quality-gate.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

MAIN = 'mod/src/main/java/a/B.java'
TEST = 'mod/src/test/java/a/BTest.java'


class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.repo = Path(self.directory.name)
        for source in (MAIN, TEST):
            (self.repo / source).parent.mkdir(parents=True)
            (self.repo / source).write_text('class B {}')
        (self.repo / 'mod/target').mkdir()
        self.checkstyle([])
        self.pmd([])
        self.spotbugs([])

    def tearDown(self):
        self.directory.cleanup()

    def checkstyle(self, errors):
        body = ''.join(f'<file name="{self.repo / f}"><error line="3" severity="{s}" message="m" source="com.puppycrawl.tools.checkstyle.checks.{r}"/></file>'
                       for f, r, s in errors)
        (self.repo / 'mod/target/checkstyle-result.xml').write_text(f'<checkstyle version="14.4.0">{body}</checkstyle>')

    def pmd(self, violations, extra=''):
        body = ''.join(f'<file name="{self.repo / f}"><violation beginline="7" rule="{r}" ruleset="r" priority="3">bad</violation></file>'
                       for f, r in violations)
        (self.repo / 'mod/target/pmd.xml').write_text(f'<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0">{body}{extra}</pmd>')

    def spotbugs(self, types):
        bugs = ''.join(f'<BugInstance type="{t}" priority="1" category="SECURITY"><ShortMessage>s</ShortMessage>'
                       f'<SourceLine classname="a.B" start="9" sourcepath="a/B.java"/></BugInstance>' for t in types)
        (self.repo / 'mod/target/spotbugsXml.xml').write_text(
            f'<BugCollection><Project><SrcDir>{self.repo / "mod/src/main/java"}</SrcDir></Project>{bugs}</BugCollection>')

    def baseline(self, entries):
        (self.repo / 'config/quality').mkdir(parents=True, exist_ok=True)
        (self.repo / gate.BASELINE).write_text(json.dumps(
            {'schema': 1, 'entries': [{'tool': t, 'rule': r, 'file': f} for t, r, f in entries]}))

    def test_new_triple_fails_with_gate_number_and_location(self):
        self.pmd([(MAIN, 'EmptyCatchBlock')])
        self.spotbugs(['COMMAND_INJECTION'])
        with self.assertRaises(ValueError) as error:
            gate.check(self.repo, ['mod'])
        message = str(error.exception)
        self.assertTrue(message.startswith('Quality gate FAIL: G3(pmd) 1 new, G4(spotbugs) 1 new'))
        self.assertIn(f'pmd EmptyCatchBlock {MAIN}:7 bad', message)
        self.assertIn(f'spotbugs COMMAND_INJECTION {MAIN}:9', message)

    def test_baselined_triple_and_test_code_only_report(self):
        self.pmd([(MAIN, 'EmptyCatchBlock'), (TEST, 'SystemPrintln')])
        self.baseline([('pmd', 'EmptyCatchBlock', MAIN)])
        self.assertEqual((1, 2), gate.check(self.repo, ['mod']))

    def test_checkstyle_info_reports_but_warning_blocks(self):
        self.checkstyle([(MAIN, 'javadoc.MissingJavadocTypeCheck', 'info')])
        gate.check(self.repo, ['mod'])
        self.checkstyle([(MAIN, 'naming.MemberNameCheck', 'warning')])
        with self.assertRaisesRegex(ValueError, 'G2\\(checkstyle\\) 1 new'):
            gate.check(self.repo, ['mod'])

    def test_missing_report_fails_closed_unless_tool_not_selected(self):
        (self.repo / 'mod/target/spotbugsXml.xml').unlink()
        with self.assertRaisesRegex(ValueError, 'G6 expected spotbugs report is missing'):
            gate.check(self.repo, ['mod'])
        gate.check(self.repo, ['mod'], ['pmd', 'checkstyle'])

    def test_pmd_processing_error_fails(self):
        self.pmd([], f'<error filename="{self.repo / MAIN}" msg="ParseException"/>')
        with self.assertRaisesRegex(ValueError, 'G3 PMD processing error'):
            gate.check(self.repo, ['mod'])

    def test_suppression_without_reason_or_date_fails(self):
        (self.repo / 'config/checkstyle').mkdir(parents=True)
        path = self.repo / 'config/checkstyle/suppressions.xml'
        path.write_text('<suppressions><!-- reason: generated --><suppress files="X" checks="Y"/></suppressions>')
        with self.assertRaisesRegex(ValueError, 'lacks reason'):
            gate.check(self.repo, ['mod'])
        path.write_text('<suppressions><!-- reason: generated; registered: 2026-11-21 --><suppress files="X" checks="Y"/></suppressions>')
        gate.check(self.repo, ['mod'])
        (self.repo / 'config/pmd').mkdir(parents=True)
        (self.repo / 'config/pmd/exclude-pmd.properties').write_text('# reason: cli output\na.B=SystemPrintln\n')
        with self.assertRaisesRegex(ValueError, 'exclude-pmd.properties'):
            gate.check(self.repo, ['mod'])

    def test_registered_pmd_exclusion_drops_finding(self):
        self.pmd([(MAIN, 'SystemPrintln')])
        (self.repo / 'config/pmd').mkdir(parents=True)
        (self.repo / 'config/pmd/exclude-pmd.properties').write_text(
            '# reason: cli output\n# registered: 2026-11-21\na.B=SystemPrintln\n')
        self.assertEqual((0, 0), gate.check(self.repo, ['mod']))

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.repo, check=True, capture_output=True)

    def test_baseline_check_shrink_and_prune(self):
        self.git('init', '-q')
        self.git('-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '--allow-empty', '-m', 'empty')
        options = type('O', (), {'init': False, 'prune': False, 'check_shrink': 'HEAD'})
        self.baseline([('pmd', 'EmptyCatchBlock', MAIN)])
        gate.baseline_command(self.repo, options)
        self.git('add', gate.BASELINE)
        self.git('-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '-m', 'baseline')
        self.baseline([('pmd', 'EmptyCatchBlock', MAIN), ('pmd', 'SystemPrintln', MAIN)])
        with self.assertRaisesRegex(ValueError, 'G6 baseline grew'):
            gate.baseline_command(self.repo, options)
        (self.repo / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>mod</module></modules></project>')
        self.pmd([(MAIN, 'SystemPrintln')])
        options.check_shrink, options.prune = None, True
        gate.baseline_command(self.repo, options)
        entries = json.loads((self.repo / gate.BASELINE).read_text())['entries']
        self.assertEqual([{'tool': 'pmd', 'rule': 'SystemPrintln', 'file': MAIN}], entries)

    def test_prune_keeps_creation_metadata_and_skips_noop_rewrite(self):
        (self.repo / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>mod</module></modules></project>')
        (self.repo / 'config/quality').mkdir(parents=True)
        path = self.repo / gate.BASELINE
        path.write_text(json.dumps({'schema': 1, 'createdAt': '2000-01-01', 'baseCommit': 'abc123', 'entries': [
            {'tool': 'pmd', 'rule': 'EmptyCatchBlock', 'file': MAIN}, {'tool': 'pmd', 'rule': 'SystemPrintln', 'file': MAIN}]}))
        options = type('O', (), {'init': False, 'prune': True, 'check_shrink': None})
        self.pmd([(MAIN, 'EmptyCatchBlock'), (MAIN, 'SystemPrintln')])
        before = path.read_bytes()
        gate.baseline_command(self.repo, options)
        self.assertEqual(before, path.read_bytes())
        self.pmd([(MAIN, 'SystemPrintln')])
        gate.baseline_command(self.repo, options)
        data = json.loads(path.read_text())
        self.assertEqual(('2000-01-01', 'abc123'), (data['createdAt'], data['baseCommit']))
        self.assertEqual([{'tool': 'pmd', 'rule': 'SystemPrintln', 'file': MAIN}], data['entries'])


if __name__ == '__main__':
    unittest.main()
