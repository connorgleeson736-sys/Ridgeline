"""Validate actual neural output, georeferenced tiling and unchanged elevation assets."""
import pathlib,json,hashlib,gzip,numpy as np
from PIL import Image
p=pathlib.Path(__file__).resolve().parent.parent/'public/assets/mt-cook/model';a=p/'ai';meta=json.loads((p/'region.json').read_text());report=json.loads((a/'provenance.json').read_text())
assert hashlib.sha256(gzip.decompress((p/'cook-dem.f32.gz').read_bytes())).hexdigest()==meta['sourceSha256']=='00e4b14878f9f9c78afa04c5ead2d8970c51844567ce5f6be090dbecbac25f1d'
assert len(report['tiles'])==256 and report['servingScale']==2 and report['architecture']['nativeScale']==4
assert report['contextCheckMaxFloatDifference']<1e-4
provenance=json.loads((p/'imagery-provenance.json').read_text());assert report['sourceImageSha256']==next(o['sha256'] for o in provenance['outputs'] if o['size']==[6144,6144])
images={};differences=[]
for entry in report['tiles']:
 file=a/(str(entry['id'])+'.webp');assert hashlib.sha256(file.read_bytes()).hexdigest()==entry['sha256'];image=Image.open(file);assert image.size==(776,776);images[entry['id']]=np.asarray(image).astype(np.float32)
for y in range(16):
 for x in range(16):
  i=y*16+x
  if x<15:differences.append(float(np.mean(abs(images[i][:,-8:]-images[i+1][:,:8]))))
  if y<15:differences.append(float(np.mean(abs(images[i][-8:]-images[i+16][:8]))))
assert max(differences)<4,'Lossy gutter disagreement is unexpectedly large'
for name,size in [('aerial.webp',(2048,2048)),('preview.webp',(512,512))]:assert Image.open(a/name).size==size
print(json.dumps({'verifiedNeuralTiles':256,'sourceDEM':'unchanged, hash verified','meanGutterRGBDifference':float(np.mean(differences)),'maxGutterPairMean':max(differences),'totalTileMB':report['totalTileBytes']/1e6}))
