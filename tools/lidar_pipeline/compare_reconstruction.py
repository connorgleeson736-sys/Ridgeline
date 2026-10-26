"""Compare full-XYZ reconstruction methods before committing a production recipe.

Poisson outputs are strictly support-trimmed; unsupported closure is not kept.
Every method retains the original observations separately and reports fitting
error. This is an experiment, not permission to synthesize missing geography.
"""
import argparse
import json
from pathlib import Path
import time

import meshoptimizer as mo
import numpy as np
import open3d as o3d
from scipy.spatial import cKDTree

from .reconstruct import gather,clip_core,scene,distances,boundary
from .state import State,atomic_json,key


def trim_supported(vertices,faces,points,normals,radius=.45):
    """Each retained triangle fits inside one measured-point support ball.

    Euclidean balls are convex: if all three corners are within radius of the
    SAME source point, the entire triangle is within radius of observations.
    Signed normal agreement rejects unsupported opposing closure surfaces.
    """
    tree=cKDTree(points);kept=[]
    for first in range(0,len(faces),50000):
        f=faces[first:first+50000];p=vertices[f];center=p.mean(axis=1)
        _,index=tree.query(center,workers=4)
        support=np.linalg.norm(p-points[index,None,:],axis=2).max(axis=1)
        n=np.cross(p[:,1]-p[:,0],p[:,2]-p[:,0]);length=np.linalg.norm(n,axis=1)
        agreement=(n*normals[index]).sum(axis=1)/np.maximum(length,1e-20)
        keep=(support<=radius)&(agreement>=.15)&(length>1e-9)
        kept.append(f[keep])
    f=np.concatenate(kept)
    used,remap=np.unique(f,return_inverse=True)
    return vertices[used],remap.reshape(-1,3).astype(np.uint32)


def run(state,tile,methods):
    cfg=state.config;size=cfg['tile_size_m'];nx=int((cfg['bounds'][2]-cfg['bounds'][0])/size)
    iy,ix=divmod(tile,nx);bounds=[ix*size,iy*size,(ix+1)*size,(iy+1)*size]
    points,inputs=gather(state,bounds);points=np.unique(points,axis=0)
    if len(points)>cfg['max_points_per_job']:raise MemoryError('Trial patch exceeds memory bound')
    cloud=o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points.astype(float)))
    cloud.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=cfg['normal_radius_m'],max_nn=cfg['normal_max_nn']))
    cloud.orient_normals_consistent_tangent_plane(16)
    n=np.asarray(cloud.normals)
    if np.median(n[:,2])<0:cloud.normals=o3d.utility.Vector3dVector(-n)
    n=np.asarray(cloud.normals)
    x0,y0,x1,y1=bounds
    core=points[(points[:,0]>=x0)&(points[:,0]<=x1)&(points[:,1]>=y0)&(points[:,1]<=y1)]
    reports=[]
    for method in methods:
        task=f'compare-{tile:04d}-{method}'
        signature=key(dict(stage='compare-reconstruction-v2',method=method,inputs=inputs,bounds=bounds,
                           support_radius_m=.45,normals=[cfg['normal_radius_m'],cfg['normal_max_nn']]))
        if state.valid(task,signature):
            reports.append(state.task(task)['metrics']);continue
        state.start(task,signature);start=time.perf_counter()
        try:
            if method.startswith('bpa'):
                radii={'bpa-small':[.12,.24,.5,1,2],'bpa-medium':[.35,.7,1.4,2.8],
                       'bpa-large':[.7,1.4,2.8]}[method]
                mesh=o3d.geometry.TriangleMesh.create_from_point_cloud_ball_pivoting(cloud,o3d.utility.DoubleVector(radii))
                v,f=clip_core(np.asarray(mesh.vertices),np.asarray(mesh.triangles),bounds)
                fitted=False
            else:
                depth=int(method.replace('poisson','').split('-')[0]); smooth=method.endswith('-smooth')
                mesh,_=o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(cloud,depth=depth,
                    scale=1.05,linear_fit=True,n_threads=4,point_weight=2 if smooth else 8,samples_per_node=3 if smooth else 1.5)
                v=np.asarray(mesh.vertices).astype(np.float32);f=np.asarray(mesh.triangles).astype(np.uint32)
                v,f=trim_supported(v,f,points,n,radius=.7 if smooth else .45)
                v,f=clip_core(v,f,bounds)
                fitted=True
            base=distances(scene(v,f),core)
            planes,nonmanifold,open_edges=boundary(v,f,bounds)
            simplified=[]
            for budget in [.025,.075,.2,.6,2]:
                output=np.empty(f.size,np.uint32);error=np.zeros(1,np.float32)
                count=mo.simplify(output,f.ravel(),v,target_index_count=3,target_error=budget,
                    options=mo.SIMPLIFY_LOCK_BORDER|mo.SIMPLIFY_ERROR_ABSOLUTE,result_error=error)
                candidate=output[:count].reshape(-1,3)
                test=scene(v,candidate)
                delta=float(np.maximum(0,distances(test,core)-base).max(initial=0))
                geometric=float(distances(test,v).max(initial=0))
                simplified.append(dict(budget_m=budget,triangles=len(candidate),reported=float(error[0]),
                    measured_source_extra_max_m=delta,measured_vertex_max_m=geometric,
                    nonmanifold_edges=boundary(v,candidate,bounds)[1]))
            output=state.root/'data/experiments'/f'{tile:04d}'/method
            output.mkdir(parents=True,exist_ok=True)
            file=output/'surface.npz';np.savez_compressed(file,vertices=v,faces=f,source_points=core,source_distances=base)
            report=dict(method=method,bounds=bounds,points=len(core),vertices=len(v),triangles=len(f),
                reconstruction_fits_observations=fitted,source_distance_p50_m=float(np.quantile(base,.5)),
                source_distance_p95_m=float(np.quantile(base,.95)),source_distance_p99_m=float(np.quantile(base,.99)),
                source_distance_max_m=float(base.max()),unsupported_over_025m=int((base>.25).sum()),
                open_edges=open_edges,nonmanifold_edges=nonmanifold,simplification_trials=simplified,
                seconds=time.perf_counter()-start,publishable=False,
                support_note='Poisson closure retained only with signed normal agreement and full triangle contained in one 0.45 m source-point support ball.' if fitted else 'Original point vertices; no unsupported gap filling.')
            report_path=output/'validation.json';atomic_json(report_path,report)
            receipt=state.stage(task,signature,[file,report_path],report)
            print(json.dumps(dict(event='comparison_checkpoint',**receipt,report=report)),flush=True)
            reports.append(report)
        except Exception as error:
            state.fail(task,error);raise
    return reports


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--tile',type=int,default=381)
    p.add_argument('--methods',nargs='+',default=['bpa-medium','bpa-large','poisson10','poisson11'])
    args=p.parse_args();root=Path(args.root);s=State(root,json.loads((root/'config.json').read_text()))
    run(s,args.tile,args.methods)
