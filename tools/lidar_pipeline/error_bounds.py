"""Conservative continuous mesh-to-mesh geometric error checks.

Distance to one convex triangle is convex. Bounding all three corners of a
reference triangle against ONE candidate triangle therefore bounds every
point of that reference triangle. Ambiguous regions subdivide adaptively.
The accelerator chooses candidates only; correctness does not depend on it
finding the exact closest triangle. Floating arithmetic gets an explicit margin.
"""
from __future__ import annotations
import numpy as np
import open3d as o3d


def corner_distances(points,triangles):
    p=np.asarray(points,dtype=np.float64);t=np.asarray(triangles,dtype=np.float64)
    a,b,c=t[:,0],t[:,1],t[:,2]
    e0,e1=b-a,c-a;normal=np.cross(e0,e1)
    n2=(normal*normal).sum(axis=1)
    s=p-a[:,None,:]
    signed=(s*normal[:,None,:]).sum(axis=2)
    q=s-signed[:,:,None]*normal[:,None,:]/np.maximum(n2[:,None,None],1e-30)
    d00=(e0*e0).sum(axis=1);d01=(e0*e1).sum(axis=1);d11=(e1*e1).sum(axis=1)
    d20=(q*e0[:,None,:]).sum(axis=2);d21=(q*e1[:,None,:]).sum(axis=2)
    den=d00*d11-d01*d01
    v=(d11[:,None]*d20-d01[:,None]*d21)/np.maximum(den[:,None],1e-30)
    w=(d00[:,None]*d21-d01[:,None]*d20)/np.maximum(den[:,None],1e-30)
    inside=(v>=0)&(w>=0)&(v+w<=1)&(n2[:,None]>1e-24)
    distance=np.where(inside,np.abs(signed)/np.sqrt(np.maximum(n2[:,None],1e-30)),np.inf)
    for start,end in [(a,b),(b,c),(c,a)]:
        edge=end-start;delta=p-start[:,None,:]
        fraction=np.clip((delta*edge[:,None,:]).sum(axis=2)/np.maximum((edge*edge).sum(axis=1)[:,None],1e-30),0,1)
        segment=np.linalg.norm(delta-fraction[:,:,None]*edge[:,None,:],axis=2)
        distance=np.minimum(distance,segment)
    return distance


def subdivide(triangles):
    a,b,c=triangles[:,0],triangles[:,1],triangles[:,2]
    ab,bc,ca=(a+b)/2,(b+c)/2,(c+a)/2
    return np.concatenate([np.stack([a,ab,ca],1),np.stack([ab,b,bc],1),
                           np.stack([ca,bc,c],1),np.stack([ab,bc,ca],1)])


def directed(reference_v,reference_f,candidate_v,candidate_f,limit,max_depth=10,max_tests=4000000):
    candidate_v=np.asarray(candidate_v,dtype=np.float32);candidate_f=np.asarray(candidate_f,dtype=np.uint32)
    scene=o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.core.Tensor(np.ascontiguousarray(candidate_v)),
                        o3d.core.Tensor(np.ascontiguousarray(candidate_f)))
    epsilon=1e-5;maximum=0.;tested=0;subdivisions=0
    for first in range(0,len(reference_f),20000):
        pending=np.asarray(reference_v[reference_f[first:first+20000]],dtype=np.float64)
        parents=np.arange(first,min(first+20000,len(reference_f)))
        for depth in range(max_depth+1):
            if not len(pending):break
            if tested+len(pending)>max_tests:
                return dict(certified=False,reason='verification_work_cap',tested=tested,unresolved=len(pending),
                            unresolved_reference_faces=np.unique(parents).tolist())
            ids=scene.compute_closest_points(o3d.core.Tensor(np.ascontiguousarray(pending.mean(axis=1),dtype=np.float32)))['primitive_ids'].numpy()
            bounds=corner_distances(pending,candidate_v[candidate_f[ids]]).max(axis=1)
            tested+=len(pending)
            passed=bounds+epsilon<=limit
            if passed.any():maximum=max(maximum,float(bounds[passed].max()))
            unresolved=pending[~passed]
            unresolved_parents=parents[~passed]
            if not len(unresolved):break
            if depth==max_depth:
                return dict(certified=False,reason='error_or_depth_limit',tested=tested,
                            unresolved=len(unresolved),unresolved_bound_m=float(bounds[~passed].max()),
                            unresolved_reference_faces=np.unique(unresolved_parents).tolist())
            # Cap before allocating a four-times larger batch.
            if tested+len(unresolved)*4>max_tests:
                return dict(certified=False,reason='verification_work_cap',tested=tested,unresolved=len(unresolved),
                            unresolved_reference_faces=np.unique(unresolved_parents).tolist())
            subdivisions+=len(unresolved);pending=subdivide(unresolved)
            parents=np.tile(unresolved_parents,4)
    return dict(certified=True,upper_bound_m=maximum+epsilon,numeric_margin_m=epsilon,
                tested=tested,subdivisions=subdivisions)


