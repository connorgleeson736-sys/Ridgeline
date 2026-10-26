"""Split pinched vertex fans without moving or removing any triangle geometry."""
import numpy as np
import open3d as o3d

def split_fans(vertices,faces):
    vertices=np.asarray(vertices,dtype=np.float32);faces=np.asarray(faces,dtype=np.uint32)
    mesh=o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(vertices.astype(float)),
                                  o3d.utility.Vector3iVector(faces.astype(np.int32)))
    if not mesh.is_edge_manifold(allow_boundary_edges=True):
        raise ValueError('Nonmanifold edges require a separate audited repair')
    problems=np.asarray(mesh.get_non_manifold_vertices(),dtype=np.int64)
    if not len(problems):return vertices,faces,np.arange(len(vertices),dtype=np.uint32),dict(split_vertices=0,geometry_identical=True)
    result=faces.copy();positions=[p for p in vertices];source=list(range(len(vertices)))
    flat=faces.ravel();order=np.argsort(flat,kind='stable')
    offsets=np.r_[0,np.cumsum(np.bincount(flat,minlength=len(vertices)))]
    for vertex in problems:
        incident=(order[offsets[vertex]:offsets[vertex+1]]//3).tolist()
        adjacent={f:[] for f in incident};by_neighbor={}
        for f in incident:
            for other in result[f]:
                if other!=vertex:by_neighbor.setdefault(int(other),[]).append(f)
        for group in by_neighbor.values():
            if len(group)==2:
                a,b=group;adjacent[a].append(b);adjacent[b].append(a)
        remaining=set(incident);components=[]
        while remaining:
            stack=[remaining.pop()];component=[]
            while stack:
                f=stack.pop();component.append(f)
                for neighbor in adjacent[f]:
                    if neighbor in remaining:remaining.remove(neighbor);stack.append(neighbor)
            components.append(component)
        for component in components[1:]:
            new=len(positions);positions.append(vertices[vertex]);source.append(int(vertex))
            for f in component:result[f,result[f]==vertex]=new
    positions=np.asarray(positions,dtype=np.float32);source=np.asarray(source,dtype=np.uint32)
    if not np.array_equal(positions[result],vertices[faces]):raise AssertionError('Fan split changed geometry')
    repaired=o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(positions.astype(float)),
                                      o3d.utility.Vector3iVector(result.astype(np.int32)))
    if not repaired.is_vertex_manifold() or not repaired.is_edge_manifold(allow_boundary_edges=True):
        raise ValueError('Vertex-fan split did not produce manifold patches')
    return positions,result,source,dict(split_vertices=len(positions)-len(vertices),
                                       original_pinches=len(problems),geometry_identical=True)
