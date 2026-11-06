import math,json,os,sys,random,concurrent.futures as cf
import numpy as np
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from dem_study import dem,upsample_quadrant,tile
random.seed(20261106)
# Land sample boxes (lon0,lat0,lon1,lat1) covering varied NZ terrain.
boxes=[(168.0,-45.9,171.5,-43.0),(170.0,-43.5,173.5,-41.5),(171.5,-44.5,172.8,-43.3),(172.5,-42.0,174.3,-40.8),(174.5,-41.6,176.5,-38.5),(175.0,-39.6,177.8,-37.5),(172.8,-36.8,175.5,-34.8)]
def sample(z):
    b=random.choice(boxes);lon=random.uniform(b[0],b[2]);lat=random.uniform(b[1],b[3]);return tile(z,lon,lat),lat
jobs=[]
for z in (14,15,16,17):
    for _ in range(40):jobs.append((z,)+sample(z))
def evaluate(job):
    z,(zz,x,y),lat=job
    c=dem(z,x,y);p=dem(z-1,x//2,y//2);g=dem(z-2,x//4,y//4)
    if c is None or p is None or g is None:return None
    c,_=c;p,pbytes=p;g,_=g
    cu=upsample_quadrant(p,x%2,y%2)                    # child predicted from parent
    child_add=np.abs(c-cu)[8:-8,8:-8]
    # Detail the parent added over the grandparent, over the parent quadrant that covers this child.
    pu=upsample_quadrant(g,(x//2)%2,(y//2)%2)
    parent_add=np.abs(p-pu)
    q=parent_add[(y%2)*128:(y%2)*128+128,(x%2)*128:(x%2)*128+128][4:-4,4:-4]
    if np.isnan(child_add).all() or np.isnan(q).all():return None
    ground=40075016.686*math.cos(math.radians(lat))/(256*2**z)
    return {"z":z,"tile":[z,x,y],"tolChild":0.6*ground/2,"tolParent":0.6*2*ground/2,
            "childMax":float(np.nanmax(child_add)),"childP99":float(np.nanpercentile(child_add,99)),
            "parentQuadMax":float(np.nanmax(q)),"parentQuadP99":float(np.nanpercentile(q,99)),"childBytes":os.path.getsize(os.path.join(sys.argv[2],f"{z}_{x}_{y}.png"))}
import dem_study
with cf.ThreadPoolExecutor(6) as ex:rows=[r for r in ex.map(evaluate,jobs) if r]
json.dump(rows,open(sys.argv[3],'w'),indent=0)
print("samples",len(rows),"by zoom",{z:sum(r['z']==z for r in rows) for z in (14,15,16,17)})
needs=lambda r:r["childMax"]>r["tolChild"]
print("child adds visible detail (max diff > tolerance):",sum(map(needs,rows)),"of",len(rows))
for k in (0.25,0.5,1.0,1.5,2.0):
    skip=[r for r in rows if r["parentQuadMax"]<=k*r["tolParent"]]
    wrong=[r for r in skip if needs(r)]
    worst=max([r["childMax"]/r["tolChild"] for r in wrong],default=0)
    saved=sum(r["childBytes"] for r in skip)/sum(r["childBytes"] for r in rows)
    print(f"skip if parent-added max <= {k:4.2f} x parent tolerance: skips {len(skip):3d}/{len(rows)} ({100*len(skip)/len(rows):4.1f}% of requests, {100*saved:4.1f}% of bytes); wrongly skipped {len(wrong)} (worst {worst:.2f}x tolerance)")
