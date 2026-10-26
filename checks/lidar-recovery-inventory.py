"""Recovery must select durable work despite interrupted newer attempts."""
import runpy
import unittest
from tools.lidar_pipeline.recovery_inventory import inventory
from tools.lidar_pipeline.state import key

MemoryRemote = runpy.run_path('checks/lidar-remote.py')['MemoryRemote']


class Inventory(unittest.TestCase):
    def test_paginated_selection_retains_old_durable_after_new_failure(self):
        remote = MemoryRemote(); run = key({'test': 1})
        remote.record(run, 'tasks', 'selected', dict(name='selected', run=run,
            state='durable', signature='1'*64, finished=2,
            remote=dict(sha256='2'*64, bytes=100)))
        remote.record(run, 'tasks', 'selected', dict(name='selected',
            state='failed', started=3, finished=4))
        remote.record(run, 'tasks', 'selected-pending', dict(name='selected-pending', state='running', started=3))
        unrelated = f'runs/{run}/tasks/unrelated/' + '0'*64
        remote.objects[unrelated] = b'corrupt unrelated record'
        result = inventory(remote, run, ['selected'])
        self.assertEqual(result['restore_names'], ['selected'])
        self.assertEqual(result['tasks']['selected']['latest_attempt']['state'], 'failed')
        self.assertEqual(result['tasks']['selected']['durable']['finished'], 2)
        self.assertNotIn(unrelated, remote.reads)
        self.assertGreater(result['pages'], 1)

    def test_corrupt_selected_record_and_stuck_cursor_rejected(self):
        remote = MemoryRemote(); run = key({'test': 1})
        remote.objects[f'runs/{run}/tasks/selected/' + '0'*64] = b'corrupt'
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            inventory(remote, run, ['selected'])
        with self.assertRaisesRegex(ValueError, 'safe task prefixes'):
            inventory(remote, run, [''])

    def test_equal_timestamp_conflicting_generations_rejected(self):
        remote = MemoryRemote(); run = key({'test': 1})
        for signature in ['1'*64, '3'*64]:
            remote.record(run, 'tasks', 'selected', dict(name='selected', run=run,
                state='durable', signature=signature, finished=2,
                remote=dict(sha256='2'*64, bytes=100)))
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            inventory(remote, run, ['selected'])


if __name__ == '__main__':
    unittest.main()
