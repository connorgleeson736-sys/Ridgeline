// Geometry-preserving combinatorial sheet separation. CGAL GPL/commercial.
#include <CGAL/Exact_predicates_inexact_constructions_kernel.h>
#include <CGAL/Polygon_mesh_processing/orient_polygon_soup.h>
#include <CGAL/Polygon_mesh_processing/polygon_soup_to_polygon_mesh.h>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <vector>
using K=CGAL::Exact_predicates_inexact_constructions_kernel;
template<class T>std::vector<T> read(const char* path){
 std::ifstream in(path,std::ios::binary|std::ios::ate);if(!in)throw std::runtime_error("Missing soup input");
 auto bytes=in.tellg();if(bytes<0||bytes%sizeof(T))throw std::runtime_error("Invalid binary soup input");
 std::vector<T> data(static_cast<std::size_t>(bytes)/sizeof(T));in.seekg(0);in.read(reinterpret_cast<char*>(data.data()),bytes);
 if(!in)throw std::runtime_error("Incomplete soup input");return data;
}
int main(int argc,char** argv){try{
 if(argc!=5)throw std::runtime_error("Usage: orient-soup input.f32 input.u32 output.f32 output.u32");
 auto xyz=read<float>(argv[1]);auto indices=read<std::uint32_t>(argv[2]);
 if(xyz.size()%3||indices.size()%3||xyz.size()/3>2000000||indices.size()/3>6000000)throw std::runtime_error("Invalid or oversized soup");
 std::vector<K::Point_3> points;points.reserve(xyz.size()/3);
 for(std::size_t i=0;i<xyz.size();i+=3)points.emplace_back(xyz[i],xyz[i+1],xyz[i+2]);
 std::vector<std::vector<std::size_t>> polygons;polygons.reserve(indices.size()/3);
 for(std::size_t i=0;i<indices.size();i+=3){
  for(int k=0;k<3;k++)if(indices[i+k]>=points.size())throw std::runtime_error("Invalid soup face index");
  polygons.push_back({indices[i],indices[i+1],indices[i+2]});
 }
 const auto before=points.size();
 CGAL::Polygon_mesh_processing::orient_polygon_soup(points,polygons);
 if(!CGAL::Polygon_mesh_processing::is_polygon_soup_a_polygon_mesh(polygons))throw std::runtime_error("Soup separation failed topology gate");
 std::ofstream vp(argv[3],std::ios::binary|std::ios::trunc),ip(argv[4],std::ios::binary|std::ios::trunc);
 for(const auto& p:points){float a[3]={float(p.x()),float(p.y()),float(p.z())};vp.write(reinterpret_cast<const char*>(a),12);}
 for(const auto& p:polygons){std::uint32_t a[3]={std::uint32_t(p[0]),std::uint32_t(p[1]),std::uint32_t(p[2])};ip.write(reinterpret_cast<const char*>(a),12);}
 if(!vp||!ip)throw std::runtime_error("Failed saving separated sheets");
 std::cerr<<"Separated "<<points.size()-before<<" vertex copies; retained "<<polygons.size()<<" original triangles\n";return 0;
}catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}}
