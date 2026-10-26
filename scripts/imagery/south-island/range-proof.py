"""Authenticated production range read; credentials stay on hidden stdin."""
from pathlib import Path
import importlib.util,sys,termios,json,hashlib
spec=importlib.util.spec_from_file_location('south',Path(__file__).with_name('pipeline.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
a=termios.tcgetattr(0);h=a[:];h[3]&=~termios.ECHO;termios.tcsetattr(0,termios.TCSANOW,h);print('Ready for private Site credentials (hidden).',flush=True);c=json.loads(sys.stdin.readline());termios.tcsetattr(0,termios.TCSANOW,a)
r=m.Remote(c['url'],c['token']);s=json.loads(m.STATE_PATH.read_text());output=s.get('outputs',{}).get('natural/12/3983/2600')
if output:
 entry=output['asset'];file=entry['file'];offset=entry['offset'];size=entry['bytes'];expected=entry['sha256'];proof='completed-overview'
else:
 batch=next(v for k,v in s['batches'].items()if k.startswith('sources-')and v.get('record'));_,_,record=r.request('GET',batch['record']['path']);record=json.loads(record);entry=record['entries'][0];file=record['file']['sha256'];offset=entry['offset'];size=entry['bytes'];expected=entry['sha256'];proof='source-preflight'
status,headers,data=r.request('GET','files/'+file+'.bin?tile=range-proof',headers={'Range':f'bytes={offset}-{offset+size-1}'})
assert status==206 and headers['Content-Range'].startswith(f'bytes {offset}-{offset+size-1}/')and len(data)==size and m.digest(data)==expected
qa=m.ROOT/'qa/south-island';qa.mkdir(parents=True,exist_ok=True);(qa/'range-proof.webp').write_bytes(data)
record={'proof':proof,'status':status,'headers':{k:headers.get(k)for k in ['Content-Type','Content-Range','Content-Length','Cache-Control']},'file':file,'offset':offset,'bytes':size,'sha256':expected};(qa/'range-proof.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record),flush=True)
