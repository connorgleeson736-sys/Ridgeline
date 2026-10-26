"""Independent release gates for full-XYZ LOD geometry; no QEM error trust."""
import numpy as np
import open3d as o3d
from .error_bounds import symmetric
from .reconstruct import distances,scene

def open_edge_coordinates(vertices,faces):
    edges=np.sort(np.concatenate([faces[:,[0,1]],faces[:,[1,2]],faces[:,[2,0]]]),axis=1)
    unique,counts=np.unique(edges,axis=0,return_counts=True)
    coordinates=vertices[unique[counts==1]].copy()
    a,b=coordinates[:,0],coordinates[:,1]
    swap=(a[:,0]>b[:,0])|((a[:,0]==b[:,0])&(a[:,1]>b[:,1]))|((a[:,0]==b[:,0])&(a[:,1]==b[:,1])&(a[:,2]>b[:,2]))
    coordinates[swap]=coordinates[swap,::-1]
    flat=coordinates.reshape(-1,6)
    return flat[np.lexsort(tuple(flat[:,i] for i in range(5,-1,-1)))]

def validate(reference_v,reference_f,v,f,budget):
    if not len(v) or not len(f) or not np.isfinite(v).all() or f.max()>=len(v):
        raise ValueError('Invalid candidate arrays')
    mesh=o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(v.astype(float)),o3d.utility.Vector3iVector(f.astype(np.int32)))
    topology=dict(edge_manifold=mesh.is_edge_manifold(allow_boundary_edges=True),
                  vertex_manifold=mesh.is_vertex_manifold(),orientable=mesh.is_orientable())
    borders_equal=np.array_equal(open_edge_coordinates(reference_v,reference_f),open_edge_coordinates(v,f))
    vertex_max=float(distances(scene(v,f),reference_v).max(initial=0))
    if all(topology.values()) and borders_equal and vertex_max<=budget:
        bound=symmetric(reference_v,reference_f,v,f,budget)
    else:bound=dict(certified=False,reason='topology_border_or_vertex_gate')
    return dict(accepted=bool(all(topology.values()) and borders_equal and bound['certified']),
                budget_m=budget,vertices=len(v),triangles=len(f),reference_triangles=len(reference_f),
                reduction_fraction=1-len(f)/len(reference_f),topology=topology,
                exact_open_borders=borders_equal,reference_vertex_max_m=vertex_max,continuous_bound=bound)
