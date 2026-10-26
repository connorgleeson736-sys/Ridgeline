"""Lossless geometry streams plus native AND existing browser-codec readback.

These are reusable GLB buffer building blocks, not a georeferenced tileset.
No quantization or surface alteration is performed in this stage.
"""
from pathlib import Path
import hashlib
import importlib.metadata
import json
import subprocess
import time
import numpy as np
import meshoptimizer as mo
from .state import atomic_bytes,atomic_json,key,digest


def cyclic_faces(f):
    first=np.argmin(f,axis=1)
    return np.ascontiguousarray(np.take_along_axis(f,(first[:,None]+np.arange(3))%3,axis=1),dtype='<u4')


def process(state,source_path,label):
    source_path=Path(source_path);root=Path(__file__).resolve().parents[2]
    verifier=root/'tools/lidar-decode-check.mjs';version=importlib.metadata.version('meshoptimizer')
    signature=key(dict(stage='lossless-geometry-codec-v1',input=digest(source_path),version=version,
        implementation=digest(__file__),verifier=digest(verifier),
        decoder=digest(root/'public/assets/vendor/meshoptimizer/meshopt_decoder.mjs')))
    task='codec-'+label;directory=state.root/'data/codec'/label
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return state.task(task)
        state.start(task,signature);directory.mkdir(parents=True,exist_ok=True)
        try:
            with np.load(source_path) as data:
                v=np.ascontiguousarray(data['vertices'],dtype='<f4');f=np.ascontiguousarray(data['faces'],dtype='<u4')
            if not len(v) or not len(f) or not np.isfinite(v).all() or f.max()>=len(v):raise ValueError('Invalid mesh codec input')
            started=time.perf_counter()
            positions=mo.encode_vertex_buffer(v,vertex_count=len(v),vertex_size=12)
            indices=mo.encode_index_buffer(f.ravel(),index_count=f.size,vertex_count=len(v))
            encode_ms=(time.perf_counter()-started)*1000
            native_started=time.perf_counter()
            dv=mo.decode_vertex_buffer(len(v),12,positions)
            df=mo.decode_index_buffer(f.size,4,indices).reshape(-1,3)
            native_ms=(time.perf_counter()-native_started)*1000
            if dv.tobytes()!=v.tobytes() or not np.array_equal(cyclic_faces(f),cyclic_faces(df)):
                raise ValueError('Native codec roundtrip changed geometry')
            offset=(len(positions)+3)//4*4
            payload=positions+b'\0'*(offset-len(positions))+indices
            buffer=directory/'geometry.meshopt.bin';atomic_bytes(buffer,payload)
            description=dict(schema=1,codec='meshoptimizer',encoder_version=version,
                position_sha256=hashlib.sha256(v.tobytes()).hexdigest(),
                cyclic_index_sha256=hashlib.sha256(cyclic_faces(f).tobytes()).hexdigest(),
                coordinate_frame='Unchanged input frame; not yet a georeferenced glTF/3D Tiles asset',
                views=[dict(byteOffset=0,byteLength=len(positions),count=len(v),byteStride=12,mode='ATTRIBUTES'),
                       dict(byteOffset=offset,byteLength=len(indices),count=f.size,byteStride=4,mode='TRIANGLES')])
            descriptor=directory/'geometry.meshopt.json';atomic_json(descriptor,description)
            options=directory/'verify-options.json';atomic_json(options,dict(description=str(descriptor),buffer=str(buffer)))
            checked=subprocess.run(['node',str(verifier),str(options)],capture_output=True,text=True,check=True,timeout=120)
            browser=json.loads(checked.stdout)
            report=dict(vertices=len(v),triangles=len(f),raw_bytes=v.nbytes+f.nbytes,encoded_bytes=len(payload),
                transfer_reduction_fraction=1-len(payload)/(v.nbytes+f.nbytes),encode_ms=encode_ms,
                native_decode_ms=native_ms,browser_codec_node=browser,additional_geometric_error_m=0.,
                position_bits_identical=True,triangle_geometry_and_winding_identical=True,
                source_sha256=digest(source_path),publishable=False,
                limitation='Node/WASM codec timings are not browser/device GPU/FPS measurements. Unchanged source coordinate frame; no imagery, normals, hierarchy or ECEF transform yet.')
            validation=directory/'validation.json';atomic_json(validation,report)
            state.stage(task,signature,[buffer,descriptor,validation],report)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('Codec output awaits persistent checkpoint')
            options.unlink(missing_ok=True)
            print(json.dumps(dict(task=task,**report)),flush=True);return state.task(task)
        except Exception as error:
            if not state.valid(task,signature):state.fail(task,error)
            raise
