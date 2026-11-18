"""Negative acceptance cases for the test evidence gate (standard library only)."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('reports', Path(__file__).with_name('check-test-reports.py'))
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class ReportGateTest(unittest.TestCase):
    def test_missing_it_skipped_stale_and_missing_method_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / 'module/target/surefire-reports'
            folder.mkdir(parents=True)
            test = {'module': 'module', 'class': 'ExampleIT', 'methods': ['required']}
            (folder / 'TEST-ExampleTest.xml').write_text('<testsuite tests="1"/>')
            with self.assertRaises(ValueError):
                reports.check(root, [test], 'current')
            report = folder / 'TEST-ExampleIT.xml'
            for run, name, child in [('current', 'required', '<skipped/>'), ('old', 'required', ''), ('current', 'another', '')]:
                report.write_text(f'<testsuite><properties><property name="oryxos.test.runId" value="{run}"/></properties><testcase classname="ExampleIT" name="{name}">{child}</testcase></testsuite>')
                with self.assertRaises(ValueError):
                    reports.check(root, [test], 'current')


if __name__ == '__main__':
    unittest.main()
