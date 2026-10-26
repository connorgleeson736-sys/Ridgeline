"""Compile the native MapLibre direct fragment and verify pixels with Mesa GLES 3.
This tests shader math, orientation, sRGB and depth, not browser performance.
"""
import ctypes as c, ctypes.util, json, subprocess
E=c.CDLL(ctypes.util.find_library('EGL'))
def egl(name,restype,*types):
 f=getattr(E,name);f.restype=restype;f.argtypes=types;return f
P=c.c_void_p;I=c.c_int;U=c.c_uint;F=c.c_float
getproc=egl('eglGetProcAddress',P,c.c_char_p)
def gl(name,restype,*types):return c.CFUNCTYPE(restype,*types)(getproc(name.encode()))
platform=c.CFUNCTYPE(P,U,P,c.POINTER(I))(getproc(b'eglGetPlatformDisplayEXT'))
display=platform(0x31DD,None,None)
assert egl('eglInitialize',U,P,c.POINTER(I),c.POINTER(I))(display,None,None),'EGL unavailable'
assert egl('eglBindAPI',U,U)(0x30A0)
a=(I*15)(0x3033,1,0x3040,0x40,0x3024,8,0x3023,8,0x3022,8,0x3025,24,0x3038,0,0)
config=P();count=I();assert egl('eglChooseConfig',U,P,c.POINTER(I),c.POINTER(P),I,c.POINTER(I))(display,a,c.byref(config),1,c.byref(count)) and count.value
surface=egl('eglCreatePbufferSurface',P,P,P,c.POINTER(I))(display,config,(I*5)(0x3057,64,0x3056,64,0x3038))
context=egl('eglCreateContext',P,P,P,P,c.POINTER(I))(display,config,None,(I*3)(0x3098,3,0x3038))
assert context and egl('eglMakeCurrent',U,P,P,P,P)(display,surface,surface,context)
fragment=subprocess.check_output(['node','--input-type=module','-e',"import * as E from './public/assets/maplibre-gl.mjs';import {createRequire} from 'node:module';const D=createRequire(import.meta.url)('./public/maplibre-direct-sampling.js');console.log(D.shader(E.ridgeDirectTerrainAPI).fragment);"],text=True)
vertex='''#version 300 es
precision highp float;layout(location=0) in vec2 grid;out vec2 v_texture_pos;out float v_fog_depth;
void main(){gl_Position=vec4(grid*2.-1.,0.,1.);v_texture_pos=vec2(grid.x,1.-grid.y);v_fog_depth=0.;}'''
fragment='#version 300 es\nprecision highp float;out vec4 fragColor;\n'+fragment
create=gl('glCreateShader',U,U);source=gl('glShaderSource',None,U,I,c.POINTER(c.c_char_p),P);compile_shader=gl('glCompileShader',None,U);param=gl('glGetShaderiv',None,U,U,c.POINTER(I));log=gl('glGetShaderInfoLog',None,U,I,P,P)
handles=[]
for kind,text in zip([0x8B31,0x8B30],[vertex,fragment]):
 h=create(kind);src=c.c_char_p(text.encode());source(h,1,c.byref(src),None);compile_shader(h);ok=I();param(h,0x8B81,c.byref(ok));message=c.create_string_buffer(8192);log(h,8192,None,message);assert ok.value,message.value;handles.append(h)
