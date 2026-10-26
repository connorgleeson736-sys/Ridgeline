"""Credential-safe autonomous entry point. Send one JSON object through hidden stdin.

Settings: base, token, root, operation, optional tile. Never save that input.
"""
import json
from pathlib import Path
import sys
from .state import State
from .remote import Remote


def main():
    print('Ready for private checkpoint session JSON on hidden stdin.',flush=True)
    settings=json.loads(sys.stdin.readline())
    remote=Remote(settings['base'],settings.pop('token'))
    root=Path(settings['root'])
    if settings['operation']=='restore':
        print(json.dumps(remote.restore_run(settings['run'],root,settings.get('only'))),flush=True);return
    config=json.loads((root/'config.json').read_text()) if (root/'config.json').exists() else json.loads(Path(__file__).with_name('aoraki.json').read_text())
    state=State(root,config,remote=remote)
    operation=settings['operation']
    if operation=='health':
        response=remote.request('GET','health');print(json.dumps(response.json()),flush=True);return
    remote.sync(state,include_events=operation=='sync')
    if operation=='sync':return
    if operation=='plan':
        from .plan import create
        create(state)
    elif operation=='coordinates':
        from .coordinates import checkpoint
        frame=checkpoint(state);print(json.dumps(frame.metadata),flush=True)
    elif operation=='export-glb':
        import re
        from .gltf_export import process
        paths=[(root/settings[k]).resolve() for k in ['source','reference']]
        if any(not p.is_relative_to((root/'data').resolve()) for p in paths):raise ValueError('Export input outside data tree')
        if not re.fullmatch(r'[A-Za-z0-9_-]+',settings['label']):raise ValueError('Invalid export label')
        process(state,*paths,settings['label'],float(settings.get('budget',0)),settings.get('imagery','source'))
    elif operation=='clusters':
        from .clusters import process
        import re
        source=(root/settings['source']).resolve()
        if not source.is_relative_to((root/'data').resolve()):raise ValueError('Cluster source outside data tree')
        if not re.fullmatch(r'[A-Za-z0-9_-]+',settings['label']):raise ValueError('Invalid cluster label')
        result=process(state,source,settings['label'],int(settings.get('maximum_triangles',16384)))
        print(json.dumps(result['metrics']),flush=True)
    elif operation=='tile-assets':
        from .hierarchy import process
        result=process(state,int(settings.get('tile',189)),float(settings.get('delta',1.35)),
            int(settings.get('maximum_triangles',16384)),settings.get('imagery','source'),settings.get('limit_clusters'),settings.get('workers',2))
        print(json.dumps(result),flush=True)
    elif operation=='partition':
        from .partition import partition_source
        for audit in sorted((root/'data/raw').glob('*.audit.json')):partition_source(state,audit)
    elif operation=='feedback-lod':
        from .iterative_lod import run
        run(state,root/'data/experiments/0381/afs-2/surface.npz',float(settings.get('budget',.2)),'0381')
    elif operation=='finalize-refinement':
        from .finalize_refinement import recover
        recover(state)
    elif operation=='reconstruct':
        from .surface import process
        size=config['tile_size_m'];nx=int((config['bounds'][2]-config['bounds'][0])/size)
        ny=int((config['bounds'][3]-config['bounds'][1])/size)
        for tile in settings.get('tiles',[settings.get('tile')]):
            tile=int(tile)
            if not 0<=tile<nx*ny:raise ValueError('Patch outside configured region')
            iy,ix=divmod(tile,nx)
            process(state,f'{tile:04d}',[ix*size,iy*size,(ix+1)*size,(iy+1)*size],settings['native_dir'])
    elif operation=='seams':
        from .seams import test_four
        test_four(state,settings.get('local_delta'),settings.get('tiles'))
    elif operation=='local-trial':
        from .local_trial import process
        for tile in settings.get('tiles',[361,362,381,382]):
            process(state,int(tile),settings['native_dir'],float(settings.get('delta',1.35)),settings.get('revision'))
    elif operation=='certified-lod':
        from .certified_lod import run
        size=config['tile_size_m'];nx=int((config['bounds'][2]-config['bounds'][0])/size)
        label=format(float(settings.get('delta',1.35)),'.6g').replace('.','p')
        revision=settings.get('revision')
        if revision is not None:
            import re
            if not isinstance(revision,str) or not re.fullmatch('[a-z0-9]{1,12}',revision):raise ValueError('Safe algorithm revision required')
            label+='-'+revision
        for tile in settings.get('tiles',[381]):
            tile=int(tile)
            ny=int((config['bounds'][3]-config['bounds'][1])/size)
            if not 0<=tile<nx*ny:raise ValueError('LOD patch outside configured region')
            iy,ix=divmod(int(tile),nx)
            run(state,root/'data/local'/label/f'{tile:04d}'/'finest.npz',
                [ix*size,iy*size,(ix+1)*size,(iy+1)*size],float(settings.get('budget',.2)),f'{tile:04d}-d{label}')
    elif operation in ['orient','codec']:
        import re
        if operation=='orient':from .orient_geometry import process
        else:from .mesh_codec import process
        source=(root/settings['source']).resolve()
        if not source.is_relative_to((root/'data').resolve()):raise ValueError('Orientation source outside data tree')
        if not re.fullmatch(r'[A-Za-z0-9_-]+',settings['label']):raise ValueError('Invalid orientation label')
        if operation=='orient':process(state,source,settings['label'],settings['native_dir'])
        else:process(state,source,settings['label'])
    elif operation=='ingest':
        from .ingest import discover,ingest_one
        for source in discover(state):ingest_one(state,source)
    else:raise ValueError('Unknown processing operation')
    remote.sync(state)
    print(json.dumps(dict(operation=operation,counts=state.manifest()['counts'])),flush=True)


if __name__=='__main__':main()