def symmetric(reference_v,reference_f,candidate_v,candidate_f,limit,**kwargs):
    if np.array_equal(reference_v,candidate_v) and np.array_equal(reference_f,candidate_f):
        return dict(certified=True,upper_bound_m=0.,method='identical mesh')
    forward=directed(reference_v,reference_f,candidate_v,candidate_f,limit,**kwargs)
    if not forward['certified']:return dict(certified=False,forward=forward)
    reverse=directed(candidate_v,candidate_f,reference_v,reference_f,limit,**kwargs)
    return dict(certified=reverse['certified'],upper_bound_m=max(forward.get('upper_bound_m',0),reverse.get('upper_bound_m',0)),
                forward=forward,reverse=reverse,
                method='Adaptive convex-triangle distance bounds, both directions; reconstructed mesh only, not survey accuracy')


def collect(reference_v,reference_f,candidate_v,candidate_f,limit,max_depth=10,max_tests=8000000,batch=4000):
    """Conservative directed certificate collecting ALL unresolved face regions.

    A closest-point sample may reject a proposal early, but never accepts it.
    Acceptance still requires convex corner bounds covering every triangle.
    A cap or depth limit returns all unproved parents, including unvisited ones;
    local feedback must never mistake an unvisited region for a proved region.
    """
    if not np.isfinite(limit) or limit<=1e-5:raise ValueError('Positive budget above numerical margin required')
    rv=np.asarray(reference_v,dtype=np.float32);rf=np.asarray(reference_f,dtype=np.uint32)
    cv=np.asarray(candidate_v,dtype=np.float32);cf=np.asarray(candidate_f,dtype=np.uint32)
    if not len(rf) or not len(cf):raise ValueError('Nonempty surfaces required')
    accelerator=o3d.t.geometry.RaycastingScene()
    accelerator.add_triangles(o3d.core.Tensor(np.ascontiguousarray(cv)),o3d.core.Tensor(np.ascontiguousarray(cf)))
    maximum=0.;tested=0;subdivisions=0;epsilon=1e-5;failed=np.zeros(len(rf),bool);capped=False
    for first in range(0,len(rf),batch):
        stop=min(first+batch,len(rf));parents=np.arange(first,stop)
        pending=np.asarray(rv[rf[first:stop]],dtype=np.float64)
        for depth in range(max_depth+1):
            if not len(pending):break
            if tested+len(pending)>max_tests:
                failed[parents]=True;failed[stop:]=True;capped=True;break
            centers=pending.mean(axis=1)
            nearest=accelerator.compute_closest_points(o3d.core.Tensor(np.ascontiguousarray(centers,dtype=np.float32)))
            ids=nearest['primitive_ids'].numpy()
            bounds=corner_distances(pending,cv[cf[ids]]).max(axis=1);tested+=len(pending)
            passed=bounds+epsilon<=limit
            if passed.any():maximum=max(maximum,float(bounds[passed].max()))
            # Approximate search can only over-reject here. The convex corner
            # test above is the sole acceptance rule, independent of search.
            sample_distance=np.linalg.norm(centers-nearest['points'].numpy(),axis=1)
            rejected=(~passed)&(sample_distance+epsilon>limit)
            failed[parents[rejected]]=True
            unresolved=(~passed)&(~failed[parents])
            if depth==max_depth:
                failed[parents[unresolved]]=True;break
            remainder=pending[unresolved];remaining_parents=parents[unresolved]
            if not len(remainder):break
            if tested+len(remainder)*4>max_tests:
                failed[remaining_parents]=True;failed[stop:]=True;capped=True;break
            subdivisions+=len(remainder);pending=subdivide(remainder);parents=np.tile(remaining_parents,4)
        if capped:break
    rejected_ids=np.flatnonzero(failed)
    return dict(certified=not len(rejected_ids),upper_bound_m=maximum+epsilon,numeric_margin_m=epsilon,
        tested=tested,subdivisions=subdivisions,work_cap_reached=capped,
        unresolved_reference_faces=rejected_ids.tolist(),unresolved=len(rejected_ids),
        method='Continuous convex-triangle bounds; unproved faces rejected, no sample-based acceptance')
