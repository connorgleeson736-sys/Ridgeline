// Backend-only original-point reconstruction using CGAL's Advancing Front API.
// CGAL package license: GPL-3.0-or-later OR commercial. See THIRD_PARTY.md.
// This program retains original vertex positions; it never creates a heightfield.
#include <CGAL/Exact_predicates_inexact_constructions_kernel.h>
#include <CGAL/Advancing_front_surface_reconstruction.h>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>

using Kernel=CGAL::Exact_predicates_inexact_constructions_kernel;
using Point=Kernel::Point_3;
using Face=std::array<std::size_t,3>;

struct SupportedEdge {
 double maximum_squared;
 template<class Front,class Cell>
 double operator()(const Front& front,Cell& cell,const int& opposite)const{
  std::array<int,3> ids{};int next=0;
  for(int k=0;k<4;k++)if(k!=opposite)ids[next++]=k;
  for(int k=0;k<3;k++){
   const auto& a=cell->vertex(ids[k])->point();
   const auto& b=cell->vertex(ids[(k+1)%3])->point();
   if(CGAL::to_double(CGAL::squared_distance(a,b))>maximum_squared)return front.infinity();
  }
  return front.smallest_radius_delaunay_sphere(cell,opposite);
 }
};

int main(int argc,char** argv){
 try{
  if(argc!=4)throw std::runtime_error("Usage: advancing-front input.f32 output.u32 maximum-edge-metres");
  std::ifstream in(argv[1],std::ios::binary|std::ios::ate);
  if(!in)throw std::runtime_error("Cannot open original points");
  const auto bytes=in.tellg();
  if(bytes<=0||bytes%12!=0)throw std::runtime_error("Invalid float32 XYZ input");
  const std::size_t count=static_cast<std::size_t>(bytes)/12;
  if(count>1200000)throw std::runtime_error("Point job exceeds bounded memory limit");
  in.seekg(0);std::vector<Point> points;points.reserve(count);
  for(std::size_t i=0;i<count;i++){
   float p[3];in.read(reinterpret_cast<char*>(p),12);
   if(!in||!std::isfinite(p[0])||!std::isfinite(p[1])||!std::isfinite(p[2]))throw std::runtime_error("Invalid measured point");
   points.emplace_back(p[0],p[1],p[2]);
  }
  const double edge=std::stod(argv[3]);if(!(edge>0&&edge<=8))throw std::runtime_error("Unsupported edge limit");
  std::cerr<<"Advancing front: "<<count<<" unchanged original vertices\n";
  std::vector<Face> faces;
  CGAL::advancing_front_surface_reconstruction(points.begin(),points.end(),std::back_inserter(faces),SupportedEdge{edge*edge},5.0,.52);
  std::ofstream out(argv[2],std::ios::binary|std::ios::trunc);
  for(const auto& f:faces){
   std::uint32_t packed[3];
   for(int k=0;k<3;k++){if(f[k]>=count)throw std::runtime_error("Invalid reconstructed index");packed[k]=static_cast<std::uint32_t>(f[k]);}
   out.write(reinterpret_cast<const char*>(packed),12);
  }
  if(!out)throw std::runtime_error("Failed writing reconstruction");
  std::cerr<<"Reconstructed "<<faces.size()<<" full-XYZ triangles\n";
  return 0;
 }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
