"""Matched authentic pixels at equal ground footprints, not unlike-view histograms."""
from pathlib import Path
from PIL import Image,ImageDraw
import numpy as np,json,re,subprocess,hashlib
R=Path(__file__).resolve().parents[1];Q=R/'qa/zoom-colour-2026-10-19';S=Q/'source'
M=json.loads(re.search(r'const data=(.*);if\(typeof module',(R/'public/cook-reference-overviews.js').read_text())[1])
def output(z,x,y,mode='raw'):
 e=M['tiles'][f'{z}/{x}/{y}'];return Image.open(R/'public'/(e if isinstance(e,str)else e[mode]).lstrip('/')).convert('RGB')
def src(z,x,y):return Image.open(S/f'{z}-{x}-{y}.webp').convert('RGB')
reference=Image.new('RGB',(512,512))
for dy in range(2):
 for dx in range(2):reference.paste(src(15,31870+dx,20800+dy),(dx*256,dy*256))
paint=json.loads(subprocess.check_output(['node','-e',"console.log(JSON.stringify(require('./public/imagery.js').paint('linz')))"],cwd=R))
def grade(a):
 a=a.astype(float)/255;a+=(a.mean(2,keepdims=True)-a)*(-paint['raster-saturation']);return np.clip(((a-.5)/(1-paint['raster-contrast'])+.5)*(paint['raster-brightness-max']-paint['raster-brightness-min'])+paint['raster-brightness-min'],0,1)*255
rows=[];contact=Image.new('RGB',(1024,550),(22,26,29));d=ImageDraw.Draw(contact)
for i,z in enumerate([13,14]):
 x,y=15935//2**(14-z),10400//2**(14-z);w=256//2**(14-z);box=((15935%2**(14-z))*w,(10400%2**(14-z))*w,(15935%2**(14-z)+1)*w,(10400%2**(14-z)+1)*w)
 old=src(z,x,y).crop(box);new=output(z,x,y).crop(box);target=reference.resize((w,w),Image.Resampling.BOX);a,b,c=map(lambda im:np.array(im).astype(float),[old,new,target]);
 # Test interior summit face with no Esri footprint or outer photographic join.
 mask=np.zeros((w,w),bool);mask[int(w*.35):int(w*.9),int(w*.25):int(w*.9)]=True
 before=float(abs(a-c)[mask].mean());after=float(abs(b-c)[mask].mean());assert after<.8 and after<before*.05
 rows.append(dict(z=z,matchedRawMAEBefore255=before,matchedRawMAEAfter255=after,matchedDisplayedMAEBefore255=float(abs(grade(a)-grade(c))[mask].mean()),matchedDisplayedMAEAfter255=float(abs(grade(b)-grade(c))[mask].mean()),oldMeanRGB=a[mask].mean(0).tolist(),newMeanRGB=b[mask].mean(0).tolist(),referenceMeanRGB=c[mask].mean(0).tolist()))
 for j,(label,im)in enumerate([('Previous source',old),('Fine-source overview',new),('Fine source, same footprint',target)]):
  show=Image.fromarray(np.rint(grade(np.array(im))).astype('uint8')).resize((256,256));contact.paste(show,(j*341,i*275+20));d.text((j*341+4,i*275+4),f'z{z} {label}',fill='white')
# Source z19 is a tiny crop of the same survey, not an unverified global equivalence.
a=np.array(src(15,31870,20801).crop((9*16,12*16,10*16,13*16))).astype(float);b=np.array(src(19,509929,332828).resize((16,16),Image.Resampling.BOX)).astype(float)
assert max(abs(a.mean((0,1))-b.mean((0,1))))<1
# Out-of-footprint pixels in a distant overview remain byte-identical.
index=json.loads(re.search(r'const data=(.*);if\(typeof module',(R/'public/south-overviews.js').read_text())[1]);preserved=False
for key,e in M['tiles'].items():
 z,x,y=map(int,key.split('/'))
 if z!=8:continue
 old=index['tiles'][key];old=old if isinstance(old,dict)and'url'in old else old['raw'];base=np.array(Image.open(R/'public'/old['url'].lstrip('/')).convert('RGB'));changed=np.any(base!=np.array(output(z,x,y)),2);f=2**(14-z);x0=max(0,int(np.floor((15934/f-x)*256))-1);y0=max(0,int(np.floor((10399/f-y)*256))-1);x1=min(256,int(np.ceil((15937/f-x)*256))+1);y1=min(256,int(np.ceil((10402/f-y)*256))+1);changed[y0:y1,x0:x1]=False;assert not changed.any();preserved=True
assert preserved
foot=json.loads((R/'WEST_COAST_SURVEY_FOOTPRINTS.json').read_text());mask=Image.open(R/'public/assets/west-coast-mask.png').getchannel('A');protected=0
for x,y in [(15934,10400),(15935,10399),(15935,10400)]:
 f=2**(foot['z']-14)*256;alpha=np.array(mask.transform((256,256),Image.Transform.EXTENT,(x*f-foot['x']*256,y*f-foot['y']*256,(x+1)*f-foot['x']*256,(y+1)*f-foot['y']*256),resample=Image.Resampling.BILINEAR));selected=alpha==255
 if selected.any():
  esri=np.array(Image.open(S/f'esri-14-{x}-{y}.webp').convert('RGB'));shown=np.array(output(14,x,y));assert np.array_equal(shown[selected],esri[selected]);protected+=int(selected.sum())
assert protected>0
contact.save(Q/'comparison.png');report={'samples':rows,'highestZoomCheck':{'sourceZoom':19,'matchedZoom15MeanChannelDifference255':(b.mean((0,1))-a.mean((0,1))).tolist(),'matchedMAE255':float(abs(a-b).mean()),'note':'One authentic matched-footprint survey/radiometry check, not an assertion of equal detail or all-NZ equivalence.'},'distantOutsideFootprintPixelsPreserved':True,'unchangedEsriPixelsVerified':protected,'colourGradeUnchanged':paint,'sourceHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest()for p in S.glob('*.webp')},'caveat':'CPU image/grade reference, not a GPU screenshot or real-device latency measurement.'};(R/'COOK_REFERENCE_PIXEL_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items()if k not in ['sourceHashes','colourGradeUnchanged']}))
