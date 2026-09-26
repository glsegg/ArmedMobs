"""Read-only TaCZ mount inventory (Python 3.11+, standard library only).

By default inspect run/client/tacz and its common config. Optional --pcl-gunpack-dir
and --pcl-config add a second instance. Only the JSON report and gun-ids.txt are
written; inputs are never edited. Gun pack directories are not tracked in Git,
so this tool is intentionally excluded from the default test suite.
"""
from pathlib import Path
import argparse
import collections
import hashlib
import json
import math
import re
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tacz-dir', type=Path, default=ROOT / 'run/client/tacz',
                        help='Project TaCZ directory, or one extracted gun-pack directory.')
    parser.add_argument('--config', type=Path, default=ROOT / 'run/client/config/tarkovscav-common.toml',
                        help='Project common TOML used for the real GunPool filters.')
    parser.add_argument('--pcl-gunpack-dir', type=Path,
                        help='Optional PCL TaCZ directory, or one extracted gun-pack directory.')
    parser.add_argument('--pcl-config', type=Path, help='Common TOML of the optional PCL instance.')
    parser.add_argument('--runtime-log', type=Path, default=ROOT / 'run/client/logs/latest.log',
                        help='Existing project log; provenance is recorded without launching the game.')
    parser.add_argument('--tacz-jar', type=Path, default=ROOT / 'libs/tacz-1.20.1-1.1.8-hotfix.jar',
                        help='Optional local TaCZ jar to inventory its bundled default pack.')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/gun-mount-audit/inventory.json',
                        help='JSON output. gun-ids.txt is written beside it, for the project instance.')
    args = parser.parse_args()
    if bool(args.pcl_gunpack_dir) != bool(args.pcl_config):
        parser.error('--pcl-gunpack-dir and --pcl-config must be supplied together')
    return args

def jsonc(text):
    # Remove comments without modifying quoted strings, then allow trailing JSON commas like Gson.
    out = []
    i = 0
    quoted = False
    while i < len(text):
        c = text[i]
        if quoted:
            out.append(c)
            if c == '\\' and i + 1 < len(text):
                i += 1
                out.append(text[i])
            elif c == '"':
                quoted = False
        elif c == '"':
            quoted = True
            out.append(c)
        elif text[i:i+2] == '//':
            end = text.find('\n', i)
            i = len(text) if end == -1 else end
            out.append('\n')
        elif text[i:i+2] == '/*':
            end = text.find('*/', i + 2)
            if end == -1:
                raise ValueError('Unterminated block comment')
            i = end + 1
        else:
            out.append(c)
        i += 1
    # Keep quoted text untouched: a literal string containing ",}" is not a trailing comma.
    clean = re.sub(r'("(?:[^"\\]|\\.)*")|,(\s*[}\]])',
                   lambda match: match[1] if match[1] is not None else match[2], ''.join(out))
    return json.loads(clean)

def read(path):
    return jsonc(path.read_text(encoding='utf-8-sig'))

def reference(pack, value, domain, directory, suffix='.json'):
    namespace, identifier = value.split(':', 1)
    if (not re.fullmatch(r'[a-z0-9_.-]+', namespace)
            or not re.fullmatch(r'[a-z0-9/._-]+', identifier)
            or any(part in ('', '.', '..') for part in identifier.split('/'))):
        raise ValueError(f'Invalid resource reference {value!r}')
    return pack / domain / namespace / directory / (identifier + suffix)

def identity():
    return [[float(row == col) for col in range(4)] for row in range(4)]


def multiply(a, b):
    return [[sum(a[row][k] * b[k][col] for k in range(4)) for col in range(4)] for row in range(4)]


def translation(vector):
    matrix = identity()
    for axis in range(3):
        matrix[axis][3] = vector[axis]
    return matrix


def scaling(vector):
    matrix = identity()
    for axis in range(3):
        matrix[axis][axis] = vector[axis]
    return matrix


def rotation(axis, degrees):
    matrix = identity()
    a, b = [(1, 2), (2, 0), (0, 1)][axis]
    cosine, sine = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    matrix[a][a] = matrix[b][b] = cosine
    matrix[a][b], matrix[b][a] = -sine, sine
    return matrix


def product(*matrices):
    result = identity()
    for matrix in matrices:
        result = multiply(result, matrix)
    return result


