"""Propose redundant-facet removal for a COARSE LOD, never alter finest data.

Local tangent empty-disk voting is only a heuristic proposal. Every removal
must independently pass the continuous original-to-candidate error bound.
Selected faces are an exact subset, so reverse geometric error is exactly zero.
Sharp/ambiguous neighborhoods and a 1.5 m divider guard are retained unchanged.
"""
import numpy as np
from scipy.spatial import cKDTree

def propose(vertices,faces,observations,bounds):
    v=np.asarray(vertices);f=np.asarray(faces);source=np.asarray(observations)
    tree=cKDTree(source)
    distance,neighbors=tree.query(v,k=64,distance_upper_bound=1.2,workers=4)
    valid=neighbors<len(source);neighbors=np.minimum(neighbors,len(source)-1)
    normals=np.empty((len(v),3),np.float64);confident=np.zeros(len(v),bool)
    for first in range(0,len(v),10000):
        end=min(len(v),first+10000);d=distance[first:end,:32];ids=neighbors[first:end,:32]
        points=source[ids].astype(np.float64)
        h=np.maximum(np.where(np.isfinite(d[:,15]),d[:,15],.6),.05)
        weights=np.exp(-np.minimum(d,10)**2/h[:,None]**2)*valid[first:end,:32]
        total=np.maximum(weights.sum(axis=1),1e-12)
        mean=(points*weights[:,:,None]).sum(axis=1)/total[:,None]
        centered=points-mean[:,None,:]
        covariance=np.einsum('bni,bnj,bn->bij',centered,centered,weights)/total[:,None,None]
        eigenvalue,eigenvector=np.linalg.eigh(covariance)
        normals[first:end]=eigenvector[:,:,0]
        confident[first:end]=(valid[first:end,:32].sum(axis=1)>=12)&(eigenvalue[:,0]<.12*np.maximum(eigenvalue[:,1]+eigenvalue[:,2],1e-15))&(eigenvalue[:,1]>.05*np.maximum(eigenvalue[:,2],1e-15))
    keep=np.ones(len(f),bool);protected=np.zeros(len(f),bool);tested=0
    for first in range(0,len(f),3000):
        end=min(len(f),first+3000);indices=f[first:end];triangle=v[indices].astype(np.float64)
        margin=np.minimum.reduce([triangle[:,:,0]-bounds[0],bounds[2]-triangle[:,:,0],triangle[:,:,1]-bounds[1],bounds[3]-triangle[:,:,1]])
        guard=(margin<1.5).any(axis=1);protected[first:end]=guard
        known=np.zeros((len(indices),3),bool);votes=np.zeros((len(indices),3),bool)
        for corner in range(3):
            id=indices[:,corner];origin=triangle[:,corner];n=normals[id]
            a=triangle[:,(corner+1)%3]-origin;b=triangle[:,(corner+2)%3]-origin
            a-=n*(a*n).sum(axis=1)[:,None];b-=n*(b*n).sum(axis=1)[:,None]
            aa=(a*a).sum(axis=1);ab=(a*b).sum(axis=1);bb=(b*b).sum(axis=1)
            determinant=aa*bb-ab*ab
            stable=determinant>1e-12
            den=np.maximum(determinant,1e-12)*2
            center=a*(bb*(aa-ab)/den)[:,None]+b*(aa*(bb-ab)/den)[:,None]
            radius2=(center*center).sum(axis=1)
            # A complete 3D neighborhood must cover the projected disk and a
            # 0.3 m normal slab; otherwise retain the ambiguous source faces.
            search_radius=np.where(valid[id,-1],distance[id,-1],1.2)
            complete=4*radius2+.3**2<search_radius**2
            known[:,corner]=stable&complete&confident[id]
            delta=source[neighbors[id]].astype(np.float64)-origin[:,None,:]
            normal_distance=np.einsum('bni,bi->bn',delta,n)
            tangent=delta-normal_distance[:,:,None]*n[:,None,:]
            metric=((tangent-center[:,None,:])**2).sum(axis=2)-radius2[:,None]
            inside=valid[id]&(np.abs(normal_distance)<.3)&(metric<-(1e-7*np.maximum(radius2,.01))[:,None])
            votes[:,corner]=~inside.any(axis=1)
        count=known.sum(axis=1);support=(votes&known).sum(axis=1)
        remove=(count>=2)&(support*2<count+1)&~guard
        keep[first:end]=~remove;tested+=int(((count>=2)&~guard).sum())
    return keep,dict(proposal='local-tangent-empty-disk-consensus',input_triangles=len(f),kept_triangles=int(keep.sum()),
        removed_triangles=int((~keep).sum()),guarded_triangles=int(protected.sum()),reliable_triangles_tested=tested,
        finest_geometry_modified=False,requires_independent_continuous_error_check=True)
