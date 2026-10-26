"""Full checksum/range/index validation plus independent pyramid samples."""
from pathlib import Path
from PIL import Image
from io import BytesIO
import hashlib,json,os,subprocess,numpy as np
ROOT=Path(__file__).resolve().parents[1]
WORK=Path(os.environ.get('RIDGE_SOUTH_WORK','/workspace/scratch/3dd2ae8bd0a2/south-island-overviews-v1'))
s=json.loads((WORK/'progress.json').read_text());assert s['stage']=='complete';source_bytes=0
for row in s['sources'].values():
 b=(WORK/row['path']).read_bytes();assert hashlib.sha256(b).hexdigest()==row['sha256'];source_bytes+=len(b)
 with Image.open(BytesIO(b))as im:assert im.size==(256,256);assert im.convert('RGBA').getchannel('A').getextrema()==(255,255)
unique={}
for row in s['outputs'].values():
 b=(WORK/row['path']).read_bytes();assert len(b)==row['bytes']and hashlib.sha256(b).hexdigest()==row['sha256'];asset=row['asset'];assert asset['bytes']==len(b)and asset['sha256']==row['sha256'];unique[row['sha256']]=len(b)
 o=(WORK/row['overlay']['path']).read_bytes();assert hashlib.sha256(o).hexdigest()==row['overlay']['sha256']
 with Image.open(BytesIO(b))as im:assert im.size==(256,256)
public=json.loads(subprocess.check_output(['node','-e',"console.log(JSON.stringify(require('./public/south-overviews.js')))"],cwd=ROOT));assert len(public['tiles'])==5313 and len(s['outputs'])==10626
for key,modes in public['tiles'].items():
 for mode in ['raw','natural']:
  row=modes if isinstance(modes,list)or 'url'in modes else modes[mode];a=row['range']if isinstance(row,dict)and 'url'in row else row;asset=s['outputs'][mode+'/'+key]['asset'];assert public['packs'][a[0]]==asset['file']and a[1]==asset['offset']and a[2]==asset['bytes']
  if isinstance(row,dict)and 'url'in row:assert hashlib.sha256((ROOT/'public'/row['url'].lstrip('/')).read_bytes()).hexdigest()==asset['sha256']
errors=[]
for z in range(11,-1,-1):
 rows=[(k,r)for k,r in s['outputs'].items()if k.startswith('raw/'+str(z)+'/')]
 for k,row in rows[::max(1,len(rows)//8)]:
  _,_,xs,ys=k.split('/');x,y=int(xs),int(ys);mosaic=np.zeros((512,512,4),dtype=np.uint32)
  for dy in range(2):
   for dx in range(2):
    child=s['outputs'].get(f'raw/{z+1}/{x*2+dx}/{y*2+dy}')
    if child:mosaic[dy*256:(dy+1)*256,dx*256:(dx+1)*256]=np.asarray(Image.open(WORK/child['overlay']['path']).convert('RGBA'))
  a=mosaic[:,:,3];w=mosaic[:,:,:3]*a[:,:,None];aa=a[::2,::2]+a[1::2,::2]+a[::2,1::2]+a[1::2,1::2];ww=w[::2,::2]+w[1::2,::2]+w[::2,1::2]+w[1::2,1::2];rgb=np.divide(ww,aa[:,:,None],out=np.zeros_like(ww,dtype=float),where=aa[:,:,None]>0);expected=np.concatenate([np.rint(rgb),np.rint(aa[:,:,None]/4)],axis=2).astype(int);got=np.asarray(Image.open(WORK/row['overlay']['path']).convert('RGBA')).astype(int);delta=np.abs(expected-got);assert delta.max()<=1;errors.append(float(delta.mean()))
report={'run':s['run'],'allSourceHashesVerified':len(s['sources']),'allSourceSnapshotsOpaque':True,'outputCoordinates':5313,'outputVariantEntries':len(s['outputs']),'uniqueTileFiles':len(unique),'tileBytes':sum(unique.values()),'allOutputAndOverlayHashesVerified':True,'allPublicRangesMatchDurableManifest':True,'independentRecursiveSamples':len(errors),'worstSampleMeanByteError':max(errors),'sourceSnapshotBytes':source_bytes,'allLevels':list(range(13)),'caveat':'CPU filtering, checksums and range mapping; not GPU screenshots or device FPS.'}
(ROOT/'SOUTH_ISLAND_ASSET_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
