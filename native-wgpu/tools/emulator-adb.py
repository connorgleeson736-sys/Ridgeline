#!/usr/bin/env python3
"""Minimal local ADB transport for restricted build hosts with read-only default adb home.
Test tooling only. Uses emulator loopback adbd, no external connection or app runtime.
"""
import argparse
from pathlib import Path
import socket
import struct
import sys

class Emulator:
    def __init__(self, port=5555, timeout=90):
        self.socket = socket.create_connection(('127.0.0.1', port), 10)
        self.socket.settimeout(timeout)
        self.send(b'CNXN',0x01000000,1024*1024,b'host::\0')
        cmd, _, self.maximum, _ = self.recv()
        if cmd != b'CNXN':
            raise RuntimeError('Emulator adbd must authorize this host before testing')
    def send(self,cmd,a,b,data=b''):
        n=int.from_bytes(cmd,'little')
        self.socket.sendall(struct.pack('<6I',n,a,b,len(data),sum(data),n^0xffffffff)+data)
    def read(self,n):
        data=bytearray()
        while len(data)<n:
            part=self.socket.recv(n-len(data))
            if not part: raise EOFError('ADB stream closed')
            data.extend(part)
        return bytes(data)
    def recv(self):
        c,a,b,n,checksum,magic=struct.unpack('<6I',self.read(24))
        if magic!=c^0xffffffff or n>16*1024*1024: raise ValueError('Invalid ADB packet')
        data=self.read(n)
        if checksum!=sum(data): raise ValueError('Invalid ADB checksum')
        return c.to_bytes(4,'little'),a,b,data
    def run(self,command,input_path=None):
        self.send(b'OPEN',1,0,('exec:'+command+'\0').encode())
        cmd,remote,_,data=self.recv()
        if cmd!=b'OKAY': raise RuntimeError('ADB service rejected: '+str(data))
        if input_path:
            with open(input_path,'rb') as stream:
                while chunk:=stream.read(min(256*1024,self.maximum)):
                    self.send(b'WRTE',1,remote,chunk)
                    while True:
                        cmd,a,b,data=self.recv()
                        if cmd==b'OKAY': break
                        if cmd==b'WRTE':
                            sys.stdout.buffer.write(data);self.send(b'OKAY',1,remote)
                        else: raise RuntimeError('Install stream closed early: '+str(data))
        while True:
            cmd,_,_,data=self.recv()
            if cmd==b'WRTE':
                sys.stdout.buffer.write(data);self.send(b'OKAY',1,remote)
            elif cmd==b'CLSE':
                self.send(b'CLSE',1,remote);break
            elif cmd==b'OKAY': continue # An outstanding final write acknowledgement may arrive after stdin completes.
            else: raise RuntimeError('Unexpected ADB packet: '+str(cmd))
        self.socket.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('command');p.add_argument('--input');p.add_argument('--port',type=int,default=5555);p.add_argument('--timeout',type=float,default=90)
    args=p.parse_args();Emulator(args.port,args.timeout).run(args.command,args.input)
