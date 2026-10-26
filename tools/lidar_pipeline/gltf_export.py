"""Full-XYZ glTF/meshopt export with explicit geographic and visual provenance.

Flat shading preserves creases/intersecting sheets. Only identical COMPLETE
attributes weld. Geometry is never smoothed, closed or replaced by a heightmap.
"""
from pathlib import Path
import hashlib
import importlib.metadata
import json
import struct
import subprocess
import time
import numpy as np
import meshoptimizer as mo
from . import coordinates,imagery,error_bounds,quantization
from .mesh_codec import cyclic_faces
from .state import atomic_bytes,atomic_json,digest,key


def build(positions,faces,uv,png,extras):
    p=np.asarray(positions,dtype='<f4');f=np.asarray(faces,dtype='<u4');uv=np.asarray(uv,dtype='<f4')
    if len(p)!=len(uv) or not np.isfinite(p).all() or not np.isfinite(uv).all() or not len(f) or f.max()>=len(p):
        raise ValueError('Invalid glTF attributes')
    triangle=p[f].astype(np.float64);normal=np.cross(triangle[:,1]-triangle[:,0],triangle[:,2]-triangle[:,0])
    length=np.linalg.norm(normal,axis=1);degenerate=length<1e-15
    normal[~degenerate]/=length[~degenerate,None];normal[degenerate]=[0,1,0]
    flat=np.column_stack([p[f.ravel()],np.repeat(normal.astype('<f4'),3,axis=0),uv[f.ravel()]])
    attributes,inverse=np.unique(flat,axis=0,return_inverse=True)
    attributes=np.ascontiguousarray(attributes,dtype='<f4');indices=inverse.astype('<u4')
    if not np.array_equal(attributes[indices,:3],p[f.ravel()]):raise AssertionError('Attribute welding moved geometry')
    before=cyclic_faces(indices.reshape(-1,3));optimized=np.empty_like(indices)
    mo.optimize_vertex_cache(optimized,indices,index_count=len(indices),vertex_count=len(attributes))
    def face_set(a):return np.sort(np.ascontiguousarray(a).view(np.dtype((np.void,12))).reshape(-1))
    if not np.array_equal(face_set(before),face_set(cyclic_faces(optimized.reshape(-1,3)))):
        raise AssertionError('GPU cache optimization changed triangle corners or winding')
    indices=optimized
    # Reorder fetches without altering complete attributes; verify the remap.
    fetched=np.empty_like(attributes)
    count=mo.optimize_vertex_fetch(fetched,indices,attributes,index_count=len(indices),vertex_count=len(attributes),vertex_size=32)
    fetched=fetched[:count]
    fields=np.dtype([(str(i),'<f4') for i in range(8)])
    mapping=np.searchsorted(attributes.view(fields).ravel(),fetched.view(fields).ravel())
    if not np.array_equal(attributes[mapping],fetched):raise AssertionError('Vertex fetch optimization changed attributes')
    if not np.array_equal(face_set(before),face_set(cyclic_faces(mapping[indices].astype('<u4').reshape(-1,3)))):
        raise AssertionError('Vertex fetch optimization changed geometry')
    attributes=fetched
    encoded_v=mo.encode_vertex_buffer(attributes,vertex_count=len(attributes),vertex_size=32)
    encoded_i=mo.encode_index_buffer(indices,index_count=len(indices),vertex_count=len(attributes))
    dv=mo.decode_vertex_buffer(len(attributes),32,encoded_v)
    di=mo.decode_index_buffer(len(indices),4,encoded_i).reshape(-1,3)
    if dv.tobytes()!=attributes.tobytes() or not np.array_equal(cyclic_faces(di),cyclic_faces(indices.reshape(-1,3))):
        raise ValueError('glTF native decode did not preserve complete attributes')
    pad=lambda b:b+b'\0'*((-len(b))%4)
    vi=0;ii=len(pad(encoded_v));image_offset=ii+len(pad(encoded_i))
    binary=pad(encoded_v)+pad(encoded_i)+png
    stride=32;raw_v=len(attributes)*stride;raw_i=len(indices)*4
    ext='EXT_meshopt_compression'
    doc=dict(asset=dict(version='2.0',generator='Ridgeline original-point pipeline',
        copyright='LINZ / Environment Canterbury and survey/imagery contributors; CC BY 4.0'),
        scene=0,scenes=[dict(nodes=[0])],nodes=[dict(mesh=0)],
        extensionsUsed=[ext],extensionsRequired=[ext],
        buffers=[dict(byteLength=len(binary)),dict(byteLength=raw_v+raw_i,extensions={ext:dict(fallback=True)})],
        bufferViews=[
            dict(buffer=1,byteOffset=0,byteLength=raw_v,byteStride=stride,target=34962,
                extensions={ext:dict(buffer=0,byteOffset=vi,byteLength=len(encoded_v),byteStride=stride,count=len(attributes),mode='ATTRIBUTES',filter='NONE')}),
            dict(buffer=1,byteOffset=raw_v,byteLength=raw_i,target=34963,
                extensions={ext:dict(buffer=0,byteOffset=ii,byteLength=len(encoded_i),byteStride=4,count=len(indices),mode='TRIANGLES')}),
            dict(buffer=0,byteOffset=image_offset,byteLength=len(png))],
        accessors=[dict(bufferView=0,byteOffset=0,componentType=5126,count=len(attributes),type='VEC3',
                        min=attributes[:,:3].min(0).astype(float).tolist(),max=attributes[:,:3].max(0).astype(float).tolist()),
            dict(bufferView=0,byteOffset=12,componentType=5126,count=len(attributes),type='VEC3'),
            dict(bufferView=0,byteOffset=24,componentType=5126,count=len(attributes),type='VEC2'),
            dict(bufferView=1,componentType=5125,count=len(indices),type='SCALAR',min=[int(indices.min())],max=[int(indices.max())])],
        meshes=[dict(primitives=[dict(attributes=dict(POSITION=0,NORMAL=1,TEXCOORD_0=2),indices=3,material=0,mode=4)])],
        materials=[dict(doubleSided=True,pbrMetallicRoughness=dict(baseColorTexture=dict(index=0),metallicFactor=0.,roughnessFactor=.94))],
        images=[dict(bufferView=2,mimeType='image/png')],textures=[dict(sampler=0,source=0)],
        samplers=[dict(magFilter=9729,minFilter=9987,wrapS=33071,wrapT=33071)],extras=extras)
    js=json.dumps(doc,separators=(',',':'),allow_nan=False).encode();js+=b' '*((-len(js))%4);binary=pad(binary)
    glb=struct.pack('<III',0x46546c67,2,12+8+len(js)+8+len(binary))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(binary),0x004e4942)+binary
    metrics=dict(triangles=len(f),gpu_vertices=len(attributes),unwelded_vertices=len(f)*3,
        gpu_attribute_bytes=raw_v,gpu_index_bytes=raw_i,meshopt_bytes=len(encoded_v)+len(encoded_i),glb_bytes=len(glb),
        degenerate_shading_faces=int(degenerate.sum()),geometry_unchanged_by_packing=True,
        attributes_sha256=hashlib.sha256(attributes.tobytes()).hexdigest(),
        cyclic_index_sha256=hashlib.sha256(cyclic_faces(indices.reshape(-1,3)).tobytes()).hexdigest(),
        image_sha256=hashlib.sha256(png).hexdigest())
    return glb,metrics


