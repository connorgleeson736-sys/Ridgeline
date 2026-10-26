"""Save a validated, explicitly limited pilot configuration before exposing it.

Never sets production/publishable flags. Browser/device gates remain explicit.
"""
from pathlib import Path
import json,re
import numpy as np
from .state import atomic_json,atomic_bytes,digest,key
from . import coordinates,inspection_camera

def collect(state,label='0189-d1p35',kind='source'):
    name='tileset-'+label+'-'+kind;task=state.task(name)
    if not task or task['state']!='durable' or not state.valid(name,task['signature']):raise ValueError('Complete durable hierarchy required')
    summary=task['metrics']
    if not summary['complete'] or not summary['every_original_triangle_exactly_once']:raise ValueError('Incomplete triangle coverage')
    path=state.root/'data/tilesets'/name/'tileset.json';tree=json.loads(path.read_text())
    if digest(path)!=summary['asset']['sha256']:raise ValueError('Hierarchy asset hash mismatch')
    correction=tree.get('extras',{}).get('ridgeline',{}).get('reference_float32_rounding_max_m')
    if correction is None or not 0<=correction<.001:raise ValueError('Full-precision reference rounding budget required')
    content=[]
    def visit(node):
        if 'content' in node:content.append(node['content']['uri'])
        for child in node.get('children',[]):visit(child)
    visit(tree['root'])
    expected=['glb-'+label+'-2-'+kind,'glb-'+label+'-0p6-'+kind]
    expected+=['glb-'+label+f'-c{i:04d}-'+kind for i in range(64)]
    reports=[];signatures=[]
    for stage in expected:
        entry=state.task(stage)
        if not entry or entry['state']!='durable' or not state.valid(stage,entry['signature']):raise ValueError('Missing durable GLB: '+stage)
        report=entry['metrics'];asset=report['asset']
        if not asset['verified'] or not report['validation']['verified']:raise ValueError('Unverified decoded/persistent GLB')
        if not re.fullmatch('[a-f0-9]{64}',asset['sha256']):raise ValueError('Invalid asset hash')
        reports.append(report);signatures.append(entry['signature'])
    if set(content)!={'/api/lidar/files/'+r['asset']['sha256']+'.glb' for r in reports} or len(content)!=len(expected):
        raise ValueError('Hierarchy references do not match current verified assets')
    fine=reports[2:]
    if sum(r['triangles'] for r in fine)!=summary['finest_triangles']:raise ValueError('Finest triangle accounting mismatch')
    source=state.root/'data/local/1p35/0189/finest.npz'
    frame=coordinates.Frame(state.config,state.root/'data/coordinates'/coordinates.GRID_NAME)
    with np.load(source) as data:camera=inspection_camera.geographic(frame,inspection_camera.select(frame.to_enu(data['vertices']),data['faces']))
    config=dict(schema=1,status='pilot',run=state.run,patch_metres=100,patch_index=189,
        tileset_sha256=summary['asset']['sha256'],finest_triangles=summary['finest_triangles'],fine_clusters=len(fine),
        source='Original LINZ Canterbury point clouds; local full-XYZ triangle union',
        source_crs='EPSG:2193+7839',imagery=kind,
        inspection_camera=camera,
        validation=dict(all_assets_verified=True,original_face_coverage=True,decoder_and_khronos_verified=True,
            browser_device_verified=False,production_release=False),
        limits=['Only one100m processing patch, not the complete2km ROI','Nonmanifold reconstruction experiment',
            'Top-down source imagery does not recover unobserved cliff photography','Real browser/device inspection outstanding'])
    benchmark=dict(schema=1,scope='Single100m summit0189 pilot; NOT regional/device performance results',
        run=state.run,tileset_sha256=summary['asset']['sha256'],
        levels=[dict(name=n,triangles=r['triangles'],glb_bytes=r['glb_bytes'],
            continuous_bound_against_float32_reference_m=r['continuous_world_bound']['upper_bound_m'],
            full_precision_reference_bound_m=r['continuous_world_bound']['upper_bound_m']+correction,
            persistent_sha256=r['asset']['sha256']) for n,r in zip(['coarse','intermediate'],reports[:2])],
        fine=dict(clusters=len(fine),triangles=sum(r['triangles'] for r in fine),glb_bytes=sum(r['glb_bytes'] for r in fine),
            same_input_float32_glb_bytes=sum(r['baseline_float32_glb_bytes'] for r in fine),
            gpu_geometry_bytes=sum(r['gpu_attribute_bytes']+r['gpu_index_bytes'] for r in fine),
            full_precision_reconstruction_display_bound_m=max(r['continuous_world_bound']['upper_bound_m'] for r in fine),
            compact_attribute_bytes_per_vertex=20,baseline_attribute_bytes_per_vertex=32),
        reference_float32_rounding_max_m=correction,
        accuracy_limits=['Bounds are relative to reconstructed geometry, not absolute LiDAR accuracy',
            'Encoding precision does not imply geological or survey accuracy'],
        browser_fps=None,browser_gpu_time_ms=None,device_benchmarks=[],
        device_benchmark_url='/lidar-summit.html',
        verification='Actual site meshopt WASM decoder under Node, decoded-core Khronos validation, continuous geometry bounds and persistent readback; no browser/device test')
    return config,benchmark,key(dict(hierarchy=task['signature'],glbs=signatures,implementation=digest(__file__),camera=digest(inspection_camera.__file__)))

def process(state,checkout):
    checkout=Path(checkout);config,benchmark,signature=collect(state)
    manifest=json.loads((checkout/'.openai/hosting.json').read_text())
    if manifest['project_id']!='appgprj_6acc83afb6588191aff43dc542d98b5f':raise ValueError('Wrong Ridgeline checkout')
    name='pilot-release-0189-source';directory=state.root/'data/pilot-release';directory.mkdir(parents=True,exist_ok=True)
    with state.lock(name):
        if not state.valid(name,signature):
            state.start(name,signature)
            try:
                cfg=directory/'config.json';bench=directory/'benchmarks.json'
                atomic_json(cfg,config);atomic_json(bench,benchmark)
                state.stage(name,signature,[cfg,bench],dict(status='pilot',tileset_sha256=config['tileset_sha256'],
                    finest_triangles=config['finest_triangles'],browser_device_verified=False))
            except Exception as error:
                if not state.valid(name,signature):state.fail(name,error)
                raise
        elif state.remote:state.remote.sync_task(state,name)
        if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Pilot release configuration awaits external checkpoint')
        atomic_bytes(checkout/'public/assets/mt-cook/lidar-pilot.json',(directory/'config.json').read_bytes())
        atomic_bytes(checkout/'tools/lidar_pipeline/PILOT_BENCHMARKS-2026-10-14.json',(directory/'benchmarks.json').read_bytes())
    return dict(pilot_config_saved=True,tileset_sha256=config['tileset_sha256'],benchmark=benchmark)

if __name__=='__main__':
    from .state import State
    from .remote import Remote
    print('Ready for private pilot release JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    print(json.dumps(process(state,settings['checkout'])),flush=True)
