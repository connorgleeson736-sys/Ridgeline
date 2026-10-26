"""Explicit 3D Tiles REPLACE hierarchy with independently durable GLB leaves."""
from pathlib import Path
import json
import re
from concurrent.futures import ThreadPoolExecutor
from collections import deque
import numpy as np
from . import clusters,gltf_export,quantization,coordinates
from .state import atomic_json,key,digest


def uri(asset,suffix):
    sha=asset.get('sha256','')
    if not asset.get('verified') or not re.fullmatch('[a-f0-9]{64}',sha):raise ValueError('Verified persistent asset required')
    return '/api/lidar/files/'+sha+suffix


def box(lo,hi):
    lo=np.asarray(lo,float)-.001;hi=np.asarray(hi,float)+.001
    center=(lo+hi)/2;half=(hi-lo)/2
    return np.r_[center,half[0],0,0,0,half[1],0,0,0,half[2]].tolist()


def assemble(coarse,leaves,expected_triangles,intermediate=None):
    if not leaves or sum(r['triangles'] for r in leaves)!=expected_triangles:raise ValueError('Finest triangle coverage mismatch')
    frame=coarse['coordinate_frame'];children=[]
    # Parent certificates compare to a Float32 common-ENU reference. Recover a
    # conservative bound to the full precision transformed reconstruction using
    # the maximum reference rounding over ALL finest clusters. Fine leaf bounds
    # already include this conversion and their position packing contribution.
    reference_rounding=max(r['coordinate_rounding_max_m'] for r in leaves)
    if not np.isfinite(reference_rounding) or not 0<=reference_rounding<.001:raise ValueError('Unexpected reference encoding error')
    for report in leaves:
        if report['coordinate_frame']!=frame:raise ValueError('Mixed geographic coordinate frames')
        if not report['triangle_topology_preserved'] or not report['validation']['verified']:raise ValueError('Unverified leaf packing')
        bound=report['continuous_world_bound']
        if not report['finest_reference'] or not bound['certified'] or bound['upper_bound_m']>.001:
            raise ValueError('Finest display cluster lacks a verified submillimetre bound')
        children.append(dict(boundingVolume=dict(box=box(report['enu_min'],report['enu_max'])),
            geometricError=bound['upper_bound_m'],content=dict(uri=uri(report['asset'],'.glb'))))
    if not coarse['continuous_world_bound']['certified'] or not coarse['validation']['verified']:raise ValueError('Uncertified coarse fallback')
    lo=np.min([r['enu_min'] for r in [coarse,*leaves]],axis=0)
    hi=np.max([r['enu_max'] for r in [coarse,*leaves]],axis=0)
    error=coarse['continuous_world_bound']['upper_bound_m']+reference_rounding+1e-5
    if intermediate is not None:
        mid=intermediate;bound=mid['continuous_world_bound']
        if mid['coordinate_frame']!=frame or not bound['certified'] or not mid['validation']['verified']:
            raise ValueError('Unverified intermediate LOD/frame')
        if not 0<bound['upper_bound_m']<error:raise ValueError('Intermediate error must decrease')
        lo=np.minimum(lo,mid['enu_min']);hi=np.maximum(hi,mid['enu_max'])
        children=[dict(boundingVolume=dict(box=box(lo,hi)),geometricError=bound['upper_bound_m']+reference_rounding+1e-5,
            refine='REPLACE',content=dict(uri=uri(mid['asset'],'.glb')),children=children)]
    root=dict(boundingVolume=dict(box=box(lo,hi)),geometricError=error,refine='REPLACE',
        transform=frame['enu_to_ecef_column_major'],content=dict(uri=uri(coarse['asset'],'.glb')),children=children)
    return dict(asset=dict(version='1.1',tilesetVersion='ridgeline-original-point-pilot-1'),
        geometricError=error,root=root,extras=dict(ridgeline=dict(status='Single processing-patch pilot; regional publication gates remain',
            finest_triangles=expected_triangles,finest_clusters=len(leaves),coordinate_frame=frame,
            reference_float32_rounding_max_m=reference_rounding,
            parent_error_accounting='Certificate to Float32 reference + measured maximum full-precision reference rounding +1e-5m margin. Finest leaf bounds already include conversion/packing.',
            limits='Not the complete 2km region; no rendered/device performance claim; original raw data remain authoritative')))