def vector3(value, label):
    if (not isinstance(value, list) or len(value) != 3
            or any(isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n) for n in value)):
        raise ValueError(f'{label} must contain three finite numbers')
    return list(value)


def chain_details(bones, name):
    """Rest-pose TaCZ BedrockPart transform, using actual root/child pivot conversion."""
    chain, seen = [], set()
    bone = bones.get(name)
    if bone is None:
        return {'present': False, 'path_root_first': [], 'error': 'missing_bone'}
    while bone:
        if bone['name'] in seen:
            return {'present': True, 'path_root_first': [], 'error': 'parent_cycle'}
        seen.add(bone['name'])
        chain.append(bone)
        parent = bone.get('parent')
        if parent is not None and parent not in bones:
            return {'present': True, 'path_root_first': [], 'error': 'missing_parent', 'parent': parent}
        bone = bones.get(parent)
    chain.reverse()
    matrix, result = identity(), []
    try:
        for bone in chain:
            pivot = vector3(bone.get('pivot'), bone['name'] + '.pivot')
            angles = vector3(bone.get('rotation', [0, 0, 0]), bone['name'] + '.rotation')
            parent = bones.get(bone.get('parent'))
            if parent:
                parent_pivot = vector3(parent.get('pivot'), parent['name'] + '.pivot')
                position = [pivot[0] - parent_pivot[0], parent_pivot[1] - pivot[1], pivot[2] - parent_pivot[2]]
            else:
                position = [pivot[0], 24 - pivot[1], pivot[2]]
            # BedrockModel.convertRotation: degrees -> radians, with no sign change.
            # BedrockPart.translateAndRotateAndScale: T(p/16) Rz Ry Rx.
            local = product(translation([n / 16 for n in position]),
                            rotation(2, angles[2]), rotation(1, angles[1]), rotation(0, angles[0]))
            matrix = multiply(matrix, local)
            result.append({**{key: bone[key] for key in ('name', 'parent', 'pivot', 'rotation', 'scale') if key in bone},
                           'tacz_local_position_pixels': position, 'tacz_rotation_degrees': angles,
                           'tacz_local_matrix_blocks_row_major': local})
    except ValueError as error:
        return {'present': True, 'path_root_first': result, 'error': str(error)}
    return {'present': True, 'path_root_first': result, 'matrix_blocks_row_major': matrix,
            'origin_model_blocks': [matrix[axis][3] for axis in range(3)],
            'nonzero_rotations': [{'bone': bone['name'], 'degrees': bone['tacz_rotation_degrees']}
                                  for bone in result if any(bone['tacz_rotation_degrees'])]}


def positioning_matrix(chain, scale):
    """GunItemRendererWrapper.applyPositioningNodeTransform, including reverse chain order."""
    matrix = translation([0, 1.5, 0])
    for bone in reversed(chain):
        angles, position = bone['tacz_rotation_degrees'], bone['tacz_local_position_pixels']
        offset = [-position[axis] * scale[axis] / 16 for axis in range(3)]
        if 'parent' not in bone:
            offset[1] += 1.5 * scale[1]
        matrix = product(matrix, rotation(0, -angles[0]), rotation(1, -angles[1]),
                         rotation(2, -angles[2]), translation(offset))
    return multiply(matrix, translation([0, -1.5, 0]))


