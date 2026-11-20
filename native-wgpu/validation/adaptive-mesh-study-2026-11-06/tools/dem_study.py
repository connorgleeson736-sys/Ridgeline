import io,math,json,os,sys,urllib.request
import numpy as np
from PIL import Image
KEY=sys.argv[1];CACHE=sys.argv[2];OUT=sys.argv[3] if len(sys.argv)>3 else None
def tile(z,lon,lat):
    n=2**z;return z,int((lon+180)/360*n),int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*n)
def dem(z,x,y):
    path=os.path.join(CACHE,f"{z}_{x}_{y}.png")
    if not os.path.exists(path):
        u=f"https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/{z}/{x}/{y}.png?pipeline=terrain-rgb&api={KEY}"
        try:data=urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':'Ridgeline-dem-study'}),timeout=30).read()
        except urllib.error.HTTPError as e:return None
        open(path,'wb').write(data)
    a=np.asarray(Image.open(path).convert('RGBA'),dtype=np.float64)
    h=-10000+(a[...,0]*65536+a[...,1]*256+a[...,2])*0.1
    h[a[...,3]==0]=np.nan
    return h,os.path.getsize(path)
def upsample_quadrant(parent,qx,qy):
    # Parent pixel centres -> child pixel centres, bilinear (pixel-centre convention).
    i=np.arange(256)
    src=(qx*128)+(i+0.5)/2-0.5; srcy=(qy*128)+(i+0.5)/2-0.5
    x0=np.clip(np.floor(src).astype(int),0,254);fx=np.clip(src-x0,0,1)
    y0=np.clip(np.floor(srcy).astype(int),0,254);fy=np.clip(srcy-y0,0,1)
    a=parent[np.ix_(y0,x0)];b=parent[np.ix_(y0,x0+1)];c=parent[np.ix_(y0+1,x0)];d=parent[np.ix_(y0+1,x0+1)]
    return (a*(1-fx)[None,:]*(1-fy)[:,None]+b*fx[None,:]*(1-fy)[:,None]+c*(1-fx)[None,:]*fy[:,None]+d*fx[None,:]*fy[:,None])
def _study():
    places={"Aoraki/Mt Cook (alpine)":(170.1418,-43.5950),"Rolling Pin (foothills)":(171.95,-43.03),
            "Fiordland, Milford (alpine)":(167.93,-44.67),"Canterbury Plains, Ashburton (flat)":(171.75,-43.90),
            "Wellington hills":(174.78,-41.29),"Tongariro (volcanic)":(175.65,-39.20)}
    rows=[]
    for name,(lon,lat) in places.items():
        for zc in range(12,19):
            z,x,y=tile(zc,lon,lat);child=dem(z,x,y);par=dem(z-1,x//2,y//2)
            if child is None or par is None:continue
            (c,cb),(p,pb)=child,par
            up=upsample_quadrant(p,x%2,y%2)
            d=np.abs(c-up)[8:-8,8:-8];d=d[np.isfinite(d)]
            if d.size==0:continue
            ground=40075016.686*math.cos(math.radians(lat))/(256*2**z)   # metres per DEM pixel at zc
            # The DEM at zoom zc feeds geometry tiles at zc+1 (terrainSourceZoom = z-1).
            # Renderer budget: 0.6 screen px; at the view where a geometry tile is its
            # own detail level, one screen px is about one geometry-grid cell = half a
            # DEM pixel of its source. Tolerance in metres:
            tol=0.6*ground/2
            rows.append({"place":name,"demZoom":zc,"bytes":cb,"groundPixelM":round(ground,3),"toleranceM":round(tol,3),
                "maxDiffM":round(float(d.max()),3),"p99DiffM":round(float(np.percentile(d,99)),3),"medianDiffM":round(float(np.median(d)),3),
                "fractionAboveTolerance":round(float((d>tol).mean()),5),"adds":bool(d.max()>tol)})
    json.dump(rows,open(OUT,'w'),indent=1)
    for r in rows:print(f"{r['place'][:34]:34s} z{r['demZoom']:2d} {r['bytes']:7d}B pix {r['groundPixelM']:7.2f}m tol {r['toleranceM']:6.2f}m  max {r['maxDiffM']:8.2f} p99 {r['p99DiffM']:7.2f} >tol {100*r['fractionAboveTolerance']:6.2f}%")
if __name__=="__main__":_study()
