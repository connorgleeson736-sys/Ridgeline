"""Compact glTF display attributes; authoritative source meshes stay unchanged.

Optional positions snap to ONE global, binary-exact ENU/glTF grid. Local
Uint16 offsets differ across chunks, but decoded shared positions are identical.
Triangle correspondence gives a continuous error bound, not a sampling claim.
"""
import json,struct,hashlib
import numpy as np
import meshoptimizer as mo

POSITION_STEP=1/2048 # 0.48828125 mm; fine cluster span must fit Uint16.


def positions_on_grid(positions,step=POSITION_STEP):
    p=np.asarray(positions,dtype=np.float64)
    if not np.isfinite(p).all() or not np.isfinite(step) or step<=0 or np.frexp(step)[0]!=.5:
        raise ValueError('Finite positions and a binary power-of-two grid step required')
    absolute=np.rint(p/step).astype(np.int64);origin=absolute.min(0)
    local=absolute-origin
    if local.max()>65535:raise ValueError('Render cluster exceeds Uint16 global-grid span; split it spatially')
    return local.astype('<u2'),origin*step,absolute*step


def compact(glb,metrics,position_step=None,extra_error_m=0.):
    length=struct.unpack_from('<I',glb,12)[0];doc=json.loads(glb[20:20+length]);binary=glb[28+length:]
    ext='EXT_meshopt_compression';old=doc['bufferViews'][0]['extensions'][ext]
    encoded=binary[old['byteOffset']:old['byteOffset']+old['byteLength']]
    attributes=mo.decode_vertex_buffer(old['count'],old['byteStride'],encoded)
    if old['byteStride']!=32:raise ValueError('Expected verified full-precision intermediate attributes')
    old_normal=attributes[:,3:6].astype(float);normal=np.rint(np.clip(old_normal,-1,1)*32767).astype('<i2')
    decoded_normal=normal.astype(float)/32767
    decoded_normal/=np.linalg.norm(decoded_normal,axis=1,keepdims=True)
    angle=float(np.rad2deg(np.arccos(np.clip(np.sum(old_normal*decoded_normal,axis=1)/np.linalg.norm(old_normal,axis=1),-1,1))).max())
    uv=np.rint(np.clip(attributes[:,6:8].astype(float),0,1)*65535).astype('<u2')
    uv_error=np.abs(uv.astype(float)/65535-attributes[:,6:8])
    origin=None;geometric_error=0.
    if position_step is not None:
        position,origin,decoded_position=positions_on_grid(attributes[:,:3],position_step)
        geometric_error=float(np.linalg.norm(decoded_position-attributes[:,:3].astype(float),axis=1).max())
        dtype=np.dtype(dict(names=['p','n','uv'],formats=[('<u2',3),('<i2',3),('<u2',2)],offsets=[0,8,16],itemsize=20))
        doc['nodes'][0].update(translation=origin.tolist(),scale=[position_step]*3)
        doc['accessors'][0].update(componentType=5123,min=position.min(0).astype(int).tolist(),max=position.max(0).astype(int).tolist())
        normal_offset,uv_offset=8,16
    else:
        position=attributes[:,:3]
        dtype=np.dtype(dict(names=['p','n','uv'],formats=[('<f4',3),('<i2',3),('<u2',2)],offsets=[0,12,20],itemsize=24))
        normal_offset,uv_offset=12,20
    packed=np.zeros(len(attributes),dtype);packed['p']=position;packed['n']=normal;packed['uv']=uv
    ev=mo.encode_vertex_buffer(packed,vertex_count=len(packed),vertex_size=dtype.itemsize)
    if mo.decode_vertex_buffer(len(packed),dtype.itemsize,ev).tobytes()!=packed.tobytes():raise ValueError('Compact attribute codec mismatch')
    old_i=doc['bufferViews'][1]['extensions'][ext]
    ei=binary[old_i['byteOffset']:old_i['byteOffset']+old_i['byteLength']]
    image_view=doc['bufferViews'][2];image=binary[image_view['byteOffset']:image_view['byteOffset']+image_view['byteLength']]
    pad=lambda b:b+b'\0'*((-len(b))%4)
    io=len(pad(ev));po=io+len(pad(ei));payload=pad(ev)+pad(ei)+image
    raw_v=len(packed)*dtype.itemsize;raw_i=doc['bufferViews'][1]['byteLength']
    doc['buffers'][0]['byteLength']=len(payload);doc['buffers'][1]['byteLength']=raw_v+raw_i
    view=doc['bufferViews'][0];view.update(byteLength=raw_v,byteStride=dtype.itemsize)
    view['extensions'][ext].update(byteOffset=0,byteLength=len(ev),byteStride=dtype.itemsize)
    doc['bufferViews'][1]['byteOffset']=raw_v
    doc['bufferViews'][1]['extensions'][ext].update(byteOffset=io,byteLength=len(ei))
    doc['bufferViews'][2]['byteOffset']=po
    doc['accessors'][1].update(byteOffset=normal_offset,componentType=5122,normalized=True)
    doc['accessors'][2].update(byteOffset=uv_offset,componentType=5123,normalized=True)
    for name in ['extensionsUsed','extensionsRequired']:doc[name].append('KHR_mesh_quantization')
    details=dict(position_step_m=position_step,position_rounding_max_m=geometric_error,
        corresponding_triangle_bound_both_directions_m=geometric_error+extra_error_m+1e-8,
        normal_max_angle_degrees=angle,uv_max_error_normalized=uv_error.max(0).tolist(),
        shared_positions='One global binary grid, independent of chunk origin' if position_step else 'Original localized Float32 positions',
        authoritative_mesh_modified=False)
    ridgeline=doc.setdefault('extras',{}).setdefault('ridgeline',{})
    ridgeline['display_quantization']=details
    if 'lod' in ridgeline:
        ridgeline['lod']['upper_bound_m']+=geometric_error+extra_error_m+1e-8
        ridgeline['lod']['packing_error_added_m']=geometric_error+extra_error_m+1e-8
    js=json.dumps(doc,separators=(',',':'),allow_nan=False).encode();js+=b' '*((-len(js))%4);payload=pad(payload)
    result=struct.pack('<III',0x46546c67,2,28+len(js)+len(payload))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(payload),0x004e4942)+payload
    report=dict(metrics,baseline_float32_glb_bytes=len(glb),glb_bytes=len(result),gpu_attribute_bytes=raw_v,
        meshopt_bytes=len(ev)+len(ei),attributes_sha256=hashlib.sha256(packed.tobytes()).hexdigest(),
        geometry_unchanged_by_packing=position_step is None,triangle_topology_preserved=True,
        display_quantization=details,encoded_size_reduction_fraction=1-len(result)/len(glb),
        packed_world_bound=ridgeline.get('lod'))
    return result,report