def mounted_details(model, scale):
    hand = model['anchors'].get('thirdperson_hand', {})
    if 'matrix_blocks_row_major' not in hand:
        return {'error': 'unusable_thirdperson_hand'}
    try:
        scale = vector3(scale if scale is not None else [1, 1, 1], 'thirdperson scale')
        if any(value <= 0 for value in scale):
            return {'error': 'nonpositive_thirdperson_scale'}
    except ValueError as error:
        return {'error': str(error)}
    wrapper = product(translation([0.5, 2, 0.5]), scaling([-1, -1, 1]),
                      positioning_matrix(hand['path_root_first'], scale),
                      translation([0, 1.5, 0]), scaling(scale), translation([0, -1.5, 0]))
    hand_mounted = multiply(wrapper, hand['matrix_blocks_row_major'])
    hand_origin = [hand_mounted[axis][3] for axis in range(3)]
    targets = {}
    for name in ('muzzle_flash', 'muzzle_pos'):
        anchor = model['anchors'][name]
        if 'matrix_blocks_row_major' not in anchor:
            targets[name] = {'present': anchor['present'], 'error': anchor.get('error')}
            continue
        transformed = multiply(wrapper, anchor['matrix_blocks_row_major'])
        origin = [transformed[axis][3] for axis in range(3)]
        # Column-vector convention; negative Z is the model's local bore direction.
        direction = [-transformed[axis][2] for axis in range(3)]
        length = math.sqrt(sum(value * value for value in direction))
        delta = [origin[axis] - hand_origin[axis] for axis in range(3)]
        targets[name] = {'present': True, 'origin_item_blocks': origin,
                         'hand_to_muzzle_item_blocks': delta,
                         'distance_from_hand_blocks': math.sqrt(sum(value * value for value in delta)),
                         'negative_local_z_direction_item_normalized': [value / length for value in direction]}
    return {'thirdperson_scale_effective': scale, 'wrapper_matrix_blocks_row_major': wrapper,
            'hand_matrix_item_blocks_row_major': hand_mounted, 'hand_origin_item_blocks': hand_origin,
            'hand_origin_error_from_item_center_blocks': math.dist(hand_origin, [0.5, 0.5, 0.5]),
            'muzzles': targets}


def model_details(path):
    if not path.is_file():
        return {'file': str(path), 'has_thirdperson_hand': False, 'anchors': {},
                'issues': [{'code': 'missing_model_file'}]}
    model = read(path)
    geometries = model.get('minecraft:geometry') or [v for k, v in model.items() if k.startswith('geometry.')]
    # BedrockModelPOJO.getGeometryModelNew reads element zero, not merged geometries.
    raw_bones = geometries[0].get('bones', []) if geometries else []
    bones = {bone['name']: bone for bone in raw_bones}
    issues = []
    if len(geometries) != 1:
        issues.append({'code': 'multiple_or_missing_geometries', 'count': len(geometries)})
    duplicates = [name for name, count in collections.Counter(b['name'] for b in raw_bones).items() if count > 1]
    if duplicates:
        issues.append({'code': 'duplicate_bone_names', 'names': duplicates})
    anchors = {name: chain_details(bones, name) for name in ('thirdperson_hand', 'muzzle_flash', 'muzzle_pos')}
    for name, anchor in anchors.items():
        # muzzle_pos is an attachment point, not the mandatory muzzle-flash origin.
        if 'error' in anchor and (name != 'muzzle_pos' or anchor['present']):
            issues.append({'code': anchor['error'], 'anchor': name})
        for bone in anchor['path_root_first']:
            if 'scale' in bone:
                issues.append({'code': 'bone_scale_field_ignored_by_tacz_pojo', 'anchor': name, 'bone': bone['name']})
    return {'file': str(path), 'source_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'format_version': model.get('format_version'), 'geometry_count': len(geometries), 'bone_count': len(bones),
            'thirdperson_hand_path_root_first': anchors['thirdperson_hand']['path_root_first'],
            'has_thirdperson_hand': anchors['thirdperson_hand']['present'], 'anchors': anchors, 'issues': issues,
            'relevant_bones': [name for name in bones if re.search(r'(hand|grip|root|muzzle)', name, re.I)]}

def gun_entries(pack):
    result = []
    for index_path in sorted((pack / 'data').glob('*/index/guns/**/*.json')):
        index = read(index_path)
        namespace = index_path.relative_to(pack / 'data').parts[0]
        gun_id = namespace + ':' + index_path.relative_to(pack / 'data' / namespace / 'index/guns').with_suffix('').as_posix()
        data_path = reference(pack, index['data'], 'data', 'data/guns')
        display_path = reference(pack, index['display'], 'assets', 'display/guns')
        data, display = read(data_path), read(display_path)
        model_path = reference(pack, display['model'], 'assets', 'geo_models')
        lod = display.get('lod')
        entry = {
            'id': gun_id,
            'type': index.get('type', '').lower(),
            'script': data.get('script'),
            'ammo': data.get('ammo'),
            'index_file': str(index_path),
            'data_file': str(data_path),
            'display_file': str(display_path),
            'model_location': display['model'],
            'texture_location': display.get('texture'),
            'thirdperson_scale_declared': display.get('transform', {}).get('scale', {}).get('thirdperson'),
            'third_person_animation': display.get('third_person_animation'),
            'player_animator_3rd': display.get('player_animator_3rd'),
            'use_default_animation': display.get('use_default_animation'),
            'model': model_details(model_path),
            'lod_model': model_details(reference(pack, lod['model'], 'assets', 'geo_models')) if lod else None,
        }
        for variant in ('model', 'lod_model'):
            if entry[variant]:
                entry[variant]['mounted'] = mounted_details(entry[variant], entry['thirdperson_scale_declared'])
        result.append(entry)
    return result

