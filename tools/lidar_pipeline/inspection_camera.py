"""Choose an actual visible steep facet and prove camera clearance locally.

This avoids placing a programmatic close-up inside the reconstructed mountain.
It is a model-space visibility check, not a geological solid/field safety claim.
"""
import numpy as np
import open3d as o3d
import pyproj

def select(vertices,faces):
    v=np.asarray(vertices,dtype=np.float32);f=np.asarray(faces,dtype=np.uint32)
    points=v[f];centers=points.mean(1);normal=np.cross(points[:,1]-points[:,0],points[:,2]-points[:,0])
    length=np.linalg.norm(normal,axis=1);normal/=np.maximum(length[:,None],1e-20)
    normal[normal[:,2]<0]*=-1
    candidates=np.flatnonzero((length>.03)&(np.abs(normal[:,2])<.6))
    if not len(candidates):raise ValueError('No supported steep facet for an inspection preset')
    midpoint=(v.min(0)+v.max(0))/2
    score=np.linalg.norm(centers[candidates]-midpoint,axis=1)
    candidates=candidates[np.argsort(score,kind='stable')[:1024]]
    scene=o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.core.Tensor(v),o3d.core.Tensor(f))
    for distance in [25.,35.,50.]:
        targets=centers[candidates];offsets=normal[candidates]*distance;offsets[:,2]+=8
        camera=targets+offsets;ranges=np.linalg.norm(offsets,axis=1);direction=-offsets/ranges[:,None]
        rays=np.column_stack([camera,direction]).astype(np.float32)
        hit=scene.cast_rays(o3d.core.Tensor(rays))['t_hit'].numpy()
        clearance=scene.compute_distance(o3d.core.Tensor(camera)) .numpy()
        # Keep the camera above the highest reconstructed surface at its XY.
        origins=camera.copy();origins[:,2]=v[:,2].max()+200
        vertical=np.column_stack([origins,np.tile([0,0,-1],(len(origins),1))]).astype(np.float32)
        down=scene.cast_rays(o3d.core.Tensor(vertical))['t_hit'].numpy()
        highest=origins[:,2]-down
        valid=np.flatnonzero(np.isfinite(hit)&(np.abs(hit-ranges)<.1)&(clearance>=5)&(camera[:,2]>highest+5))
        if len(valid):
            i=int(valid[0]);return dict(target_enu=targets[i].astype(float).tolist(),camera_enu=camera[i].astype(float).tolist(),
                visible_source_face=int(candidates[i]),camera_clearance_m=float(clearance[i]),
                first_hit_target_difference_m=float(abs(hit[i]-ranges[i])),
                source_slope_degrees=float(np.rad2deg(np.arccos(abs(normal[candidates[i],2])))),
                method='Full finest triangle-union raycast, >=5m nearest-surface clearance, above local highest hit; not a solid-geometry or field-safety claim')
    raise ValueError('No unobstructed close camera with required measured clearance')

def geographic(frame,proof):
    target=np.asarray(proof['target_enu'])@frame.rotation+frame.origin
    camera=np.asarray(proof['camera_enu'])@frame.rotation+frame.origin
    lon,lat,_=pyproj.Transformer.from_crs(4978,4979,always_xy=True).transform(*target,errcheck=True)
    lam,phi=np.deg2rad([lon,lat]);sl,cl=np.sin(lam),np.cos(lam);sp,cp=np.sin(phi),np.cos(phi)
    rotation=np.array([[-sl,cl,0],[-sp*cl,-sp*sl,cp],[cp*cl,cp*sl,sp]])
    offset=(camera-target)@rotation.T;distance=np.linalg.norm(offset)
    return dict(target_ecef=target.tolist(),heading_degrees=float(np.rad2deg(np.arctan2(-offset[0],-offset[1]))),
        pitch_degrees=float(-np.rad2deg(np.arcsin(offset[2]/distance))),range_metres=float(distance),validation=proof)
