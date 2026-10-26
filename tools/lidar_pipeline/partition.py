"""Idempotent per-source spatial shards; full raw records remain in LAZ.

Coordinates are localized before float32 conversion; maximum conversion error
is measured and reported. A source point's original record index is retained.
"""
from __future__ import annotations
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import shutil
import uuid

import laspy
import numpy as np

from .state import State, atomic_json, digest, key


DTYPE = np.dtype([('position','<f4',(3,)),('record','<u4')])


def partition_source(state, audit_path):
    audit_path=Path(audit_path)
    source=audit_path.with_name(audit_path.name.replace('.audit.json','.laz'))
    raw_task=state.task('raw-'+source.stem)
    if not raw_task or raw_task['state'] != 'durable':
        return {'source':source.name,'state':'waiting_for_durable_raw'}
    if not state.valid(raw_task['name'],raw_task['signature']):
        raise ValueError('Raw checkpoint failed local integrity; restore it from the remote journal before partitioning')
    config=state.config
    audit=json.loads(audit_path.read_text())
    signature=key(dict(stage='partition-v1',source=audit['crop_sha256'],config=config))
    name='partition-'+source.stem
    with state.lock(name):
        if state.valid(name,signature):return dict(task=name,skipped=True)
        state.start(name,signature)
        temporary=state.root/'data'/('partition-'+uuid.uuid4().hex+'.tmp')
        temporary.mkdir()
        final=state.root/'data'/'shards'/source.stem
        try:
            origin=np.array(config['origin'],dtype=np.float64)
            xmin,ymin,xmax,ymax=config['bounds'];size=config['tile_size_m']
            nx=int(np.ceil((xmax-xmin)/size));ny=int(np.ceil((ymax-ymin)/size))
            counts=Counter();flag_counts=Counter();seen=0;max_conversion=0.
            with laspy.open(source) as reader:
                for chunk in reader.chunk_iterator(500000):
                    classes=np.asarray(chunk.classification)
                    withheld=np.asarray(chunk.withheld,dtype=bool)
                    # Cross-tab rather than assuming withheld == overlap.
                    for c in np.unique(classes):
                        for w in [False,True]:
                            flag_counts[f'class_{int(c)}_withheld_{int(w)}']+=int(((classes==c)&(withheld==w)).sum())
                    keep=~np.isin(classes,config['exclude_classes_from_surface'])
                    if config['exclude_withheld_from_surface']:keep &= ~withheld
                    records=np.arange(seen,seen+len(chunk),dtype=np.uint32)[keep]
                    seen+=len(chunk)
                    if not len(records):continue
                    xyz=np.column_stack((chunk.x,chunk.y,chunk.z))[keep]-origin
                    if not np.isfinite(xyz).all():raise ValueError('Nonfinite selected point')
                    positions=xyz.astype('<f4')
                    max_conversion=max(max_conversion,float(np.max(np.linalg.norm(positions.astype(float)-xyz,axis=1))))
                    ix=np.clip(np.floor(xyz[:,0]/size).astype(int),0,nx-1)
                    iy=np.clip(np.floor(xyz[:,1]/size).astype(int),0,ny-1)
                    ids=iy*nx+ix
                    order=np.argsort(ids,kind='stable')
                    labels,start,length=np.unique(ids[order],return_index=True,return_counts=True)
                    packed=np.empty(len(records),DTYPE);packed['position']=positions;packed['record']=records
                    for tile,first,n in zip(labels,start,length):
                        with (temporary/f'{int(tile):04d}.bin').open('ab') as f:
                            packed[order[first:first+n]].tofile(f)
                        counts[int(tile)]+=int(n)
            if seen!=audit['crop_points']:raise ValueError('Partition did not read every retained original point')
            for tile,count in counts.items():
                if (temporary/f'{tile:04d}.bin').stat().st_size!=count*DTYPE.itemsize:
                    raise ValueError('Incomplete spatial shard')
            atomic_json(temporary/'inventory.json',dict(source=source.name,source_sha256=audit['crop_sha256'],
                        counts=dict(counts),selected=sum(counts.values()),raw_points=seen,flags=dict(flag_counts),
                        origin=origin.tolist(),record_layout=DTYPE.descr,max_coordinate_conversion_m=max_conversion))
            final.parent.mkdir(parents=True,exist_ok=True)
            if final.exists():shutil.rmtree(final)
            os.replace(temporary,final)
            paths=sorted(final.glob('*'))
            receipt=state.stage(name,signature,paths,dict(points=sum(counts.values()),max_coordinate_conversion_m=max_conversion))
            if state.remote and state.task(name)['state']!='durable':
                raise RuntimeError('Partition paused until this source checkpoint is externally durable')
            print(json.dumps(dict(event='checkpoint_ready',**receipt)),flush=True)
            return receipt
        except Exception as error:
            if not state.valid(name,signature):state.fail(name,error)
            raise
        finally:
            if temporary.exists():shutil.rmtree(temporary)


def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);args=p.parse_args()
    root=Path(args.root);state=State(root,json.loads((root/'config.json').read_text()))
    for audit in sorted((root/'data/raw').glob('*.audit.json')):
        print(json.dumps(partition_source(state,audit)),flush=True)


if __name__=='__main__':main()
