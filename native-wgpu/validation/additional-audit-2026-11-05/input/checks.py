#!/usr/bin/env python3
"""Deterministic native-renderer audit experiments; NOT an Android benchmark.

Run: python3 checks.py /path/to/Ridgeline [--out results.json]
The supplied root must contain native-wgpu/. Uses only Python's standard library.
Models are explicit ports of the named policies, with independent invariants.
"""
import argparse
import hashlib
import json
import math
import random
import struct
from pathlib import Path

HEAD = '3f4d9b8dedf8e3c53ed24f1c148712def00d1692'
K = 'native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/'
R = 'native-wgpu/renderer/src/'

def read_sources(root):
    names = [K+'TerrainView.kt', K+'UploadAdmission.kt', R+'adaptive.rs',
             R+'lib.rs', R+'android.rs', R+'cartography.rs', R+'routes.rs',
             'native-wgpu/core/src/adaptive.rs']
    sources = {p:(root/p).read_text() for p in names}
    # Guard the facts modeled below. Hashes in results identify the exact inputs.
    assert 'as_millis() >= 350' in sources[R+'adaptive.rs']
    assert 'step: 1,' in sources[R+'adaptive.rs']
    assert 'let mut target = level(0.6, 10.);' in sources[R+'adaptive.rs']
    assert 'vertices.extend_from_slice(part)' in sources[R+'cartography.rs']
    assert 'queue.write_buffer(&self.vertices, 0, bytes)' in sources[R+'routes.rs']
    assert 'array_stride:64' in sources[R+'routes.rs']
    assert 'self.poll_cartography();' in sources[R+'lib.rs']
    assert 'map_or(32, |delay| delay.min(32))' in sources[R+'lib.rs']
    assert 'maxOf(1L,it)' in sources[K+'TerrainView.kt']
    assert 'count==0&&(urgent||allowance>0)' in sources[K+'UploadAdmission.kt']
    assert 'frameNanos:Long=16_666_667L' in sources[K+'UploadAdmission.kt']
    assert 'val vsync=Choreographer.FrameCallback{drawFrame()}' in sources[K+'TerrainView.kt']
    assert 'desired_maximum_frame_latency: 2' in sources[R+'android.rs']
    return {p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in names}

