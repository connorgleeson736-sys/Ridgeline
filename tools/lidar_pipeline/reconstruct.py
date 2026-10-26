"""Bounded full-XYZ surface reconstruction and independently measured LOD.

No heightfield, Poisson closure, voxel downsample, or synthesized overhangs.
This stage intentionally does not mark meshes publishable before seam checks.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import time

import numpy as np
import open3d as o3d
import meshoptimizer as mo

from .partition import DTYPE
from .state import State, atomic_json, digest, key


def gather(state, bounds):
    """Read only intersecting shards; require every contributing source stage."""
    config=state.config;size=config['tile_size_m'];halo=config['halo_m']
    x0,y0,x1,y1=bounds;nx=int(np.ceil((config['bounds'][2]-config['bounds'][0])/size))
    ny=int(np.ceil((config['bounds'][3]-config['bounds'][1])/size))
    origins=np.array(config['origin'])
    catalog_path=state.root/'data/source-audit.json';catalog={}
    if catalog_path.exists():
        proof=state.task('source-audit')
        if not proof or proof['state']!='durable' or not state.valid('source-audit',proof['signature']):
            raise ValueError('Source audit catalog lacks a verified durable checkpoint')
        catalog={a['source']['name']:a for a in json.loads(catalog_path.read_text())['sources']}
        sources=[a['source'] for a in catalog.values()]
    else:
        proof=state.task('discover')
        if not proof or proof['state']!='durable' or not state.valid('discover',proof['signature']):
            raise ValueError('Verified source discovery or source-audit catalog required')
        sources=json.loads((state.root/'data/sources.json').read_text())['sources']
    needed=[]
    for source in sources:
        audit=state.root/'data/raw'/Path(source['name']).with_suffix('.audit.json')
        if source['name'] in catalog:a=catalog[source['name']]
        else:
            if not audit.exists():raise RuntimeError('Missing source audit '+source['name'])
            proof=state.task('raw-'+Path(source['name']).stem)
            if not proof or proof['state']!='durable' or not state.valid(proof['name'],proof['signature']):
                raise ValueError('Raw audit lacks a verified checkpoint; restore source-audit for data-only processing')
            a=json.loads(audit.read_text())
        lo,hi=np.array(a['bounds'])-origins
        if hi[0]<x0-halo or lo[0]>x1+halo or hi[1]<y0-halo or lo[1]>y1+halo:continue
        task=state.task('partition-'+Path(source['name']).stem)
        if not task or task['state']!='durable':
            raise RuntimeError('Contributing partition is not externally durable: '+source['name'])
        if not state.valid(task['name'],task['signature']):
            raise RuntimeError('Partition failed local integrity; restore checkpoint: '+source['name'])
        inventory=json.loads((state.root/'data/shards'/Path(source['name']).stem/'inventory.json').read_text())
        if inventory['source_sha256']!=a['crop_sha256']:raise ValueError('Partition/source audit generation mismatch')
        needed.append((source,task))
    parts=[];inputs={}
    for source,task in needed:
        inputs[source['name']]=task['signature']
        shard_root=state.root/'data/shards'/Path(source['name']).stem
        for iy in range(max(0,int((y0-halo)//size)),min(ny-1,int((y1+halo)//size))+1):
            for ix in range(max(0,int((x0-halo)//size)),min(nx-1,int((x1+halo)//size))+1):
                path=shard_root/f'{iy*nx+ix:04d}.bin'
                if not path.exists():continue
                a=np.fromfile(path,DTYPE)['position']
                mask=(a[:,0]>=x0-halo)&(a[:,0]<=x1+halo)&(a[:,1]>=y0-halo)&(a[:,1]<=y1+halo)
                if mask.any():parts.append(a[mask])
    if not parts:raise ValueError('No survey-valid points in job extent')
    return np.concatenate(parts),inputs


def clip_core(vertices, faces, bounds):
    """Exact plane clipping only on crossing triangles; shared plane positions.

    Original interior measured vertices stay fixed. New cut vertices interpolate
    existing triangle edges, not a DEM, and carry no new measurement claim.
    """
    x0,y0,x1,y1=bounds
    p=vertices[faces]
    inside=(p[:,:,0]>=x0)&(p[:,:,0]<=x1)&(p[:,:,1]>=y0)&(p[:,:,1]<=y1)
    interior=inside.all(axis=1)
    outside=((p[:,:,0]<x0).all(axis=1)|(p[:,:,0]>x1).all(axis=1)|
             (p[:,:,1]<y0).all(axis=1)|(p[:,:,1]>y1).all(axis=1))
    output_vertices=[v for v in vertices]
    output_faces=[t for t in faces[interior]]
    # Weld generated cut positions, retaining 1 micrometre coordinate keying.
    generated={}
    def index(v):
        k=tuple(np.round(v,6))
        if k not in generated:
            generated[k]=len(output_vertices);output_vertices.append(v)
        return generated[k]
    for triangle in p[~interior&~outside]:
        poly=list(triangle)
        for axis,plane,sign in [(0,x0,1),(0,x1,-1),(1,y0,1),(1,y1,-1)]:
            result=[]
            for i,a in enumerate(poly):
                b=poly[(i+1)%len(poly)];ai=sign*(a[axis]-plane)>=0;bi=sign*(b[axis]-plane)>=0
                if ai:result.append(a)
                if ai!=bi:
                    v=a+(b-a)*((plane-a[axis])/(b[axis]-a[axis]));v[axis]=plane;result.append(v)
            poly=result
            if not poly:break
        for i in range(1,len(poly)-1):output_faces.append([index(poly[0]),index(poly[i]),index(poly[i+1])])
    faces=np.asarray(output_faces,dtype=np.uint32)
    if not len(faces):raise ValueError('No supported triangles in tile core')
    used,remap=np.unique(faces,return_inverse=True)
    vertices=np.asarray(output_vertices,dtype=np.float32)[used]
    faces=remap.reshape(-1,3).astype(np.uint32)
    # Crossing triangles also reference original points. Weld their generated
    # references back to identical measured positions before topology/LOD work.
    vertices,weld=np.unique(vertices,axis=0,return_inverse=True)
    faces=weld[faces].astype(np.uint32)
    area=np.linalg.norm(np.cross(vertices[faces[:,1]]-vertices[faces[:,0]],vertices[faces[:,2]]-vertices[faces[:,0]]),axis=1)
    faces=faces[area>1e-9]
    used,remap=np.unique(faces,return_inverse=True)
    vertices=vertices[used]
    faces=remap.reshape(-1,3).astype(np.uint32)
    return vertices,faces


def scene(vertices,faces):
    s=o3d.t.geometry.RaycastingScene()
    s.add_triangles(o3d.core.Tensor(np.ascontiguousarray(vertices,dtype=np.float32)),
                    o3d.core.Tensor(np.ascontiguousarray(faces,dtype=np.uint32)))
    return s


def distances(s,points):
    output=np.empty(len(points),np.float32)
    for first in range(0,len(points),100000):
        last=min(len(points),first+100000)
        output[first:last]=s.compute_distance(o3d.core.Tensor(np.ascontiguousarray(points[first:last],dtype=np.float32))).numpy()
    return output


def boundary(vertices,faces,bounds):
    edges=np.sort(np.concatenate([faces[:,[0,1]],faces[:,[1,2]],faces[:,[2,0]]]),axis=1)
    unique,counts=np.unique(edges,axis=0,return_counts=True)
    exterior=unique[counts==1];nonmanifold=int((counts>2).sum())
    planes=[]
    for axis,value in [(0,bounds[0]),(0,bounds[2]),(1,bounds[1]),(1,bounds[3])]:
        e=exterior[(np.abs(vertices[exterior,axis]-value)<1e-4).all(axis=1)]
        positions=vertices[e].reshape(-1,3)
        planes.append(dict(axis=axis,value=value,edges=len(e),positions=positions))
    return planes,nonmanifold,len(exterior)


def process(state,name,bounds):
    t0=time.perf_counter();points,inputs=gather(state,bounds);config=state.config
    if len(points)>config['max_points_per_job']:
        x0,y0,x1,y1=bounds
        if min(x1-x0,y1-y0)<=12.5:raise MemoryError('Dense minimum-size job needs larger memory; no sampling applied')
        xm,ym=(x0+x1)/2,(y0+y1)/2
        return [process(state,name+str(i),b) for i,b in enumerate([(x0,y0,xm,ym),(xm,y0,x1,ym),(x0,ym,xm,y1),(xm,ym,x1,y1)])]
    signature=key(dict(stage='reconstruct-v2',bounds=bounds,inputs=inputs,config=config))
    task='mesh-'+name
    with state.lock(task):
        if state.valid(task,signature):return dict(task=task,skipped=True)
        state.start(task,signature)
        try:
            # Exact duplicate XYZ records add no geometry. Their original
            # attributes and multiplicity remain preserved in the raw LAZ.
            before=len(points);points=np.unique(points,axis=0)
            cloud=o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points.astype(float)))
            cloud.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=config['normal_radius_m'],max_nn=config['normal_max_nn']))
            cloud.orient_normals_consistent_tangent_plane(16)
            normals=np.asarray(cloud.normals)
            # Choose the connected surface's overall orientation without forcing
            # each individual normal upward: undersides remain representable.
            if np.median(normals[:,2])<0:cloud.normals=o3d.utility.Vector3dVector(-normals)
            full=o3d.geometry.TriangleMesh.create_from_point_cloud_ball_pivoting(cloud,o3d.utility.DoubleVector(config['ball_radii_m']))
            if not len(full.triangles):raise ValueError('No reliable BPA surface')
            vertices,faces=clip_core(np.asarray(full.vertices),np.asarray(full.triangles),bounds)
            x0,y0,x1,y1=bounds
            core=points[(points[:,0]>=x0)&(points[:,0]<=x1)&(points[:,1]>=y0)&(points[:,1]<=y1)]
            base_scene=scene(vertices,faces)
            baseline=distances(base_scene,core)
            directory=state.root/'data/meshes'/name;directory.mkdir(parents=True,exist_ok=True)
            base_path=directory/'full.npz'
            np.savez_compressed(base_path,vertices=vertices,faces=faces,source_points=core,source_distances=baseline)
            paths=[base_path];levels=[]
            for number,budget in enumerate(config['lod_errors_m']):
                chosen=faces;extra=0.;reported=0.
                if budget:
                    tolerance=budget
                    for attempt in range(6):
                        output=np.empty(faces.size,np.uint32);error=np.zeros(1,np.float32)
                        count=mo.simplify(output,np.ascontiguousarray(faces.ravel()),np.ascontiguousarray(vertices),
                                          target_index_count=3,target_error=tolerance,
                                          options=mo.SIMPLIFY_LOCK_BORDER|mo.SIMPLIFY_ERROR_ABSOLUTE,result_error=error)
                        candidate=output[:count].reshape(-1,3)
                        if not len(candidate):raise ValueError('Simplifier returned an empty supported surface')
                        candidate_scene=scene(vertices,candidate)
                        extra=float(np.maximum(0,distances(candidate_scene,core)-baseline).max(initial=0))
                        # Test all original finest-mesh vertices as well, including
                        # clipped seam intersections, not only a random sample.
                        vertex_error=float(distances(candidate_scene,vertices).max(initial=0))
                        # A simplification candidate must also retain manifold
                        # edge incidence. Reject it rather than lose the tile.
                        topology_ok=boundary(vertices,candidate,bounds)[1]==0
                        if topology_ok and max(extra,vertex_error)<=budget+1e-4:
                            chosen=candidate;extra=max(extra,vertex_error);reported=float(error[0]);break
                        tolerance*=.5
                    else:extra=0. # Full measured reconstruction remains the safe fallback.
                used,remap=np.unique(chosen,return_inverse=True)
                v=vertices[used];f=remap.reshape(-1,3).astype(np.uint32)
                plane_edges,nonmanifold,open_edges=boundary(v,f,bounds)
                if nonmanifold:raise ValueError(f'Nonmanifold reconstructed edges: {nonmanifold}')
                mesh=o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(v.astype(float)),o3d.utility.Vector3iVector(f.astype(np.int32)))
                mesh.compute_vertex_normals();norm=np.asarray(mesh.vertex_normals).astype('<f4')
                out=directory/f'lod-{number}.npz'
                np.savez_compressed(out,vertices=v,faces=f,normals=norm)
                paths.append(out)
                seam=directory/f'seams-{number}.npz'
                np.savez_compressed(seam,**{f'plane_{i}':p['positions'] for i,p in enumerate(plane_edges)})
                paths.append(seam)
                levels.append(dict(level=number,budget_m=budget,tested_vertex_and_source_extra_max_m=extra,
                                   meshoptimizer_reported_error_m=reported,vertices=len(v),triangles=len(f),
                                   open_edges=open_edges,negative_normal_fraction=float((norm[:,2]<-.1).mean())))
            metrics=dict(bounds=bounds,input_returns_with_halo=before,unique_points_with_halo=len(points),
                         core_points=len(core),finest_vertices=len(vertices),finest_triangles=len(faces),
                         source_distance_p50_m=float(np.quantile(baseline,.5)),source_distance_p95_m=float(np.quantile(baseline,.95)),
                         source_distance_p99_m=float(np.quantile(baseline,.99)),source_distance_max_m=float(baseline.max()),
                         unsupported_points_over_025m=int((baseline>.25).sum()),levels=levels,
                         seconds=time.perf_counter()-t0,publishable=False,
                         release_gates_pending=['shared seam topology and mixed-LOD checks','compression and quantization','rendered inspection'],
                         limits='Vertex/source-point tests are not a certified continuous Hausdorff bound. Negative normals are candidates, not confirmed overhang coverage.')
            report=directory/'validation.json';atomic_json(report,metrics);paths.append(report)
            receipt=state.stage(task,signature,paths,metrics)
            print(json.dumps(dict(event='checkpoint_ready',**receipt,metrics=metrics)),flush=True)
            return receipt
        except Exception as error:
            state.fail(task,error);raise


def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--tile',type=int,required=True);args=p.parse_args()
    root=Path(args.root);state=State(root,json.loads((root/'config.json').read_text()))
    c=state.config;size=c['tile_size_m'];nx=int(np.ceil((c['bounds'][2]-c['bounds'][0])/size))
    iy,ix=divmod(args.tile,nx)
    if not 0<=ix<nx or not 0<=iy<nx:raise ValueError('Tile outside configured region')
    process(state,f'{args.tile:04d}',[ix*size,iy*size,(ix+1)*size,(iy+1)*size])


if __name__=='__main__':main()
