"""Persist the like-for-like four-child versus merged-parent geometry result."""
import json
from pathlib import Path
from .state import atomic_json, key


def compare(state):
    union_name='regional-union-7e8d397b8039-d1p35';union=state.task(union_name)
    if not union or union['state']!='durable' or not state.valid(union_name,union['signature']):
        raise ValueError('Verified exact regional union required')
    u=union['metrics']
    if (not u['every_input_triangle_has_exact_parent_witness'] or not u['every_parent_triangle_has_input_witness']
        or u['vertices_moved'] or u['duplicate_divider_triangles_removed']):
        raise ValueError('This comparison requires the exact unchanged four-patch face union')
    for source in u['inputs']:
        task=state.task(source['task'])
        if not task or task['signature']!=source['signature'] or not state.valid(source['task'],source['signature']):
            raise ValueError('Regional source generation changed')
    names=[f'certified-lod-{tile:04d}-d1p35-2' for tile in [189,190,209,210]]
    parent_name='certified-lod-regional-7e8d397b8039-d1p35-2'
    records=[]
    for name in names+[parent_name]:
        task=state.task(name)
        if not task or task['state']!='durable' or not state.valid(name,task['signature']):
            raise ValueError('Verified durable benchmark input required: '+name)
        m=task['metrics']
        if not m['candidate_accepted'] or m['budget_m']!=2 or not m['continuous_bound']['certified'] or m['continuous_bound']['upper_bound_m']>2:
            raise ValueError('Benchmark input failed the common continuous2m certificate')
        records.append(dict(task=name,signature=task['signature'],triangles=m['triangles'],
            reference_triangles=m['reference_triangles'],bound_m=m['continuous_bound']['upper_bound_m'],
            divider_facets=m['divider_facets']))
    children=records[:-1];parent=records[-1];child_triangles=sum(r['triangles'] for r in children)
    if sum(r['reference_triangles'] for r in children)!=parent['reference_triangles']:
        raise ValueError('Parent and children do not cover the same finest face count')
    report=dict(schema=1,run=state.run,region_m=[200,200],region_fraction_of_requested_2km=.01,
        exact_union_task=union_name,exact_union_signature=union['signature'],source_patches=u['inputs'],
        geometric_budget_m=2,children=children,parent=parent,child_triangles=child_triangles,
        parent_triangles=parent['triangles'],triangles_saved=child_triangles-parent['triangles'],
        reduction_relative_to_child_lods=1-parent['triangles']/child_triangles,
        reduction_relative_to_finest=1-parent['triangles']/parent['reference_triangles'],
        geometry_only=True,publishable=False,
        interpretation='A larger parent releases internal100m divider constraints while preserving its exterior facets. Both choices pass symmetric continuous distance checks against the same finest union.',
        exclusions=['No browser FPS, GPU, latency, compressed size or end-to-end processing speed comparison.',
                    'Resumed task total_seconds excludes earlier saved attempts; do not compare those as complete runtimes.',
                    'Certificates describe reconstructed local-coordinate triangle unions, not survey accuracy or completeness.',
                    'Mixed-LOD regional rendering, seams and export/quantization accounting remain separate gates.'])
    name='regional-parent-comparison-2m';signature=key(report)
    path=state.root/'data/regional/parent-comparison-2m.json'
    with state.lock(name):
        if not state.valid(name,signature):
            state.start(name,signature);atomic_json(path,report)
            state.stage(name,signature,[path],dict(child_triangles=child_triangles,parent_triangles=parent['triangles'],
                reduction_relative_to_child_lods=report['reduction_relative_to_child_lods']))
        elif state.remote:state.remote.sync_task(state,name)
        if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Benchmark awaits persistent checkpoint')
    return report


if __name__=='__main__':
    from .remote import Remote
    from .state import State
    print('Ready for private regional benchmark JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    print(json.dumps(compare(state)),flush=True)
