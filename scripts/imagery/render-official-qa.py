"""Offline source comparison for survey-based imagery selection."""
from pathlib import Path
import argparse,json,re
import numpy as np
from PIL import Image
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parents[2];p=argparse.ArgumentParser();p.add_argument('--cache',type=Path,required=True);a=p.parse_args();atlas=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/gap-imagery-atlas.js').read_text())[1]);candidates=[]
for k,e in atlas['entries'].items():
 x,y=e['x'],e['y']
 if not(a.cache/f'aerial-13-{x}-{y}.png').exists()or not(a.cache/f'esri-13-{x}-{y}.png').exists():continue
 m=np.array(Image.open(ROOT/'public'/e['maskURL'].lstrip('/')).getchannel('A'))>0;fraction=m.mean()
 if .15<fraction<.85:candidates.append((abs(fraction-.5),x,y,m,e))
selected=[]
for r in sorted(candidates,key=lambda q:q[0]):
 if all(abs(r[1]-q[1])+abs(r[2]-q[2])>12 for q in selected):selected.append(r)
 if len(selected)==3:break
fig,axes=plt.subplots(len(selected),3,figsize=(12,4*len(selected)),squeeze=False)
for row,(_,x,y,m,e)in enumerate(selected):
 linz=np.array(Image.open(a.cache/f'aerial-13-{x}-{y}.png').convert('RGB'));esri=np.array(Image.open(a.cache/f'esri-13-{x}-{y}.png').convert('RGB'));out=np.where(m[:,:,None],esri,linz)
 for col,(img,title)in enumerate([(linz,'Original LINZ composite'),(esri,'Esri World Imagery'),(out,'Official survey boundary + protective margin')]):
  ax=axes[row,col];ax.imshow(img,extent=(0,256,256,0));ax.set_xlim(0,256);ax.set_ylim(256,0);ax.set_title(title,fontsize=10);ax.set_axis_off()
  if col==2:
   for ring in e['outer']:
    q=np.array(ring);ax.plot(q[:,0],q[:,1],color='#172724',linewidth=3,linestyle='dotted');ax.plot(q[:,0],q[:,1],color='#fff6c8',linewidth=1.5,linestyle='dotted')
  if col==0:ax.text(3,249,f'z13 / {x} / {y}',color='white',fontsize=8,bbox={'facecolor':'black','alpha':.6,'pad':2})
fig.suptitle('Official LINZ survey geometry — offline source-pixel comparison, not a browser screenshot',fontsize=11);fig.tight_layout();out=ROOT/'qa/official-surveys/source-comparison.jpg';fig.savefig(out,dpi=140);print(out)