program=gl('glCreateProgram',U)()
for h in handles:gl('glAttachShader',None,U,U)(program,h)
gl('glLinkProgram',None,U)(program);ok=I();gl('glGetProgramiv',None,U,U,c.POINTER(I))(program,0x8B82,c.byref(ok));assert ok.value,'Link failed'
gl('glUseProgram',None,U)(program)
def loc(name):return gl('glGetUniformLocation',I,U,c.c_char_p)(program,name.encode())
def u4(name,*v):gl('glUniform4f',None,I,F,F,F,F)(loc(name),*v)
def ui(name,v):gl('glUniform1i',None,I,I)(loc(name),v)
ui('u_is_globe_mode',0);ui('u_texture',0);ui('u_ridge_count',1);ui('u_ridge_overlay',0)
gl('glUniform1f',None,I,F)(loc('u_fog_ground_blend_opacity'),0)
u4('u_ridge_background',0,0,0,0);u4('u_ridge_adjust',0,1,0,1);gl('glUniform3f',None,I,F,F,F)(loc('u_ridge_spin'),1,0,0)
for i in range(8):ui('u_ridge_image'+str(i),4+i);u4('u_ridge_uv'+str(i),1,1,0,0);u4('u_ridge_clip'+str(i),0,0,1,1)
vao=U();gl('glGenVertexArrays',None,I,c.POINTER(U))(1,c.byref(vao));gl('glBindVertexArray',None,U)(vao)
vbo=U();gl('glGenBuffers',None,I,c.POINTER(U))(1,c.byref(vbo));gl('glBindBuffer',None,U,U)(0x8892,vbo)
v=(F*12)(0,0,1,0,0,1,1,0,1,1,0,1);gl('glBufferData',None,U,c.c_ssize_t,P,U)(0x8892,c.sizeof(v),v,0x88E4);gl('glEnableVertexAttribArray',None,U)(0);gl('glVertexAttribPointer',None,U,I,U,U,I,P)(0,2,0x1406,0,8,None)
textures={}
def texture(unit,size,data):
 t=U();gl('glGenTextures',None,I,c.POINTER(U))(1,c.byref(t));textures[unit]=t;gl('glActiveTexture',None,U)(0x84C0+unit);gl('glBindTexture',None,U,U)(0x0DE1,t)
 gl('glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x8058,size,size,0,0x1908,0x1401,(c.c_ubyte*len(data))(*data))
 for p,v in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:gl('glTexParameteri',None,U,U,I)(0x0DE1,p,v)
texture(0,1,[0,0,0,0])
for i in range(8):texture(4+i,2,[255,0,0,255,0,255,0,255,0,0,255,255,128,128,128,255])
gl('glViewport',None,I,I,I,I)(0,0,64,64)
def draw():gl('glDrawArrays',None,U,I,I)(4,0,6)
def pixel(x,y):
 p=(c.c_ubyte*4)();gl('glReadPixels',None,I,I,I,I,U,U,P)(x,y,1,1,0x1908,0x1401,p);return list(p)
draw()
for xy,expected in [((16,48),[255,0,0,255]),((48,48),[0,255,0,255]),((16,16),[0,0,255,255]),((48,16),[128,128,128,255])]:assert pixel(*xy)==expected,(xy,pixel(*xy),expected)
# A semitransparent premultiplied red route/road overlay blends over blue.
texture(0,1,[128,0,0,128]);draw();assert pixel(16,16)==[0,0,255,255],'No-overlay branch must ignore bound overlay pixels'
ui('u_ridge_overlay',1);draw();assert pixel(16,16)==[128,0,127,255],pixel(16,16)
# Test source colour controls before overlay compositing (the native order).
u4('u_ridge_adjust',.2,.8,0,1);draw();actual=pixel(16,16);assert all(abs(a-b)<=1 for a,b in zip(actual,[153,25,102,255])),actual
# Finer quadrant coverage takes precedence over the ancestor without holes.
texture(0,1,[0,0,0,0]);ui('u_ridge_overlay',0);u4('u_ridge_adjust',0,1,0,1);ui('u_ridge_count',2)
texture(4,1,[255,0,255,255]);texture(5,1,[0,255,0,255]);u4('u_ridge_clip0',0,0,.5,.5);u4('u_ridge_uv0',2,2,0,0)
draw();assert pixel(16,48)==[255,0,255,255];assert pixel(48,16)==[0,255,0,255]
# Exercise the production adaptive index layouts on the GPU as adjacent
# patches. Their complete fine boundary must rasterise without a seam/hole.
grids=json.loads(subprocess.check_output(['node','--input-type=module','-e',"import {createRequire} from 'node:module';const A=createRequire(import.meta.url)('./public/adaptive-terrain.js');console.log(JSON.stringify([32,64,128].map(n=>Array.from(A.geometry(n,false).indices))));"],text=True))
ui('u_ridge_count',1);ui('u_ridge_overlay',0);u4('u_ridge_clip0',0,0,1,1);u4('u_ridge_uv0',1,1,0,0);texture(4,1,[255,255,255,255])
grid_vertices=[]
for side in [0,1]:
 for y in range(129):
  for x in range(129):grid_vertices.extend([side*.5+x/256,y/128])
vertices=(F*len(grid_vertices))(*grid_vertices)
gl('glBindBuffer',None,U,U)(0x8892,vbo);gl('glBufferData',None,U,c.c_ssize_t,P,U)(0x8892,c.sizeof(vertices),vertices,0x88E4)
gl('glEnable',None,U)(0x0B44);gl('glFrontFace',None,U)(0x0900)
ebo=U();gl('glGenBuffers',None,I,c.POINTER(U))(1,c.byref(ebo));gl('glBindBuffer',None,U,U)(0x8893,ebo)
for a,b in [(0,1),(1,2),(0,2)]:
 indices=grids[a]+[v+129*129 for v in grids[b]];buffer=(c.c_ushort*len(indices))(*indices)
 gl('glBufferData',None,U,c.c_ssize_t,P,U)(0x8893,c.sizeof(buffer),buffer,0x88E4)
 gl('glClearColor',None,F,F,F,F)(0,0,0,0);gl('glClear',None,U)(0x4000)
 gl('glDrawElements',None,U,I,U,P)(4,len(indices),0x1403,None)
 pixels=(c.c_ubyte*(64*64*4))();gl('glReadPixels',None,I,I,I,I,U,U,P)(0,0,64,64,0x1908,0x1401,pixels)
 assert all(v==255 for v in pixels),'Mixed adaptive grids left a rasterised gap or had wrong winding'
assert gl('glGetError',U)()==0,'OpenGL error'
egl('eglMakeCurrent',U,P,P,P,P)(display,None,None,None);egl('eglDestroyContext',U,P,P)(display,context);egl('eglDestroySurface',U,P,P)(display,surface);egl('eglTerminate',U,P)(display)
print('PASS: production Direct shader pixels, colour/overlay sampling, and GLES rasterisation of adjacent 32/64/128 adaptive grids without holes or winding errors.')
