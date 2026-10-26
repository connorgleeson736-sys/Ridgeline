"""Expand each location's actual neural imagery using adjacent georeferenced pixels.
No generative cliff material; original AI cores and their physical scale are retained.
"""
from pathlib import Path
import json,hashlib
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]/'public/assets/mt-cook/model'
CORE=768;GUTTER=128
atlas=Image.new('RGB',(CORE*16,CORE*16))
for i in range(256):
    image=Image.open(ROOT/'ai'/f'{i}.webp').convert('RGB')
    assert image.size==(776,776)
    atlas.paste(image.crop((4,4,772,772)),((i%16)*CORE,(i//16)*CORE))
out=ROOT/'local';out.mkdir(exist_ok=True);entries=[]
for i in range(256):
    x,y=i%16*CORE,i//16*CORE
    # Extend only the outer regional border by edge pixels; interior gutters use real neighbors.
    x0,y0=max(0,x-GUTTER),max(0,y-GUTTER);x1,y1=min(CORE*16,x+CORE+GUTTER),min(CORE*16,y+CORE+GUTTER)
    pixels=np.array(atlas.crop((x0,y0,x1,y1)))
    pixels=np.pad(pixels,((max(0,GUTTER-y),max(0,y+CORE+GUTTER-CORE*16)),(max(0,GUTTER-x),max(0,x+CORE+GUTTER-CORE*16)),(0,0)),mode='edge')
    path=out/f'{i}.webp';Image.fromarray(pixels).save(path,quality=94,method=6)
    entries.append({'id':i,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size})
report={'source':'../ai/provenance.json','method':'Same-place Real-ESRGAN imagery; expanded gutters assembled from adjacent georeferenced AI tile cores. No generic or generated cliff material.','corePixels':CORE,'gutterPixels':GUTTER,'outputPixels':CORE+2*GUTTER,'sourceGridCells':128,'gutterGridCells':GUTTER/6,'sourceDEM':'../elevation-provenance.json','appearanceOnly':'Slope-aware reconstruction can shift fine detail within at most6metres of its source location; does not recover unobserved cliff photographs.','tiles':entries,'totalBytes':sum(t['bytes'] for t in entries)}
(out/'provenance.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'tiles':256,'bytes':report['totalBytes']}))
