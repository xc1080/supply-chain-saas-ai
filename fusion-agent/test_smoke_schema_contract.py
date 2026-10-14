"""The boundary smoke must not certify an old, partial, or failed schema."""
import unittest
from unittest.mock import patch

import smoke_engineering_boundaries as boundaries


class SchemaContractTests(unittest.TestCase):
    def test_expected_versions_follow_the_declared_source(self):
        declaration = 'new CommerceSchemaMigrator(source).migrate(Arrays.asList(' + ','.join(
            f'Migration.resources({version}, "migration-{version}")' for version in range(1, 19)
        ) + '));'
        with patch.object(boundaries.Path, 'read_text', return_value=declaration):
            self.assertEqual(boundaries.expected_schema_versions(), list(range(1, 19)))

    def test_only_the_complete_applied_schema_passes(self):
        expected = list(range(1, 18))
        schema = [{'version': version, 'state': 'APPLIED'} for version in expected]
        self.assertTrue(boundaries.migrations_applied(schema, expected))
        self.assertFalse(boundaries.migrations_applied(schema[:11], expected))
        self.assertFalse(boundaries.migrations_applied(schema + [schema[-1]], expected))
        self.assertFalse(boundaries.migrations_applied(schema + [{'version': 18, 'state': 'APPLIED'}], expected))
        failed = [dict(row) for row in schema]
        failed[-1]['state'] = 'FAILED'
        self.assertFalse(boundaries.migrations_applied(failed, expected))
        self.assertFalse(boundaries.migrations_applied([None], expected))
        self.assertFalse(boundaries.migrations_applied(None, expected))


if __name__ == '__main__':
    unittest.main()
