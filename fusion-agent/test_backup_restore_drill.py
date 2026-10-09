import tempfile
from pathlib import Path
import unittest
from backup_restore_drill import safe_restore_target, row_fingerprint

class BackupRestoreGuardsTest(unittest.TestCase):
    def test_restore_destination_cannot_be_live_or_external(self):
        for value in ("ksdatabase","ksdatabase_studio","mysql","verify_backup_existing","verify_backup_"+"a"*20+";DROP DATABASE ksdatabase"):
            with self.assertRaises(ValueError):
                safe_restore_target(value)
        safe_restore_target("verify_backup_"+"a"*20)
    def test_fingerprint_detects_row_changes_without_reporting_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"fixture.sql"
            path.write_bytes(b"CREATE TABLE data;\nINSERT INTO "+b"\x60data\x60 VALUES (1,'private');\n")
            first=row_fingerprint(path)
            path.write_bytes(b"INSERT INTO "+b"\x60data\x60 VALUES (2,'private');\n")
            self.assertNotEqual(first["sha256"],row_fingerprint(path)["sha256"])
            self.assertEqual(first["rows"],1)
            self.assertNotIn("private",str(first))
