import io,math,sys,urllib.request
import numpy as np
from PIL import Image
K=sys.argv[1]
def get(kind,z,x,y):
    ext,q=("png","pipeline=terrain-rgb&") if kind=="elevation" else ("webp","")
    u=f"https://basemaps.linz.govt.nz/v1/tiles/{kind}/WebMercatorQuad/{z}/{x}/{y}.{ext}?{q}api={K}"
    return np.asarray(Image.open(io.BytesIO(urllib.request.urlopen(u,timeout=30).read())).convert("RGBA"),dtype=np.float64)
def heights(a):h=-10000+(a[...,0]*65536+a[...,1]*256+a[...,2])*0.1;h[a[...,3]==0]=np.nan;return h
def up(p,qx,qy):
    i=np.arange(256);s=qx*128+(i+.5)/2-.5;t=qy*128+(i+.5)/2-.5
    x0=np.clip(np.floor(s).astype(int),0,254);fx=np.clip(s-x0,0,1);y0=np.clip(np.floor(t).astype(int),0,254);fy=np.clip(t-y0,0,1)
    sh=p.shape[2:] if p.ndim==3 else ()
    fx=fx.reshape((1,-1)+(1,)*len(sh));fy=fy.reshape((-1,1)+(1,)*len(sh))
    return p[np.ix_(y0,x0)]*(1-fx)*(1-fy)+p[np.ix_(y0,x0+1)]*fx*(1-fy)+p[np.ix_(y0+1,x0)]*(1-fx)*fy+p[np.ix_(y0+1,x0+1)]*fx*fy
for name,lon,lat in [("Wellington city",174.78,-41.29),("Christchurch city",172.63,-43.53),("Rolling Pin (rural)",171.95,-43.03),("Aoraki/Mt Cook",170.1418,-43.595)]:
    for z in (17,18,19,20,21):
        n=2**z;x=int((lon+180)/360*n);y=int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*n)
        try:
            ce=heights(get("elevation",z,x,y));pe=heights(get("elevation",z-1,x//2,y//2))
            d=np.abs(ce-up(pe,x%2,y%2))[8:-8,8:-8];d=d[np.isfinite(d)]
            ci=get("aerial",z,x,y)[...,:3];pi=get("aerial",z-1,x//2,y//2)[...,:3]
            r=ci-up(pi,x%2,y%2);mse=float(np.mean(r[8:-8,8:-8]**2));psnr=99 if mse==0 else 10*math.log10(255**2/mse)
            ground=40075016.686*math.cos(math.radians(lat))/(256*2**z)
            print(f"{name:20s} z{z} ({ground*100:5.1f} cm/px)  elevation added: p99 {np.percentile(d,99):5.2f} m max {d.max():6.2f} m | imagery vs upsampled parent: PSNR {psnr:5.1f} dB")
        except Exception as e:print(name,z,"error",e)
