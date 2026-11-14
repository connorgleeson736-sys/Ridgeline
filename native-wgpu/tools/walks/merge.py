"""Choose each walk's reading (older or re-read) by the compile checks, and keep
huts the older reading names that the chosen route passes (within 600 m)."""
import json,math,os,re,sys,shutil
S,W=sys.argv[1],'/home/user/native/native-wgpu/tools/walks'
exec(open(S+'/fb/render.py').read().split('meta=')[0].split('S=sys.argv[1]')[1])  # decode()
def seg(p,a,b):
  k=math.cos(math.radians(p[1]));ax,ay,bx,by,px,py=a[0]*k,a[1],b[0]*k,b[1],p[0]*k,p[1]
  l=(bx-ax)**2+(by-ay)**2;t=0 if l==0 else max(0,min(1,((px-ax)*(bx-ax)+(py-ay)*(by-ay))/l))
  return math.hypot(ax+(bx-ax)*t-px,ay+(by-ay)*t-py)*111320
def key(n):return re.sub(r'[^a-z0-9]','',re.sub(r'\b(hut|huts|bivvy|biv|bivouac|lodge|shelter)\b','',n.lower()))
ro={x['guid'].replace('-',''):x for x in json.load(open(S+'/fb/reportOld.json'))}
rn={x['guid'].replace('-',''):x for x in json.load(open(S+'/fb/report2.json'))}
wo={k.replace('-',''):v for k,v in json.load(open(S+'/fb/walksOld.json'))['walks'].items()}
wn={k.replace('-',''):v for k,v in json.load(open(S+'/fb/walks2.json'))['walks'].items()}
gold={f[:-5] for f in os.listdir(W+'/gold')}
out=S+'/final';shutil.rmtree(out,ignore_errors=True);os.makedirs(out)
log=[]
for g in sorted(set(ro)|set(rn)|{f[:-5] for f in os.listdir(W+'/readings2')}):
  newp=f'{W}/readings2/{g}.json';oldp=f'{W}/readings/{g}.json'
  if g in gold:shutil.copy(f'{W}/gold/{g}.json',f'{out}/{g}.json');log.append((g,'hand'));continue
  vn=rn.get(g,{}).get('status')=='verified';vo=ro.get(g,{}).get('status')=='verified'
  use='new' if (vn or not vo) and os.path.exists(newp) else 'old'
  r=json.load(open(newp if use=='new' else oldp))
  added=[]
  if use=='new' and g in wo and g in wn and os.path.exists(oldp):
    main=[w for w in wn[g].get('ways',[]) if w['main']]
    line=decode(main[0]['line']) if main else (decode(wn[g]['line']) if wn[g].get('line') else [])
    have={key(h['name']) for h in wn[g].get('huts',[])}|{key(h['name']) for h in r.get('huts',[])}
    old=json.load(open(oldp))
    for h in wo[g].get('huts',[]):
      if key(h['name']) in have or len(line)<2:continue
      d=min(seg((h['lon'],h['lat']),line[i],line[i+1]) for i in range(len(line)-1))
      if d>600:continue
      e=[x for x in old.get('huts',[]) if key(x['name'])==key(h['name']) or key(h['name']) in key(x['name']) or key(x['name']) in key(h['name'])]
      if e:r['huts'].append(e[0]);have.add(key(h['name']));added.append(h['name'])
  json.dump(r,open(f'{out}/{g}.json','w'),indent=1,ensure_ascii=False)
  log.append((g,use+(' +huts '+', '.join(added) if added else '')))
from collections import Counter
print(Counter(l[1].split(' ')[0] for l in log))
for g,l in log:
  if '+huts' in l or (l=='old'):print(' ',(rn.get(g) or ro.get(g))['name'][:45],'|',l)
