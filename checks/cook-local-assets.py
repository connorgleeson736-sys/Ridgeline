from pathlib import Path
import json,hashlib,gzip
from PIL import Image
import numpy as np
ROOT=Path(__file__).resolve().parents[1]/'public/assets/mt-cook/model'
original=json.loads((ROOT/'ai/provenance.json').read_text());close=json.loads((ROOT/'close/provenance.json').read_text());local=json.loads((ROOT/'local/provenance.json').read_text());meta=json.loads((ROOT/'region.json').read_text())
assert close['sourceImageSha256']==original['sourceImageSha256'];assert close['weightsSha256']==original['weightsSha256'];assert close['scale']==4
assert len(close['tiles'])==16 and len(local['tiles'])==256
assert not (ROOT/'surface-rock.webp').exists() and not (ROOT/'surface-ice.webp').exists(),'Generic materials must not ship'
assert hashlib.sha256(gzip.decompress((ROOT/meta['demPath'].split('/')[-1]).read_bytes())).hexdigest()==meta['sourceSha256'],'Authoritative source DEM changed'
for directory,report in [('local',local),('close',close)]:
 for t in report['tiles']:
  file=ROOT/directory/f"{t['id']}.webp";assert hashlib.sha256(file.read_bytes()).hexdigest()==t['sha256'];assert Image.open(file).size==(1024,1024)
errors=[]
for i in [0,46,137,138,153,154,255]:
 a=np.asarray(Image.open(ROOT/'local'/f'{i}.webp'),dtype=float)[128:-128,128:-128];b=np.asarray(Image.open(ROOT/'ai'/f'{i}.webp'),dtype=float)[4:-4,4:-4];errors.append(float(np.abs(a-b).mean()))
assert max(errors)<2.5,'Expanded gutters changed source cores excessively'
print(json.dumps({'expandedSourceSpecificTiles':256,'native4xNeuralPatches':16,'maximumMeanCoreRecompressionDifferenceRGB':round(max(errors),3),'DEM':'unchanged, source hash verified','genericCliffMaterials':'absent'}))
