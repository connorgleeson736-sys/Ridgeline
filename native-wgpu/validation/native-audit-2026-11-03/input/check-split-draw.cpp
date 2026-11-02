// Standalone Linux EGL / OpenGL ES 3 draw proof, not the Android app.
// g++ -O2 -std=c++17 check-split-draw.cpp -o /tmp/ridge-split -l:libEGL.so.1 -l:libGL.so.1
// EGL_PLATFORM=surfaceless /tmp/ridge-split
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>
using U=unsigned;using I=int;using H=void*;
extern "C" {
H eglGetProcAddress(const char*); U eglInitialize(H,I*,I*); U eglBindAPI(U);
U eglChooseConfig(H,const I*,H*,I,I*); H eglCreatePbufferSurface(H,H,const I*);
H eglCreateContext(H,H,H,const I*); U eglMakeCurrent(H,H,H,H);
const unsigned char* glGetString(U); U glCreateShader(U); void glShaderSource(U,I,const char*const*,const I*);
void glCompileShader(U);void glGetShaderiv(U,U,I*);void glGetShaderInfoLog(U,I,I*,char*);
U glCreateProgram();void glAttachShader(U,U);void glLinkProgram(U);void glGetProgramiv(U,U,I*);
I glGetUniformLocation(U,const char*);void glUniform1i(I,I);void glUniform2f(I,float,float);
void glGenTextures(I,U*);void glBindTexture(U,U);void glTexParameteri(U,U,I);
void glTexImage2D(U,I,I,I,I,I,U,U,const void*);void glUseProgram(U);void glViewport(I,I,I,I);void glEnable(U);void glClearColor(float,float,float,float);
void glClear(U);void glGenBuffers(I,U*);void glBindBuffer(U,U);void glBufferData(U,std::ptrdiff_t,const void*,U);
void glGenVertexArrays(I,U*);void glBindVertexArray(U);void glEnableVertexAttribArray(U);
void glVertexAttribPointer(U,I,U,unsigned char,I,const void*);void glDrawElements(U,I,U,const void*);
void glReadPixels(I,I,I,I,U,U,void*);U glGetError();void glDeleteBuffers(I,const U*);
}
constexpr I W=640,HGT=512;
constexpr U Array=0x8892,Element=0x8893,Static=0x88e4;
using Index=std::vector<uint16_t>;using Image=std::vector<uint32_t>;
U shader(U type,const char* source){
    auto id=glCreateShader(type);glShaderSource(id,1,&source,nullptr);glCompileShader(id);
    int ok=0;glGetShaderiv(id,0x8b81,&ok);if(!ok){char log[2000]={};glGetShaderInfoLog(id,sizeof(log),nullptr,log);std::fprintf(stderr,"%s\n",log);std::exit(2);}return id;
}
U buffer(U type,const void* data,size_t bytes){
    U id;glGenBuffers(1,&id);glBindBuffer(type,id);glBufferData(type,bytes,data,Static);return id;
}
struct Draw{U id;I count;};
Draw upload(const Index& indices){return {buffer(Element,indices.data(),indices.size()*sizeof(uint16_t)),I(indices.size())};}
void draw(const Draw& d){glBindBuffer(Element,d.id);glDrawElements(4,d.count,0x1403,nullptr);}
Image capture(){Image pixels(W*HGT);glReadPixels(0,0,W,HGT,0x1908,0x1401,pixels.data());return pixels;}
size_t changed(const Image& a,const Image& b){size_t n=0;for(size_t i=0;i<a.size();i++)n+=a[i]!=b[i];return n;}
int main(){
    using Platform=H(*)(U,H,const I*);auto get=reinterpret_cast<Platform>(eglGetProcAddress("eglGetPlatformDisplayEXT"));assert(get);
    H display=get(0x31dd,nullptr,nullptr);assert(eglInitialize(display,nullptr,nullptr));assert(eglBindAPI(0x30a0));
    const I attributes[]={0x3033,1,0x3040,0x40,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3025,24,0x3038};
    I count=0;H config;assert(eglChooseConfig(display,attributes,&config,1,&count)&&count);
    const I surfaceAttributes[]={0x3057,W,0x3056,HGT,0x3038};H surface=eglCreatePbufferSurface(display,config,surfaceAttributes);
    const I contextAttributes[]={0x3098,3,0x3038};H context=eglCreateContext(display,config,nullptr,contextAttributes);
    assert(surface&&context&&eglMakeCurrent(display,surface,surface,context));
    std::printf("Driver: %s (%s)\n",glGetString(0x1f01),glGetString(0x1f02));
    const char* vs=R"(#version 300 es
      precision highp float;layout(location=0) in vec3 pos;out highp vec3 v;uniform bool flatMap;uniform vec2 offset;
      void main(){if(flatMap){v=vec3(pos.xy,0.);gl_Position=vec4(pos.xy*1.5-0.75+offset,0.,1.);return;}v=pos;float w=1.0+0.15*pos.y;gl_Position=vec4((pos.x-0.5)*1.7+0.07*(pos.y-0.5),(pos.y-0.5)*1.45+0.18*pos.z,0.3*pos.y-0.25*pos.z,w);})";
    const char* fs=R"(#version 300 es
      precision highp float;in highp vec3 v;out vec4 color;uniform bool flatMap;uniform highp sampler2D photo;
      void main(){if(flatMap){color=texelFetch(photo,clamp(ivec2(floor(v.xy*256.0)),ivec2(0),ivec2(255)),0);return;}color=vec4(0.3+v.x*0.5,0.3+v.y*0.5,0.45+0.4*v.z,1.0);})";
    U program=glCreateProgram();glAttachShader(program,shader(0x8b31,vs));glAttachShader(program,shader(0x8b30,fs));
    glLinkProgram(program);I linked=0;glGetProgramiv(program,0x8b82,&linked);assert(linked);glUseProgram(program);
    U vao;glGenVertexArrays(1,&vao);glBindVertexArray(vao);
    std::vector<std::array<float,3>> points;
    for(int y=0;y<=128;y++)for(int x=0;x<=128;x++){
        float u=x/128.f,v=y/128.f,h=.4f*std::sin(4*u+v)*std::cos(6*v)+.23f*std::sin(11*u-4*v);
        points.push_back({u,v,h});
    }
    buffer(Array,points.data(),points.size()*sizeof(points[0]));glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,0x1406,0,sizeof(points[0]),nullptr);
    glViewport(0,0,W,HGT);glEnable(0x0b71);glClearColor(0,0,0,0);
    constexpr U mask=0x4000|0x0100;
    for(int q=0;q<4;q++){
        Index all,core,edge;
        for(int y=0;y<128;y++)for(int x=0;x<128;x++){
            int a=y*129+x;std::array<uint16_t,6> cell={uint16_t(a),uint16_t(a+129),uint16_t(a+1),uint16_t(a+1),uint16_t(a+129),uint16_t(a+130)};
            all.insert(all.end(),cell.begin(),cell.end());
            bool outside=x==((q&1)?127:0)||y==((q&2)?127:0);
            auto& group=outside?edge:core;group.insert(group.end(),cell.begin(),cell.end());
        }
        auto full=upload(all),interior=upload(core),strip=upload(edge);
        glClear(mask);draw(full);auto reference=capture();
        glClear(mask);draw(interior);auto partial=capture();draw(strip);auto complete=capture();
        const auto missing=changed(reference,partial),errors=changed(reference,complete);
        std::printf("quadrant=%d coreCells=%d rimCells=%d changedPixelsBeforeRim=%zu finalRGBAMismatches=%zu\n",q,interior.count/6,strip.count/6,missing,errors);
        assert(missing>0&&errors==0&&glGetError()==0);U ids[]={full.id,interior.id,strip.id};glDeleteBuffers(3,ids);
    }
    // Unshaded 2D control. Sample the same 256-square nearest-filtered texture
    // through the production step-4 interior / fine-edge topology and one quad.
    glUniform1i(glGetUniformLocation(program,"flatMap"),1);
    U photo;glGenTextures(1,&photo);glBindTexture(0x0de1,photo);
    glTexParameteri(0x0de1,0x2801,0x2600);glTexParameteri(0x0de1,0x2800,0x2600);
    std::vector<unsigned char> rgba(256*256*4);
    for(int y=0;y<256;y++)for(int x=0;x<256;x++){auto off=4*(256*y+x);rgba[off]=x;rgba[off+1]=y;rgba[off+2]=(x*17+y*7)%256;rgba[off+3]=255;}
    glTexImage2D(0x0de1,0,0x1908,256,256,0,0x1908,0x1401,rgba.data());
    Index grid;auto index=[](int x,int y){return static_cast<uint16_t>(y*129+x);};
    for(int y=0;y<128;y+=4)for(int x=0;x<128;x+=4){
        if(x>0&&y>0&&x+4<128&&y+4<128){grid.insert(grid.end(),{index(x,y),index(x,y+4),index(x+4,y),index(x+4,y),index(x,y+4),index(x+4,y+4)});continue;}
        Index ring;
        auto edge=[&](int ax,int ay,int bx,int by,bool fine){int n=fine?4:1;for(int i=0;i<n;i++)ring.push_back(index(ax+(bx-ax)*i/n,ay+(by-ay)*i/n));};
        edge(x,y,x,y+4,x==0);edge(x,y+4,x+4,y+4,y+4==128);edge(x+4,y+4,x+4,y,x+4==128);edge(x+4,y,x,y,y==0);
        for(size_t k=0;k<ring.size();k++)grid.insert(grid.end(),{index(x+2,y+2),ring[k],ring[(k+1)%ring.size()]});
    }
    Index quad={index(0,0),index(0,128),index(128,0),index(128,0),index(0,128),index(128,128)};
    auto mesh=upload(grid),rectangle=upload(quad);
    for(auto offset: {0.f,0.00137f}){
        glUniform2f(glGetUniformLocation(program,"offset"),offset,offset*0.7f);
        glClear(mask);draw(mesh);auto tiled=capture();glClear(mask);draw(rectangle);auto flat=capture();
        const auto errors=changed(tiled,flat);size_t covered=0,coverageDiffs=0;
        for(size_t i=0;i<tiled.size();i++){covered+=(tiled[i]>>24)!=0;coverageDiffs+=((tiled[i]>>24)!=(flat[i]>>24));}
        std::printf("2D offset=%g step4Triangles=%d quadTriangles=%d covered=%zu sampleMismatches=%zu coverageMismatches=%zu\n",offset,mesh.count/3,rectangle.count/3,covered,errors,coverageDiffs);
        assert(mesh.count/3==2680&&covered>0&&coverageDiffs==0&&glGetError()==0);
    }

}
