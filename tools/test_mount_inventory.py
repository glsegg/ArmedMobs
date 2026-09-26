"""Focused, asset-independent tests: python tools/test_mount_inventory.py [inventory.json]."""
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest

import mount_inventory as inventory


REPORT = Path(sys.argv.pop(1)) if len(sys.argv) > 1 else None


def model(bones, scale=(0.6, 0.6, 0.6)):
    bones = {bone['name']: bone for bone in bones}
    result = {'anchors': {name: inventory.chain_details(bones, name)
                          for name in ('thirdperson_hand', 'muzzle_flash', 'muzzle_pos')}, 'issues': []}
    result['mounted'] = inventory.mounted_details(result, list(scale))
    return result


class InventoryTransformTests(unittest.TestCase):
    def close_vector(self, actual, expected):
        self.assertEqual(len(actual), len(expected))
        for a, b in zip(actual, expected):
            self.assertAlmostEqual(a, b, places=10)

    def test_pivots_use_parent_deltas_and_root_24_pixel_origin(self):
        result = model([{'name': 'root', 'pivot': [4, 8, 12]},
                        {'name': 'thirdperson_hand', 'parent': 'root', 'pivot': [8, 12, 20]}])
        hand = result['anchors']['thirdperson_hand']
        self.close_vector(hand['origin_model_blocks'], [0.5, 0.75, 1.25])
        self.close_vector(hand['path_root_first'][1]['tacz_local_position_pixels'], [4, -4, 8])
        self.close_vector(result['mounted']['hand_origin_item_blocks'], [0.5, 0.5, 0.5])

    def test_ancestor_rotation_changes_child_origin(self):
        result = model([{'name': 'root', 'pivot': [0, 0, 0], 'rotation': [0, 0, 90]},
                        {'name': 'thirdperson_hand', 'parent': 'root', 'pivot': [16, 0, 0]}])
        self.close_vector(result['anchors']['thirdperson_hand']['origin_model_blocks'], [0, 2.5, 0])
        self.close_vector(result['mounted']['hand_origin_item_blocks'], [0.5, 0.5, 0.5])

    def test_nested_all_axis_rotations_are_cancelled_in_reverse_order(self):
        result = model([{'name': 'root', 'pivot': [-31, 24, 57], 'rotation': [20, -35, 80]},
                        {'name': 'parent', 'parent': 'root', 'pivot': [12, -8, 11], 'rotation': [35, 15, -60]},
                        {'name': 'thirdperson_hand', 'parent': 'parent', 'pivot': [4, 5, -3], 'rotation': [10, 5, 50]}])
        mounted = result['mounted']
        self.close_vector(mounted['hand_origin_item_blocks'], [0.5, 0.5, 0.5])
        expected = [[-0.6, 0, 0, 0.5], [0, -0.6, 0, 0.5], [0, 0, 0.6, 0.5], [0, 0, 0, 1]]
        for actual, row in zip(mounted['hand_matrix_item_blocks_row_major'], expected):
            self.close_vector(actual, row)

    def test_muzzle_flash_and_attachment_point_remain_separate(self):
        result = model([{'name': 'thirdperson_hand', 'pivot': [0, 4.225, -0.35]},
                        {'name': 'muzzle_flash', 'pivot': [0.00625, 5.35, -7.05]},
                        {'name': 'muzzle_pos', 'pivot': [0.00625, 5.35, -5.05]}])
        muzzles = result['mounted']['muzzles']
        self.close_vector(muzzles['muzzle_flash']['hand_to_muzzle_item_blocks'], [-0.000234375, 0.0421875, -0.25125])
        self.close_vector(muzzles['muzzle_pos']['hand_to_muzzle_item_blocks'], [-0.000234375, 0.0421875, -0.17625])
        self.close_vector(muzzles['muzzle_flash']['negative_local_z_direction_item_normalized'], [0, 0, -1])

    def test_lod_uses_own_anchor_and_common_translation_cancels(self):
        variants = [model([{'name': 'thirdperson_hand', 'pivot': [shift, 4 + shift, 1 + shift]},
                           {'name': 'muzzle_flash', 'pivot': [shift, 5 + shift, -7 + shift]}]) for shift in (0, 28)]
        for result in variants:
            self.close_vector(result['mounted']['hand_origin_item_blocks'], [0.5, 0.5, 0.5])
            self.close_vector(result['mounted']['muzzles']['muzzle_flash']['hand_to_muzzle_item_blocks'], [0, 0.0375, -0.3])
        self.assertNotEqual(variants[0]['mounted']['wrapper_matrix_blocks_row_major'],
                            variants[1]['mounted']['wrapper_matrix_blocks_row_major'])

    def test_cycle_and_missing_parent_are_explicit_errors(self):
        cyclic = {'a': {'name': 'a', 'parent': 'b'}, 'b': {'name': 'b', 'parent': 'a'}}
        self.assertEqual(inventory.chain_details(cyclic, 'a')['error'], 'parent_cycle')
        self.assertEqual(inventory.chain_details({'a': {'name': 'a', 'parent': 'gone'}}, 'a')['error'], 'missing_parent')
        self.assertEqual(inventory.chain_details({}, 'a')['error'], 'missing_bone')

    def test_bad_vectors_and_scales_are_reported(self):
        for value in ([0, 0], [True, 0, 0], [0, float('nan'), 0], ['0', 0, 0]):
            with self.subTest(value=value):
                self.assertIn('error', inventory.chain_details({'a': {'name': 'a', 'pivot': value}}, 'a'))
        for scale in ([1, 0, 1], [-1, 1, 1], [1, math.inf, 1]):
            with self.subTest(scale=scale):
                self.assertIn('error', model([{'name': 'thirdperson_hand', 'pivot': [0, 0, 0]}], scale)['mounted'])

    def test_muzzle_missing_does_not_invalidate_hand_normalization(self):
        result = model([{'name': 'thirdperson_hand', 'pivot': [0, 4, 0]}])
        result['issues'] = [{'code': 'missing_bone', 'anchor': 'muzzle_flash'}]
        report = inventory.mount_audit([{'id': 'test:gun', 'model': result, 'lod_model': result}],
                                       [{'id': 'test', 'allowed_ids': ['test:gun']}])
        coverage = report['automatic_origin_coverage'][0]
        self.assertTrue(coverage['all_allowed_static_origins_normalize'])
        self.assertFalse(coverage['all_muzzle_flash_anchors_available'])
        self.assertEqual(len(coverage['unavailable_muzzle_flash_anchors']), 2)
        self.assertTrue(all(issue['severity'] == 'warning' for issue in report['exceptions']))

    def test_jsonc_literals_survive_comments_and_trailing_commas(self):
        self.assertEqual(inventory.jsonc('{/*comment*/"a":"literal,}","b":[1,//line\n2,],}'),
                         {'a': 'literal,}', 'b': [1, 2]})

    def test_duplicate_bones_and_read_only_model_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.json'
            data = {'format_version': '1.12.0', 'minecraft:geometry': [{'bones': [
                {'name': 'thirdperson_hand', 'pivot': [0, 0, 0]},
                {'name': 'thirdperson_hand', 'pivot': [1, 2, 3]}]}]}
            path.write_text(json.dumps(data), encoding='utf-8')
            before = path.read_bytes()
            result = inventory.model_details(path)
            self.assertEqual(path.read_bytes(), before)
            self.assertIn('duplicate_bone_names', [issue['code'] for issue in result['issues']])
            self.assertEqual(result['source_sha256'], inventory.hashlib.sha256(before).hexdigest())

    @unittest.skipUnless(REPORT, 'Pass inventory.json to also validate the selected real gun pack.')
    def test_selected_pack_report(self):
        report = json.loads(REPORT.read_text(encoding='utf-8'))
        self.assertEqual(report['schema_version'], 2)
        self.assertTrue(report['mount_audit']['automatic_origin_coverage'])
        for coverage in report['mount_audit']['automatic_origin_coverage']:
            with self.subTest(context=coverage['context']):
                self.assertTrue(coverage['complete_main_and_lod'])
                self.assertTrue(coverage['all_allowed_static_origins_normalize'])
                self.assertEqual(coverage['normalized_model_variants'], 2 * coverage['allowed_count'])
                self.assertFalse(coverage['blocking_exceptions'])
        for gun in report['guns']:
            for variant in ('model', 'lod_model'):
                with self.subTest(gun=gun['id'], variant=variant):
                    details = gun[variant]
                    self.assertIsNotNone(details)
                    self.assertEqual(details['source_sha256'], inventory.hashlib.sha256(Path(details['file']).read_bytes()).hexdigest())
                    self.close_vector(details['mounted']['hand_origin_item_blocks'], [0.5, 0.5, 0.5])


if __name__ == '__main__':
    unittest.main()
