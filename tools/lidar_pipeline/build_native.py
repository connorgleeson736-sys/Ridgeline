"""Pinned isolated CGAL headers/static libraries; no root/system changes."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import urllib.request
from .state import atomic_bytes

PACKAGES=[
 ('c/cgal','libcgal-dev_6.2.1-1_amd64.deb','77ad9b0b18cfc0c1687ac3c28806c54afe77bf3f86b2f194765e504b15d878c9'),
 ('b/boost1.83','libboost1.83-dev_1.83.0-4.2~bpo12+1_amd64.deb','83ac68a24f5a9959b5bfcabd558b08e699b56dd17e41e12c04237c6bef2eb399'),
 ('g/gmp','libgmp-dev_6.3.0+dfsg-5+b2_amd64.deb','e3dc3c025cff753f0e288353c82ca2f40bf3cfc81177b8a59c791a998e1c6932'),
 ('m/mpfr4','libmpfr-dev_4.2.2-3_amd64.deb','ce0b35d8bb458c4068473d3eb88f93198d7b4e0d8bdd202e0173253f2d781e56')]

def build(root):
 root=Path(root).resolve();root.mkdir(parents=True,exist_ok=True);sysroot=root/'sysroot'
 for directory,name,sha in PACKAGES:
  path=root/name
  if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=sha:
   with urllib.request.urlopen('https://deb.debian.org/debian/pool/main/'+directory+'/'+name,timeout=120) as response:data=response.read()
   if hashlib.sha256(data).hexdigest()!=sha:raise ValueError('Dependency checksum mismatch: '+name)
   atomic_bytes(path,data)
  marker=sysroot/(name+'.extracted')
  if not marker.exists():subprocess.run(['dpkg-deb','-x',str(path),str(sysroot)],check=True);atomic_bytes(marker,sha.encode())
 include=sysroot/'usr/include';lib=sysroot/'usr/lib/x86_64-linux-gnu';source=Path(__file__).resolve().parent
 for src,name in [('advancing_front.cpp','advancing-front'),('local_ballmerge.cpp','local-ballmerge'),('orient_soup.cpp','orient-soup')]:
  binary=root/name;signature=hashlib.sha256((source/src).read_bytes()).hexdigest();marker=root/(name+'.source-sha256')
  if binary.exists() and marker.exists() and marker.read_text()==signature:continue
  temporary=binary.with_suffix('.building')
  subprocess.run(['g++','-std=c++17','-O2','-DNDEBUG','-I'+str(include),'-I'+str(include/'x86_64-linux-gnu'),str(source/src),'-L'+str(lib),'-l:libmpfr.a','-l:libgmp.a','-o',str(temporary)],check=True)
  temporary.replace(binary);atomic_bytes(marker,signature.encode());print('Built',binary,flush=True)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--directory',required=True);args=p.parse_args();build(args.directory)