def indices(rect, step):
    # Port of GridRect.adaptive_indices, surface only. Skirts are unchanged.
    x0,y0,x1,y1 = rect
    if step not in (2,4) or (x1-x0)%step or (y1-y0)%step:
        step=1
    ix=lambda x,y:y*129+x
    result=[]
    for y in range(y0,y1,step):
        for x in range(x0,x1,step):
            if step==1 or (x>x0 and y>y0 and x+step<x1 and y+step<y1):
                result.extend([(ix(x,y),ix(x,y+step),ix(x+step,y)),
                               (ix(x+step,y),ix(x,y+step),ix(x+step,y+step))])
                continue
            ring=[]
            def edge(ax,ay,bx,by,fine):
                n=step if fine else 1
                for i in range(n):
                    ring.append(ix(ax+(bx-ax)*i//n,ay+(by-ay)*i//n))
            edge(x,y,x,y+step,x==x0)
            edge(x,y+step,x+step,y+step,y+step==y1)
            edge(x+step,y+step,x+step,y,x+step==x1)
            edge(x+step,y,x,y,y==y0)
            center=ix(x+step//2,y+step//2)
            result.extend((center,ring[i],ring[(i+1)%len(ring)]) for i in range(len(ring)))
    return result

def triangles_formula(rect, step):
    x0,y0,x1,y1=rect
    w,h=x1-x0,y1-y0
    if step not in (2,4) or w%step or h%step:
        return 2*w*h
    nx,ny=w//step,h//step
    inside=max(nx-2,0)*max(ny-2,0)
    return 2*nx*ny+2*(nx*ny-inside)+(step-1)*(2*nx+2*ny)

def topology_checks():
    cases=0
    for rect in [(0,0,128,128),(32,64,64,96),(0,0,64,128),
                 (1,3,33,67),(0,0,7,11),(124,124,128,128)]:
        for step in (1,2,4):
            tris=indices(rect,step)
            x0,y0,x1,y1=rect
            area=0
            vertices=set()
            for tri in tris:
                p=[(i%129,i//129) for i in tri]
                cross=(p[1][0]-p[0][0])*(p[2][1]-p[0][1])-(p[1][1]-p[0][1])*(p[2][0]-p[0][0])
                assert cross<0
                area-=cross
                vertices.update(tri)
                assert all(x0<=x<=x1 and y0<=y<=y1 for x,y in p)
            assert area==2*(x1-x0)*(y1-y0)
            assert len(tris)==triangles_formula(rect,step)
            edge={y*129+x for x in range(x0,x1+1) for y in (y0,y1)}
            edge|={y*129+x for y in range(y0,y1+1) for x in (x0,x1)}
            assert edge<=vertices
            cases+=1
    return cases

def update_state(state, target, now, first=False):
    # Refinement/hysteresis level evaluation is unchanged. The experiment feeds
    # already-qualified targets, and changes only a new source's initial state.
    step,old_target,since=state
    if first or target<=step:
        return (target,target,now)
    if target!=old_target:
        return (step,target,now)
    if now-since>=350:
        return (target,target,now)
    return state

def cold_mesh():
    cases=topology_checks()
    base=(1,1,0)
    cold=(1,1,0)
    trace=[]
    for i,t in enumerate([0,16,100,349,350,360]):
        # t=360 is a close camera/route-exact case requiring full density.
        target=4 if t<360 else 1
        base=update_state(base,target,t)
        cold=update_state(cold,target,t,first=i==0)
        trace.append(dict(time_ms=t,qualified_target=target,baseline=base[0],candidate=cold[0]))
    assert [v['baseline'] for v in trace]==[1,1,1,1,4,1]
    assert [v['candidate'] for v in trace]==[4,4,4,4,4,1]
    # No profile, exact route, near-plane uncertainty remain target 1.
    assert update_state((1,1,0),1,0,first=True)[0]==1
    full=triangles_formula((0,0,128,128),1)
    coarse=triangles_formula((0,0,128,128),4)
    skirts=4*128*2
    return dict(scope='policy/topology port; no GPU timing or image-parity claim',
                topology_cases=cases,trace=trace,
                surface_triangles_before=full,surface_triangles_after=coarse,
                surface_reduction_percent=100*(1-coarse/full),
                with_four_skirts_before=full+skirts,with_four_skirts_after=coarse+skirts,
                all_skirts_reduction_percent=100*(1-(coarse+skirts)/(full+skirts)))

def record(path,patch,generation,index):
    # Real Vertex size/layout: 3+3+3+4+3 float32 = 64 bytes.
    return struct.pack('<16f',float(path),float(patch),float(generation),float(index),
                       .125,.25,0.,0.,-1.5,.1,.2,.3,.5,2.,3.,.75)

def flatten(chunks,paths,patches):
    return b''.join(chunks.get((p,t),b'') for p in paths for t in patches)

def delta_arena(old,new,arena,locations):
    changed=0
    for key,value in new.items():
        if old.get(key)==value:
            continue
        offset=len(arena)
        arena.extend(value)
        locations[key]=(offset,len(value))
        changed+=len(value)
    for key in set(locations)-set(new):
        del locations[key]
    return changed

def draw_stream(arena,locations,paths,patches):
    return b''.join(arena[locations[p,t][0]:sum(locations[p,t])]
                    for p in paths for t in patches if (p,t) in locations)

def overlay_delta():
    # 320,000 instances is below the source's 333,333-instance cap.
    paths=list(range(20));patches=list(range(32))
    old={(p,t):b''.join(record(p,t,0,i) for i in range(500)) for p in paths for t in patches}
    new=old.copy()
    for p in paths:
        new[p,7]=b''.join(record(p,7,1,i) for i in range(500))
    arena=bytearray();locations={}
    delta_arena({},old,arena,locations)
    changed=delta_arena(old,new,arena,locations)
    full=flatten(new,paths,patches)
    assert draw_stream(arena,locations,paths,patches)==full
    assert len(full)==20_480_000 and changed==640_000
    # Validate output order under variable lengths, removals, insertions and
    # changed coverage order. This is the constraint a GPU arena must preserve.
    rng=random.Random(4711)
    old={};arena=bytearray();locations={}
    for trial in range(100):
        paths=list(range(rng.randrange(1,9)))
        patches=rng.sample(range(12),rng.randrange(1,13))
        new={}
        for p in paths:
            for t in patches:
                if rng.randrange(4)==0: continue
                if (p,t) in old and rng.randrange(3): new[p,t]=old[p,t]
                else: new[p,t]=b''.join(record(p,t,trial,i) for i in range(rng.randrange(1,12)))
        delta_arena(old,new,arena,locations)
        assert draw_stream(arena,locations,paths,patches)==flatten(new,paths,patches)
        old=new
    return dict(scope='synthetic byte-stream equivalence; no GPU upload timings',
                equivalence_cases=101,instances=320000,changed_patches=1,total_patches=32,
                baseline_bytes=len(full),candidate_payload_bytes=changed,
                payload_reduction_percent=100*(1-changed/len(full)),
                maximum_source_payload_bytes=333333*64,
                constraints='Preserve path-major/cover-major order, alpha, phase, anchors and generation. Draw metadata/call overhead not counted; arena allocator/reclamation not implemented.')

def scheduling():
    period=1000/60
    def vsync(t):return math.ceil((t-1e-8)/period)*period
    # Idle scene: worker launched at callback t=0, frame returns at 6 ms;
    # result becomes ready at 7 ms. Existing code posts a 32 ms delayed wake
    # after return, then requests a Choreographer callback.
    baseline_poll=vsync(6+32)
    completion_poll=vsync(7)
    assert abs((baseline_poll-completion_poll)-2*period)<1e-6
    # Late pose example: camera A captured at 0; acquisition resumes at 12;
    # camera B is published at 8. A pose snapshot after acquisition sees B.
    inputs=[(0,'A'),(8,'B')]
    sample=lambda t:[pose for at,pose in inputs if at<=t][-1]
    assert sample(0)=='A' and sample(12)=='B'
    return dict(scope='explicit event schedules; NOT measured Android timings',
                worker_ready_ms=7,frame_return_ms=6,
                polling_callback_ms=baseline_poll,completion_wake_callback_ms=completion_poll,
                example_overlay_submission_improvement_ms=baseline_poll-completion_poll,
                late_pose_example=dict(acquire_resume_ms=12,new_input_ms=8,baseline_pose=sample(0),candidate_pose=sample(12)),
                one_refresh_ms={'60Hz':1000/60,'90Hz':1000/90,'120Hz':1000/120})

def admission():
    def allows(elapsed,count,nbytes,allowance,urgent,cost):
        return count<4 and nbytes<=650*1024 and ((count==0 and (urgent or allowance>0)) or (count>0 and elapsed+cost<=allowance))
    # A saturated render budget becomes 1 ns outside cameraFirst. The existing
    # first-upload predicate accepts a known 20 ms optional operation anyway.
    before=allows(0,0,262144,max(1,0),False,20_000_000)
    after=allows(0,0,262144,0,False,20_000_000)
    assert before and not after
    assert allows(0,0,262144,0,True,20_000_000)
    assert not allows(1,1,262144,1,False,20_000_000)
    return dict(scope='admission-predicate counterexample; 20 ms is a chosen input, not a measured tile cost',
                optional_upload_predicted_ns=20_000_000,real_allowance_ns=0,forced_allowance_ns=1,
                baseline_admits_optional=before,candidate_admits_optional=after,
                current_cap_bytes=650*1024,
                fix='Use a real deadline and actual display interval, check predicted cost including the first optional upload; reserve explicit urgent coverage exception and starvation policy.')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    parser.add_argument('--out',type=Path)
    args=parser.parse_args()
    result=dict(source_head=HEAD,source_sha256=read_sources(args.root),
                cold_mesh=cold_mesh(),overlay_delta=overlay_delta(),
                scheduling=scheduling(),upload_admission=admission())
    text=json.dumps(result,indent=2)+'\n'
    if args.out:args.out.write_text(text)
    print(text)

if __name__=='__main__':main()
