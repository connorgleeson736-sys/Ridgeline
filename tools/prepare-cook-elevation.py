import sys
from pathlib import Path
import numpy as np,json,rasterio,gzip,hashlib,math
from pyproj import Transformer
from rasterio.windows import Window
p=Path(sys.argv[1]);p.mkdir(parents=True,exist_ok=True);meta=json.load(open(sys.argv[2] if len(sys.argv)>2 else 'public/assets/mt-cook/region.json'));s=meta['gridSize'];step=meta['worldStep'];cx,cy=meta['worldCenter']

from urllib.request import urlopen
for name in ['BX15_10000_0305', 'BX15_10000_0405']:
 metadata=p/(name+'.json')
 if not metadata.exists():metadata.write_bytes(urlopen('https://nz-elevation.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2020-2023/dem_1m/2193/'+name+'.json').read())
 item=json.load(open(metadata));expected=item['assets']['visual']['file:checksum'][4:];file=p/(name+'.tiff')
 if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest()!=expected:
  temporary=p/(name+'.download');temporary.write_bytes(urlopen('https://nz-elevation.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2020-2023/dem_1m/2193/'+name+'.tiff').read());assert hashlib.sha256(temporary.read_bytes()).hexdigest()==expected;temporary.replace(file)
xw=cx+(np.arange(s)-(s-1)/2)*step;yw=cy+(np.arange(s)-(s-1)/2)*step
lon=xw*360-180;lat=np.degrees(np.arctan(np.sinh(np.pi*(1-2*yw))));lng,lt=np.meshgrid(lon,lat);x,y=Transformer.from_crs(4326,2193,always_xy=True).transform(lng,lt)
xmin=int(np.floor(x.min()))-2; ymax=int(np.ceil(y.max()))+2; xmax=int(np.ceil(x.max()))+2; ymin=int(np.floor(y.min()))-2
native=np.full((ymax-ymin,xmax-xmin),np.nan,dtype='<f4'); info=[]
for name in ['BX15_10000_0305','BX15_10000_0405']:
 f=p/(name+'.tiff'); item=json.load(open(p/(name+'.json')));actual=hashlib.sha256(f.read_bytes()).hexdigest();expected=item['assets']['visual']['file:checksum'][4:];assert actual==expected,(actual,expected)
 with rasterio.open(f) as d:
  left=max(xmin,int(d.bounds.left));right=min(xmax,int(d.bounds.right));bottom=max(ymin,int(d.bounds.bottom));top=min(ymax,int(d.bounds.top));win=Window(left-d.bounds.left,d.bounds.top-top,right-left,top-bottom);arr=d.read(1,window=win);good=np.isfinite(arr)&(arr!=d.nodata)
  patch=native[ymax-top:ymax-bottom,left-xmin:right-xmin];patch[good]=arr[good]
  info.append({'tile':name,'url':'https://nz-elevation.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2020-2023/dem_1m/2193/'+name+'.tiff','bytes':f.stat().st_size,'sha256':actual,'nativeResolutionMetres':list(d.res),'horizontalCRS':str(d.crs),'nativeBounds':list(d.bounds),'nodata':d.nodata,'cogImageStructure':d.tags(ns='IMAGE_STRUCTURE'),'nativeSamplesSupplied':int(good.sum()),'nativeSamplesRequested':int(good.size),'missingNativeSamples':int((~good).sum()),'metadata':d.tags()})
col=x-xmin-.5;row=ymax-y-.5;ic=np.floor(col).astype(int);ir=np.floor(row).astype(int);dx=col-ic;dy=row-ir
a=native[ir,ic];b=native[ir,ic+1];c=native[ir+1,ic];e=native[ir+1,ic+1];out=(a*(1-dx)*(1-dy)+b*dx*(1-dy)+c*(1-dx)*dy+e*dx*dy).astype('<f4')
assert np.isfinite(out).all(),int((~np.isfinite(out)).sum())
raw=out.tobytes();(p/'cook-authentic-dem.f32.gz').write_bytes(gzip.compress(raw,compresslevel=9,mtime=0));(p/'cook-authentic-dem.f32').write_bytes(raw)
old=np.frombuffer(gzip.decompress(Path('public/assets/mt-cook/cook-dem.f32.gz').read_bytes()),dtype='<f4').reshape((s,s));delta=out-old
report={'collection':'Canterbury LiDAR 1m DEM (2020-2025)','collectionUrl':'https://nz-elevation.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2020-2023/dem_1m/2193/collection.json','ldsUrl':'https://data.linz.govt.nz/layer/111133-canterbury-lidar-1m-dem-2020-2025/','nativeResolutionMetres':1,'verticalDatum':'NZVD2016 (verified LDS metadata)','resampling':'Bilinear interpolation directly from native1m COG samples to EXACT existing WebMercator pixel centers. No smoothing filter, invented heights, or terrain-RGB0.1m quantization.','gridSize':s,'pixelSpacingMetres':meta['pixelSpacingMetres'],'worldStep':step,'worldCenter':meta['worldCenter'],'bounds':meta['bounds'],'allGridSamplesCovered':True,'finiteSamples':int(np.isfinite(out).sum()),'missingSamples':0,'sourceFiles':info,'sha256':hashlib.sha256(raw).hexdigest(),'minimumHeight':float(out.min()),'maximumHeight':float(out.max()),'comparisonToPriorTerrainRGB':{'maxAbsoluteDifferenceMetres':float(abs(delta).max()),'rmsDifferenceMetres':float(np.sqrt(np.mean(delta.astype(float)**2))),'medianAbsoluteDifferenceMetres':float(np.median(abs(delta))),'meanSignedDifferenceMetres':float(delta.mean())}}
(p/'cook-authentic-provenance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2),flush=True)