def process(state,tile=189,delta=1.35,maximum=16384,kind='source',limit=None,workers=2):
    if kind not in ['source','ai']:raise ValueError('Invalid imagery mode')
    if limit is not None and (type(limit) is not int or limit<1):raise ValueError('Positive bounded cluster count required')
    if type(workers) is not int or not 1<=workers<=3:raise ValueError('Use one to three bounded asset workers')
    label=f'{tile:04d}-d'+format(delta,'.6g').replace('.','p')
    source=state.root/'data/local'/format(delta,'.6g').replace('.','p')/f'{tile:04d}'/'finest.npz'
    parent=state.task('local-surface-'+f'{tile:04d}'+'-d'+format(delta,'.6g').replace('.','p'))
    if not parent or parent['state']!='durable' or not state.valid(parent['name'],parent['signature']):
        raise ValueError('Durable original-point source patch required before asset export')
    coarse_label=label+'-2';coarse_path=state.root/'data/certified'/('certified-lod-'+coarse_label)/'mesh.npz'
    coarse_task=state.task('certified-lod-'+coarse_label)
    if not coarse_task or coarse_task['state']!='durable' or not state.valid(coarse_task['name'],coarse_task['signature']):
        raise ValueError('Durable certified 2m coarse LOD required')
    mid_label=label+'-0p6';mid_path=state.root/'data/certified'/('certified-lod-'+mid_label)/'mesh.npz'
    mid_task=state.task('certified-lod-'+mid_label)
    if not mid_task or mid_task['state']!='durable' or not state.valid(mid_task['name'],mid_task['signature']):
        raise ValueError('Durable certified 0.6m intermediate LOD required')
    frame=coordinates.checkpoint(state)
    with np.load(source) as data:spatial_vertices=frame.to_enu(data['vertices']).astype(np.float32)
    plan=clusters.process(state,source,label,maximum,spatial_vertices=spatial_vertices)
    count=plan['metrics']['clusters'];finest_count=plan['metrics']['triangles']
    name='tileset-'+label+'-'+kind;directory=state.root/'data/tilesets'/name
    expected=['glb-'+coarse_label+'-'+kind,'glb-'+mid_label+'-'+kind]+['glb-'+label+f'-c{i:04d}-'+kind for i in range(count)]+[name]
    plan_name='render-plan-'+label+'-'+kind
    plan_signature=key(dict(stage='render-plan-v2',clusters=plan['signature'],coarse=coarse_task['signature'],
        intermediate=mid_task['signature'],imagery=kind,tasks=expected))
    with state.lock(plan_name):
        if not state.valid(plan_name,plan_signature):
            state.start(plan_name,plan_signature)
            path=state.root/'data/render-plans'/(label+'-'+kind+'.json')
            atomic_json(path,dict(tasks=expected,cluster_plan=plan['name'],source_sha256=digest(source),
                maximum_triangles_per_cluster=maximum,imagery=kind))
            state.stage(plan_name,plan_signature,[path],dict(expected=len(expected)))
        elif state.remote:state.remote.sync_task(state,plan_name)
    if state.remote and state.task(plan_name)['state']!='durable':raise RuntimeError('Render work plan awaits persistent checkpoint')
    coarse=gltf_export.process(state,coarse_path,source,coarse_label,2.,kind)['metrics']
    intermediate=gltf_export.process(state,mid_path,source,mid_label,.6,kind)['metrics']
    def export_cluster(i):
        path=clusters.extract(state,label,i)
        report=gltf_export.process(state,path,path,label+f'-c{i:04d}',0.,kind,quantization.POSITION_STEP)['metrics']
        return report
    # At most three in-flight jobs/buffers, deterministic output ordering. A
    # failed worker prevents tileset publication; already saved leaves survive.
    reports=[];total=count if limit is None else min(count,limit);queue=deque();next_job=0
    pool=ThreadPoolExecutor(max_workers=workers)
    try:
        while next_job<min(total,workers):queue.append(pool.submit(export_cluster,next_job));next_job+=1
        while queue:
            report=queue.popleft().result();reports.append(report)
            print(json.dumps(dict(stage='fine-cluster',tile=tile,completed=len(reports),total=count,
                triangles=report['triangles'],bytes=report['glb_bytes'])),flush=True)
            if next_job<total:queue.append(pool.submit(export_cluster,next_job));next_job+=1
    finally:pool.shutdown(wait=True,cancel_futures=True)
    if len(reports)!=count:
        return dict(complete=False,completed=len(reports),expected=count,remaining=count-len(reports),
            note='Intentional bounded batch; every completed GLB is independently durable. Resume without limit.')
    signature=key(dict(stage='explicit-patch-tileset-v2',plan=plan_signature,intermediate=intermediate['asset']['sha256'],
        coarse=coarse['asset']['sha256'],leaves=[r['asset']['sha256'] for r in reports],implementation=digest(__file__)))
    with state.lock(name):
        if state.valid(name,signature):
            if state.remote:state.remote.sync_task(state,name)
            return state.task(name)['metrics']
        state.start(name,signature);directory.mkdir(parents=True,exist_ok=True)
        try:
            tileset=assemble(coarse,reports,finest_count,intermediate);path=directory/'tileset.json';atomic_json(path,tileset)
            asset=state.remote.file(path,'application/json') if state.remote else dict(sha256=digest(path),verified=False)
            result=dict(complete=True,tiles=2+count,finest_triangles=finest_count,coarse_triangles=coarse['triangles'],
                intermediate_triangles=intermediate['triangles'],intermediate_glb_bytes=intermediate['glb_bytes'],
                geometric_error_m=tileset['root']['geometricError'],asset=asset,
                finest_glb_bytes=sum(r['glb_bytes'] for r in reports),coarse_glb_bytes=coarse['glb_bytes'],
                maximum_cluster_gpu_bytes=max(r['gpu_attribute_bytes']+r['gpu_index_bytes'] for r in reports),
                every_original_triangle_exactly_once=True,publishable=False,
                pending=['regional coplanar ownership/seams','regional coarse LODs','viewer/device inspection and benchmarks'])
            metadata=directory/'validation.json';atomic_json(metadata,result)
            state.stage(name,signature,[path,metadata],result)
            if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Tileset awaits persistent checkpoint')
            return result
        except Exception as error:
            if not state.valid(name,signature):state.fail(name,error)
            raise