TIERS = {'pistol': ['pistol', 'smg'], 'shotgun': ['shotgun'], 'rifle': ['rifle', 'mg'], 'sniper': ['sniper']}
def filters(config):
    guns = config.get('guns', {})
    return {key: guns.get(key, default) for key, default in {
        'gunBlacklist': ['tacz:rpg7', 'tacz:m320', 'tacz:minigun'], 'gunWhitelist': [],
        'excludedGunTypes': ['rpg'], 'excludeScriptedGuns': True,
        'trustedScriptNamespaces': ['tacz'], 'pistolClipTypes': ['pistol'],
    }.items()}

def eligibility(gun, cfg):
    reasons = []
    if gun['id'].lower() in [s.lower() for s in cfg['gunBlacklist']]: reasons.append('gunBlacklist')
    if cfg['gunWhitelist'] and gun['id'].lower() not in [s.lower() for s in cfg['gunWhitelist']]: reasons.append('not_in_gunWhitelist')
    if gun['type'] in [s.lower() for s in cfg['excludedGunTypes']]: reasons.append('excludedGunTypes')
    if cfg['excludeScriptedGuns'] and gun['script']:
        if gun['script'].split(':')[0].lower() not in [s.strip().lower() for s in cfg['trustedScriptNamespaces']]: reasons.append('untrusted_script_namespace')
    return reasons

def mount_audit(guns, contexts):
    exceptions, rotations, differences = [], [], []
    for gun in guns:
        for variant in ('model', 'lod_model'):
            model = gun[variant]
            if not model:
                exceptions.append({'id': gun['id'], 'variant': variant, 'code': 'lod_not_declared',
                                   'severity': 'info', 'note': 'TaCZ falls back to the main model.'})
                continue
            for issue in model['issues']:
                severity = 'warning' if issue.get('anchor') in ('muzzle_flash', 'muzzle_pos') else 'error'
                exceptions.append({'id': gun['id'], 'variant': variant, 'severity': severity, **issue})
            mounted = model['mounted']
            if 'error' in mounted:
                exceptions.append({'id': gun['id'], 'variant': variant, 'severity': 'error',
                                   'code': 'unusable_mount', 'detail': mounted['error']})
            elif mounted['hand_origin_error_from_item_center_blocks'] > 1e-8:
                exceptions.append({'id': gun['id'], 'variant': variant, 'severity': 'error',
                                   'code': 'hand_origin_does_not_normalize',
                                   'error_blocks': mounted['hand_origin_error_from_item_center_blocks']})
            for name, anchor in model['anchors'].items():
                if anchor.get('nonzero_rotations'):
                    rotations.append({'id': gun['id'], 'variant': variant, 'anchor': name,
                                      'rotations': anchor['nonzero_rotations']})
        if gun['model'] and gun['lod_model']:
            for name in ('muzzle_flash', 'muzzle_pos'):
                main = gun['model']['mounted'].get('muzzles', {}).get(name, {})
                lod = gun['lod_model']['mounted'].get('muzzles', {}).get(name, {})
                if 'hand_to_muzzle_item_blocks' in main and 'hand_to_muzzle_item_blocks' in lod:
                    delta = [b - a for a, b in zip(main['hand_to_muzzle_item_blocks'], lod['hand_to_muzzle_item_blocks'])]
                    if math.sqrt(sum(value * value for value in delta)) > 1e-8:
                        differences.append({'id': gun['id'], 'anchor': name,
                                            'lod_minus_main_hand_to_muzzle_item_blocks': delta,
                                            'difference_blocks': math.sqrt(sum(value * value for value in delta))})
    coverage = []
    by_id = {gun['id']: gun for gun in guns}
    for context in contexts:
        allowed = set(context['allowed_ids'])
        blockers = [issue for issue in exceptions if issue['id'] in allowed and issue['severity'] == 'error']
        muzzle_missing = [{'id': gun_id, 'variant': variant} for gun_id in sorted(allowed)
                          for variant in ('model', 'lod_model') if by_id[gun_id][variant]
                          and 'matrix_blocks_row_major' not in by_id[gun_id][variant]['anchors'].get('muzzle_flash', {})]
        main_count = sum(bool(by_id[gun_id]['model']) for gun_id in allowed)
        lod_count = sum(bool(by_id[gun_id]['lod_model']) for gun_id in allowed)
        normalized = sum(1 for gun_id in allowed for variant in ('model', 'lod_model')
                         if by_id[gun_id][variant] and
                         by_id[gun_id][variant]['mounted'].get('hand_origin_error_from_item_center_blocks', math.inf) <= 1e-8)
        coverage.append({'context': context['id'], 'allowed_count': len(allowed), 'main_models': main_count,
                         'lod_models': lod_count, 'normalized_model_variants': normalized,
                         'complete_main_and_lod': main_count == lod_count == len(allowed),
                         'all_allowed_static_origins_normalize': not blockers and normalized == main_count + lod_count,
                         'all_muzzle_flash_anchors_available': not muzzle_missing,
                         'unavailable_muzzle_flash_anchors': muzzle_missing,
                         'blocking_exceptions': blockers,
                         'nonzero_rotation_cases': [case for case in rotations if case['id'] in allowed]})
    return {'matrix_convention': 'Row-major 4x4 matrices multiplying column vectors; translations in blocks (16 model pixels = 1 block).',
            'pose_scope': 'TaCZ rest pose before dynamic animation or attachment offsets; muzzle_flash is the actual flash origin, muzzle_pos is separately reported.',
            'expected_hand_item_origin_blocks': [0.5, 0.5, 0.5], 'origin_error_tolerance_blocks': 1e-8,
            'automatic_origin_coverage': coverage, 'exceptions': exceptions, 'nonzero_rotation_cases': rotations,
            'main_lod_relative_muzzle_differences': differences,
            'interpretation': 'Origin normalization verifies the shared TaCZ positioning contract, not visual contact with a mob palm. Different barrel lengths and main/LOD geometry are not evidence that per-gun magic offsets are needed.'}


