"""Verify cached surfaces against current source generations without remeshing.

The contributing-source AABB/halo rule exactly matches reconstruct.gather().
All selected partition artifacts and source identities are validated once before
a batch; completed surfaces can then check their original recipe signature.
"""
import json
import numpy as np
from pathlib import Path
from .state import key


def prepare(state):
    audit=state.task('source-audit')
    if not audit or audit['state']!='durable' or not state.valid('source-audit',audit['signature']):
        raise ValueError('Verified durable source audit required')
    catalog=json.loads((state.root/'data/source-audit.json').read_text());records=[]
    origin=np.asarray(state.config['origin'])
    for source in catalog['sources']:
        name=source['source']['name'];stem=Path(name).stem;task=state.task('partition-'+stem)
        if not task or task['state']!='durable' or not state.valid(task['name'],task['signature']):
            raise ValueError('Verified contributing partition required: '+name)
        inventory=json.loads((state.root/'data/shards'/stem/'inventory.json').read_text())
        if inventory['source_sha256']!=source['crop_sha256']:
            raise ValueError('Partition/source audit generation mismatch')
        bounds=np.asarray(source['bounds'])-origin
        if bounds.shape!=(2,3) or not np.isfinite(bounds).all():raise ValueError('Invalid source bounds')
        records.append(dict(name=name,signature=task['signature'],bounds=bounds))
    if not records:raise ValueError('No audited source generations')
    return records


def expected(state,tile,task,records):
    size=state.config['tile_size_m'];nx=int((state.config['bounds'][2]-state.config['bounds'][0])/size)
    iy,ix=divmod(tile,nx);bounds=[ix*size,iy*size,(ix+1)*size,(iy+1)*size]
    x0,y0,x1,y1=bounds;halo=state.config['halo_m'];inputs={}
    for record in records:
        lo,hi=record['bounds']
        if hi[0]<x0-halo or lo[0]>x1+halo or hi[1]<y0-halo or lo[1]>y1+halo:continue
        inputs[record['name']]=record['signature']
    recipe=task['metrics']['recipe']
    if (not inputs or task['metrics']['bounds']!=bounds or recipe['halo_m']!=halo
        or recipe['delta']!=1.35 or recipe['revision']!='r2' or recipe['maximum_edge_m']!=2.
        or not recipe['original_vertices']):
        raise ValueError('Cached surface recipe/domain changed')
    return key(dict(recipe=recipe,bounds=bounds,inputs=inputs,validation_schema=2))
