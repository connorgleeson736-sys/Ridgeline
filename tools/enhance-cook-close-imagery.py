"""Native 4x Real-ESRGAN detail for sixteen source-specific close-up summit patches.
Never reads/writes authoritative elevation. Weights/source hashes are verified.
Usage: python tools/enhance-cook-close-imagery.py source-6144.webp weights-directory
"""
import sys,pathlib,json,hashlib,time,os
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
import torch
sys.path.insert(0,str(pathlib.Path(__file__).parent/'vendor'))
from srvgg_arch import SRVGGNetCompact
ROOT=pathlib.Path(__file__).resolve().parents[1]/'public/assets/mt-cook/model'
source,weights=map(pathlib.Path,sys.argv[1:3]);existing=json.loads((ROOT/'ai/provenance.json').read_text());assert hashlib.sha256(source.read_bytes()).hexdigest()==existing['sourceImageSha256']
torch.set_num_threads(min(8,os.cpu_count() or 1));torch.set_num_interop_threads(1);params=[]
for name,sha in existing['weightsSha256'].items():
    file=weights/(name+'.pth');assert hashlib.sha256(file.read_bytes()).hexdigest()==sha
    params.append(torch.load(file,map_location='cpu',weights_only=True)['params'])
model=SRVGGNetCompact(3,3,64,32,4,'prelu');model.load_state_dict({k:params[0][k]*.25+params[1][k]*.75 for k in params[0]});model.eval().to(memory_format=torch.channels_last)
rgb=np.asarray(Image.open(source).convert('RGB'));assert rgb.shape==(6144,6144,3)
PAD,GUTTER,CORE=40,32,192;out=ROOT/'close';out.mkdir(exist_ok=True);entries=[];started=time.monotonic()
for tile in [137,138,153,154]:
    for q in range(4):
        x=(tile%16)*384+(q%2)*192;y=(tile//16)*384+(q//2)*192
        left,top,right,bottom=x-GUTTER-PAD,y-GUTTER-PAD,x+CORE+GUTTER+PAD,y+CORE+GUTTER+PAD
        original=Image.fromarray(rgb[top:bottom,left:right]);a=np.asarray(original);tensor=torch.from_numpy(np.ascontiguousarray(a.transpose(2,0,1))).unsqueeze(0).float().div_(255).to(memory_format=torch.channels_last)
        with torch.inference_mode(): result=model(tensor).squeeze(0).permute(1,2,0).contiguous().numpy()
        base=np.asarray(original.resize((original.width*4,original.height*4),Image.Resampling.LANCZOS),dtype=np.float32)/255
        residual=result-base;restored=np.clip(base+.65*(residual-gaussian_filter(residual,sigma=(4,4,0),mode='nearest')),0,1)
        patch=Image.fromarray(np.uint8(restored*255+.5)).crop((PAD*4,PAD*4,(PAD+CORE+2*GUTTER)*4,(PAD+CORE+2*GUTTER)*4));assert patch.size==(1024,1024)
        key=tile*4+q;file=out/f'{key}.webp';patch.save(file,quality=95,method=6)
        entries.append({'id':key,'tile':tile,'quadrant':q,'x':(tile%16)*128+(q%2)*64,'y':(tile//16)*128+(q//2)*64,'cells':64,'gutterGridCells':GUTTER/3,'pixels':1024,'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
    print(json.dumps({'tile':tile,'completed':len(entries),'seconds':round(time.monotonic()-started)}),flush=True)
report={'sourceImageSha256':existing['sourceImageSha256'],'weightsSha256':existing['weightsSha256'],'network':existing['network'],'scale':4,'method':'Direct4xneuraloutput; originalsourcecolours+65%highfrequencyresidual, 40sourcepxcontext,32sourcepxneighboringgutter','nativeSourceMetres':.3,'appearanceOnly':True,'tiles':entries,'totalBytes':sum(e['bytes'] for e in entries),'seconds':round(time.monotonic()-started)}
(out/'provenance.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'complete':True,'tiles':16,'bytes':report['totalBytes']}))