def main():
    args = arguments()
    context_inputs = [('project_dev_client', args.tacz_dir.resolve(), args.config.resolve())]
    if args.pcl_gunpack_dir:
        context_inputs.append(('pcl_forge_1_20_1', args.pcl_gunpack_dir.resolve(), args.pcl_config.resolve()))
    contexts = []
    all_guns = {}
    pack_fingerprints = set()
    for context_id, pack_directory, config_file in context_inputs:
        if not pack_directory.is_dir():
            raise ValueError(f'Gun pack directory does not exist: {pack_directory}')
        game = config_file.parent.parent
        config = tomllib.loads(config_file.read_text(encoding='utf-8-sig'))
        cfg = filters(config)
        packs = []
        local = []
        manifests = ([pack_directory / 'gunpack.meta.json'] if (pack_directory / 'gunpack.meta.json').is_file()
                     else sorted(pack_directory.glob('*/gunpack.meta.json')))
        archives = list(pack_directory.glob('*.zip'))
        if archives:
            raise ValueError('This directory-only inventory cannot silently skip zipped gun packs; '
                             'inspect an extracted copy with --tacz-dir: ' + ', '.join(str(p) for p in archives))
        if not manifests:
            raise ValueError(f'No gunpack.meta.json found in {pack_directory}')
        for manifest in manifests:
            pack = manifest.parent
            entries = gun_entries(pack)
            digest = hashlib.sha256()
            for p in sorted(pack.rglob('*.json')):
                digest.update(p.relative_to(pack).as_posix().encode('utf-8'))
                digest.update(p.read_bytes())
            fingerprint = digest.hexdigest()
            pack_fingerprints.add(fingerprint)
            packs.append({'path': str(pack), 'manifest': read(manifest), 'gun_count': len(entries), 'json_tree_sha256': fingerprint})
            local.extend(entries)
        duplicate_ids = [gun_id for gun_id, count in collections.Counter(g['id'] for g in local).items() if count > 1]
        if duplicate_ids:
            raise ValueError('Overlapping pack IDs require live TaCZ load-order verification: ' + ', '.join(duplicate_ids))
        excluded, allowed = [], []
        for gun in local:
            reasons = eligibility(gun, cfg)
            if reasons: excluded.append({'id': gun['id'], 'reasons': reasons})
            else: allowed.append(gun['id'])
            if gun['id'] not in all_guns:
                all_guns[gun['id']] = gun
                gun['contexts'] = {}
            all_guns[gun['id']]['contexts'][context_id] = {'allowed': not reasons, 'exclusion_reasons': reasons}
            gun['eligible_tiers'] = [tier for tier, types in TIERS.items() if gun['type'] in types]
        options_file = game / 'options.txt'
        options = options_file.read_text(encoding='utf-8') if options_file.is_file() else ''
        resource_packs = next((json.loads(line.split(':', 1)[1]) for line in options.splitlines() if line.startswith('resourcePacks:')), None)
        contexts.append({'id': context_id, 'game_directory': str(game), 'gunpack_directory': str(pack_directory),
                         'config_file': str(config_file), 'config_sha256': hashlib.sha256(config_file.read_bytes()).hexdigest(), 'filters': cfg,
                         'gun_pack_count': len(packs), 'packs': packs, 'indexed_count_on_disk': len(local),
                         'allowed_count_from_disk_rules': len(allowed), 'allowed_ids': sorted(allowed), 'excluded': excluded,
                         'active_vanilla_resource_packs': resource_packs})

    library = args.tacz_jar.resolve()
    embedded = None
    if library.is_file():
        with zipfile.ZipFile(library) as jar:
            embedded_indices = sorted(n for n in jar.namelist() if re.fullmatch(r'assets/tacz/custom/[^/]+/data/[^/]+/index/guns/.+\.json', n))
            embedded = {'jar': str(library), 'sha256': hashlib.sha256(library.read_bytes()).hexdigest(),
                        'index_count': len(embedded_indices), 'gunpack_roots': sorted(set(n.split('/')[3] for n in embedded_indices)),
                        'note': 'Bundled default gun pack is exported to the tacz folder; this is not an additional installed pack.'}

    entity_families = [
        {'entity_id': 'tarkovscav:scav', 'render_family': 'gecko_scav', 'natural_tiers': list(TIERS)},
        *[{'entity_id': 'tarkovscav:' + name, 'render_family': 'gecko_pillager_or_vanilla_pillager', 'natural_tiers': ['sniper'] if name == 'sniper_pillager' else list(TIERS)}
          for name in ['gunner_pillager', 'sniper_pillager', 'bear_pillager', 'elite_pillager']],
        *[{'entity_id': 'tarkovscav:' + name, 'render_family': 'vanilla_villager', 'natural_tiers': ['sniper'] if name == 'sniper_villager' else list(TIERS)}
          for name in ['gunner_villager', 'sniper_villager', 'usec_villager', 'elite_villager']],
    ]
    guns = sorted(all_guns.values(), key=lambda g: g['id'])
    report = {
        'schema_version': 2, 'generated_from': 'Read-only inspection of explicitly selected local TaCZ packs; game not launched.',
        'verified_scope': [{'gunpack_directory': str(directory), 'config_file': str(config)} for _, directory, config in context_inputs],
        'unique_installed_pack_json_trees': len(pack_fingerprints), 'installation_copy_count': sum(c['gun_pack_count'] for c in contexts),
        'unique_gun_id_count': len(guns), 'contexts': contexts, 'embedded_pack': embedded,
        'render_families': entity_families, 'tiers': TIERS,
        'counts_by_type': dict(sorted(collections.Counter(g['type'] for g in guns).items())),
        'missing_thirdperson_hand': [g['id'] for g in guns if not g['model']['has_thirdperson_hand']],
        'missing_lod_thirdperson_hand': [g['id'] for g in guns if g['lod_model'] and not g['lod_model']['has_thirdperson_hand']],
        'guns': guns,
        'mount_audit': mount_audit(guns, contexts),
        'api_evidence': {
          'library': str(library),
          'display_lookup': 'TimelessAPI.getGunDisplay(ItemStack) -> Optional<GunDisplayInstance>',
          'per_gun_origin': 'GunDisplayInstance.getGunModel().getThirdPersonHandOriginPath()',
          'per_gun_scale': 'GunDisplayInstance.getTransform().getScale().getThirdPerson()',
          'already_applied_by_tacz': 'GunItemRendererWrapper.applyPositioningTransform calls getThirdPersonHandOriginPath for THIRD_PERSON_RIGHT_HAND; callers must not apply it a second time.',
          'display_transform_contract': 'GunTransform exposes scale only; per-gun positional/rotational origin comes from model bone path, not a display offset field.',
          'model_pivot_contract': 'BedrockModel.convertPivot: root [x,24-y,z], child [x-parent.x,parent.y-y,z-parent.z]; convertRotation uses positive radians.',
          'model_chain_contract': 'BedrockPart.translateAndRotateAndScale: T(localPivot/16) * Rz * Ry * Rx in rest pose. BonesItem has no static scale field.',
          'inverse_origin_contract': 'GunItemRendererWrapper.applyPositioningNodeTransform: reverse root-first path, Rx(-xRot) Ry(-yRot) Rz(-zRot), then negative scale-adjusted local pivot; root Y includes +1.5*scale.y.',
          'wrapper_contract': 'T(0.5,2,0.5) S(-1,-1,1) * positioning * T(0,1.5,0) S(displayScale) T(0,-1.5,0); selected main or LOD model is used for both origin and render.',
          'muzzle_contract': 'BedrockGunModel.getMuzzleFlashPosPath() resolves muzzle_flash, not muzzle_pos.',
          'inspection_sources': ['GunItemRendererWrapper.javap.txt', 'BedrockGunModel.javap.txt', 'BedrockTransforms.javap.txt'],
        },
        'limits': ['No live TimelessAPI index queried; allowed IDs are inferred from existing disk index and actual local config.',
                   'No game rendering or screenshot validation was performed.',
                   'JSONC gun data/models were read only; no third-party pack or art assets changed.'],
    }
    report['summary'] = {'allowed_ids': contexts[0]['allowed_ids'], 'all_models_have_origin': not report['missing_thirdperson_hand'],
                         'all_lod_models_have_origin': not report['missing_lod_thirdperson_hand'],
                         'thirdperson_scale_counts': dict(collections.Counter(str(g['thirdperson_scale_declared']) for g in guns))}
    log_file = args.runtime_log.resolve()
    log_bytes = log_file.read_bytes() if log_file.is_file() else b''
    log_lines = log_bytes.decode('utf-8-sig').splitlines()
    matching_lines = [{'line': i + 1, 'text': line} for i, line in enumerate(log_lines)
                      if 'TaCZ gun pool:' in line or re.search(r'tier \(\d+ guns\):', line)]
    logged_ids = set()
    for entry in matching_lines:
        if re.search(r'tier \(\d+ guns\):', entry['text']):
            logged_ids.update(re.findall(r'[a-z0-9_.-]+:[a-z0-9_./-]+', entry['text'].split('guns):', 1)[1]))
    report['existing_runtime_log_evidence'] = {'file': str(log_file), 'lines': matching_lines,
        'source_sha256': hashlib.sha256(log_bytes).hexdigest() if log_bytes else None,
        'logged_allowed_ids_match_current_disk_rules': logged_ids == set(contexts[0]['allowed_ids']) if matching_lines else None,
        'note': 'Historical existing log, with original timestamps and line numbers preserved; no fresh game launch was performed.'}
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    # Keep the previously captured source evidence if a fresh preview has replaced latest.log.
    if output.is_file():
        previous = json.loads(output.read_text(encoding='utf-8'))
        history = previous.get('runtime_log_evidence_history', [])
        evidence = previous.get('existing_runtime_log_evidence')
        if (evidence and evidence.get('lines')
                and evidence.get('source_sha256') != report['existing_runtime_log_evidence']['source_sha256']
                and not any(item.get('source_sha256') == evidence.get('source_sha256') for item in history)):
            history.append(evidence)
        if history:
            report['runtime_log_evidence_history'] = history
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (output.parent / 'gun-ids.txt').write_text(','.join(contexts[0]['allowed_ids']) + '\n', encoding='utf-8')
    print(json.dumps({'unique_pack_count': len(pack_fingerprints), 'gun_count': len(guns),
                      'contexts': [{k:c[k] for k in ['id','indexed_count_on_disk','allowed_count_from_disk_rules']} for c in contexts],
                      'type_counts': report['counts_by_type'], 'missing_origins': report['missing_thirdperson_hand'],
                      'missing_lod_origins': report['missing_lod_thirdperson_hand'],
                      'automatic_origin_coverage': report['mount_audit']['automatic_origin_coverage']}, ensure_ascii=True))


if __name__ == "__main__":
    main()
