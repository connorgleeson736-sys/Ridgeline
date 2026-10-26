"""Offline source-pixel review, not a browser/WebGL screenshot."""
from pathlib import Path
import argparse,json,re,io,urllib.request
import numpy as np
from PIL import Image
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);w=p.parse_args().work
r=json.loads((ROOT/'IMAGERY_REFRESH_VALIDATION.json').read_text());atlas=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/gap-imagery-atlas.js').read_text())[1]);sizes={}
for q in r['verifiedNewPatches']:sizes[(q['x'],q['y'])]=sizes.get((q['x'],q['y']),0)+q['pixels']
chosen=[]
for k in sorted(sizes,key=sizes.get,reverse=True):
 if all(abs(k[0]-j[0])+abs(k[1]-j[1])>7 for j in chosen):chosen.append(k)
 if len(chosen)==3:break
fig,axes=plt.subplots(len(chosen),3,figsize=(12,4*len(chosen)),squeeze=False)
for row,(x,y)in enumerate(chosen):
 a=np.array(Image.open(w/f'source/aerial-13-{x}-{y}.png').convert('RGB'));b=np.array(Image.open(w/f'source/esri-13-{x}-{y}.png').convert('RGB'));m=np.array(Image.open(ROOT/'public'/atlas['entries'][f'{x}/{y}']['maskURL'].lstrip('/')))[:,:,3]>0;result=np.where(m[:,:,None],b,a)
 for col,(img,title)in enumerate([(a,'Original LINZ composite'),(b,'Esri World Imagery'),(result,'Replacement mask and dotted edge')]):
  ax=axes[row,col];ax.imshow(img);ax.set_title(title,fontsize=10);ax.set_axis_off()
  if col==2 and m.any() and not m.all():
   ax.contour(m,levels=[.5],colors=['#11231f'],linewidths=3,linestyles='dotted');ax.contour(m,levels=[.5],colors=['#fff6c8'],linewidths=1.5,linestyles='dotted')
  if col==0:ax.text(3,249,f'z13 / {x} / {y}',color='white',fontsize=8,bbox={'facecolor':'black','alpha':.6,'pad':2})
fig.suptitle('Source-pixel QA — offline composition, not a browser screenshot',fontsize=12);fig.tight_layout();out=ROOT/'qa/imagery-refresh';out.mkdir(exist_ok=True);fig.savefig(out/'source-comparison.jpg',dpi=140);print(str(out/'source-comparison.jpg'))
