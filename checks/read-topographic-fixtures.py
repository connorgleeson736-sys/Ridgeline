import pathlib,struct,json,gzip

def fields(b):
 i=0
 def varint():
  nonlocal i
  n=0;s=0
  while True:
   v=b[i];i+=1;n|=(v&127)<<s
   if v<128:return n
   s+=7
 while i<len(b):
  k=varint();t=k&7
  if t==0:v=varint()
  elif t==2:n=varint();v=b[i:i+n];i+=n
  elif t==1:v=struct.unpack('<d',b[i:i+8])[0];i+=8
  elif t==5:v=struct.unpack('<f',b[i:i+4])[0];i+=4
  else:raise ValueError(t)
  yield k>>3,v

def vint(b):
 out=[];v=s=0
 for x in b:
  v|=(x&127)<<s
  if x<128:out.append(v);v=s=0
  else:s+=7
 return out
all_features={}
for p in sorted(pathlib.Path('checks/fixtures/topography').glob('topo-*.pbf')):
 print(p.name)
 for n,b in fields(gzip.decompress(p.read_bytes()) if p.read_bytes()[:2]==bytes([31,139]) else p.read_bytes()):
  if n!=3:continue
  f=list(fields(b));name=next(v.decode() for n,v in f if n==1);keys=[v.decode() for n,v in f if n==3];values=[]
  for n,v in f:
   if n==4:
    t,x=next(fields(v));values.append(x.decode() if t==1 else x)
  feats=[]
  for n,v in f:
   if n!=2:continue
   tags=next((vint(x) for t,x in fields(v) if t==2),[]);feats.append({keys[tags[i]]:values[tags[i+1]] for i in range(0,len(tags),2)})
  all_features.setdefault(name,[]).extend(feats)
  if name in ['place_labels','contours','pois','streets','water_lines','water_polygons']:
   print(name,len(feats),json.dumps(feats[:8],ensure_ascii=False))

pathlib.Path('checks/fixtures/topography/properties.json').write_text(json.dumps(all_features,ensure_ascii=False,indent=2)+'\n')