def process(state,source_path,reference_path,label,budget=0.,kind='source',position_step=None):
    source_path=Path(source_path);reference_path=Path(reference_path)
    frame=coordinates.checkpoint(state);root=Path(__file__).resolve().parents[2]
    verifier=root/'tools/lidar-glb-check.mjs'
    metadata=root/'public/assets/mt-cook/model/region.json'
    with np.load(source_path) as d:v,f=d['vertices'],d['faces']
    with np.load(reference_path) as d:rv,rf=d['vertices'],d['faces']
    geographic=frame.to_geographic(v.astype(float)+np.asarray(state.config['origin']))
    if kind=='native':
        from . import native_imagery
        uv,png,photo=native_imagery.bake(state,v)
    else:
        uv,png,photo=imagery.bake(geographic,metadata,kind)
    signature=key(dict(stage='georeferenced-glb-v1',source=digest(source_path),reference=digest(reference_path),
        budget=budget,imagery=photo,frame=frame.metadata,implementation=digest(__file__),
        coordinate_implementation=digest(coordinates.__file__),imagery_implementation=digest(native_imagery.__file__ if kind=='native' else imagery.__file__),
        certificate_implementation=digest(error_bounds.__file__),meshoptimizer=importlib.metadata.version('meshoptimizer')))
    signature=key(dict(geometry_signature=signature,verifier=digest(verifier),
        quantization=digest(quantization.__file__),position_step=position_step,
        validator_lock=digest(Path(__file__).with_name('package-lock.json')),
        decoder=digest(root/'public/assets/vendor/meshoptimizer/meshopt_decoder.mjs')))
    task='glb-'+label+'-'+kind;directory=state.root/'data/gltf'/task
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return state.task(task)
        state.start(task,signature);directory.mkdir(parents=True,exist_ok=True);started=time.perf_counter()
        try:
            enu=frame.to_enu(v);positions=frame.to_gltf(enu).astype('<f4')
            encoded_enu=frame.from_gltf(positions)
            conversion_error=float(np.linalg.norm(encoded_enu.astype(float)-enu,axis=1).max())
            if source_path.resolve()==reference_path.resolve():
                reference_conversion_error=0.
                proof=dict(certified=True,upper_bound_m=0.,method='Same reconstructed source, after identical transformation')
            else:
                if not np.isfinite(budget) or budget<=0:raise ValueError('Positive LOD budget required for a different reference')
                reference_full=frame.to_enu(rv)
                reference_enu=reference_full.astype('<f4')
                reference_conversion_error=float(np.linalg.norm(reference_enu.astype(float)-reference_full,axis=1).max())
                limit=budget*1.002+.001
                a=error_bounds.collect(reference_enu,rf,encoded_enu,f,limit)
                b=error_bounds.collect(encoded_enu,f,reference_enu,rf,limit)
                if not a['certified'] or not b['certified']:raise ValueError('Post-transformation continuous LOD bound failed')
                proof=dict(certified=True,upper_bound_m=max(a['upper_bound_m'],b['upper_bound_m']),budget_m=limit,
                    forward={k:x for k,x in a.items() if k!='unresolved_reference_faces'},
                    reverse={k:x for k,x in b.items() if k!='unresolved_reference_faces'})
            extras=dict(ridgeline=dict(source_mesh_sha256=digest(source_path),reference_mesh_sha256=digest(reference_path),
                coordinate_frame=frame.metadata,imagery=photo,lod=proof,conversion_rounding_max_m=conversion_error,
                pending=['owned coplanar facets and regional hierarchy','browser/device inspection']))
            glb,packing=build(positions,f,uv,png,extras)
            fine=source_path.resolve()==reference_path.resolve()
            glb,packing=quantization.compact(glb,packing,position_step,conversion_error if fine else 0.)
            proof=dict(packing.pop('packed_world_bound'))
            full_precision_proof=dict(proof,upper_bound_m=proof['upper_bound_m']+reference_conversion_error,
                reference_float32_rounding_added_m=reference_conversion_error,
                reference='Full-precision common-ENU transform of finest reconstructed local-coordinate mesh; not survey accuracy')
            if 'budget_m' in proof:
                full_precision_proof['comparison_float32_budget_m']=proof['budget_m']
                full_precision_proof['budget_m']=proof['budget_m']+reference_conversion_error
            asset=directory/'terrain.glb';atomic_bytes(asset,glb)
            options=directory/'verify-options.json'
            atomic_json(options,dict(glb=str(asset),**{k:packing[k] for k in ['attributes_sha256','cyclic_index_sha256','image_sha256']}))
            checked=subprocess.run(['node',str(verifier),str(options)],capture_output=True,text=True,check=True,timeout=180)
            browser_validation=json.loads(checked.stdout)
            report=dict(**packing,coordinate_rounding_max_m=conversion_error,continuous_world_bound=proof,
                continuous_full_precision_world_bound=full_precision_proof,
                reference_conversion_rounding_max_m=reference_conversion_error,
                finest_reference=fine,
                enu_min=encoded_enu.min(0).astype(float).tolist(),enu_max=encoded_enu.max(0).astype(float).tolist(),
                coordinate_frame=frame.metadata,imagery=photo,validation=browser_validation,
                seconds=time.perf_counter()-started,publishable=False)
            if state.remote:report['asset']=state.remote.file(asset,'model/gltf-binary')
            else:report['asset']=dict(sha256=digest(asset),bytes=asset.stat().st_size,verified=False)
            validation=directory/'validation.json';atomic_json(validation,report)
            state.stage(task,signature,[asset,validation],report)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('glTF awaits persistent checkpoint')
            print(json.dumps(dict(task=task,triangles=packing['triangles'],glb_bytes=packing['glb_bytes'],
                world_bound_m=proof['upper_bound_m'],asset=report['asset'])),flush=True)
            return state.task(task)
        except Exception as error:
            if not state.valid(task,signature):state.fail(task,error)
            raise
