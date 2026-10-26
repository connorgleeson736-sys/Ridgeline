// Independent implementation of the published LOCAL BallMerge criterion.
// Parakkat et al., Computer Graphics Forum 2024; Marin et al., EG Posters 2024.
// No authors' research-only source is incorporated. CGAL backend: GPL/commercial.
#include <CGAL/Exact_predicates_inexact_constructions_kernel.h>
#include <CGAL/Delaunay_triangulation_3.h>
#include <CGAL/Triangulation_vertex_base_with_info_3.h>
#include <CGAL/Triangulation_cell_base_with_info_3.h>
#include <CGAL/Delaunay_triangulation_cell_base_3.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <vector>
using K=CGAL::Exact_predicates_inexact_constructions_kernel;
struct Ball{K::Point_3 center;double radius=0.;};
using V=CGAL::Triangulation_vertex_base_with_info_3<std::uint32_t,K>;
using C=CGAL::Triangulation_cell_base_with_info_3<Ball,K,CGAL::Delaunay_triangulation_cell_base_3<K>>;
using TDS=CGAL::Triangulation_data_structure_3<V,C>;
using DT=CGAL::Delaunay_triangulation_3<K,TDS>;
int main(int argc,char** argv){try{
 if(argc!=5)throw std::runtime_error("Usage: local-ballmerge points.f32 faces.u32 maximum-edge delta");
 std::ifstream input(argv[1],std::ios::binary|std::ios::ate);
 if(!input)throw std::runtime_error("Missing point input");auto bytes=input.tellg();
 if(bytes<=0||bytes%12||bytes/12>1200000)throw std::runtime_error("Invalid or oversized point job");
 const std::size_t count=static_cast<std::size_t>(bytes)/12;input.seekg(0);
 std::vector<std::pair<K::Point_3,std::uint32_t>> points;points.reserve(count);
 for(std::size_t i=0;i<count;i++){
  float p[3];input.read(reinterpret_cast<char*>(p),12);
  if(!input||!std::isfinite(p[0])||!std::isfinite(p[1])||!std::isfinite(p[2]))throw std::runtime_error("Invalid original point");
  points.emplace_back(K::Point_3(p[0],p[1],p[2]),static_cast<std::uint32_t>(i));
 }
 const double edge=std::stod(argv[3]),delta=std::stod(argv[4]);
 if(!(edge>0&&edge<=8&&delta>0&&delta<2))throw std::runtime_error("Invalid local reconstruction parameters");
 std::cerr<<"Local BallMerge: "<<count<<" unchanged original vertices\n";
 DT dt;dt.insert(points.begin(),points.end());
 std::cerr<<"Intrinsic dimension: "<<dt.dimension()<<"\n";
 if(dt.dimension()<2)throw std::runtime_error("Point/line-only observations retained; no supported surface can be inferred");
 if(dt.dimension()==2){
  // CGAL's intrinsic coplanar Delaunay faces retain full original XYZ. A
  // dimension-2 cell has its sole facet opposite vertex index3. No projection
  // to geographical XY, approximate plane fit, or synthesized samples.
  std::ofstream output(argv[2],std::ios::binary|std::ios::trunc);std::size_t selected=0;
  for(auto facet=dt.finite_facets_begin();facet!=dt.finite_facets_end();++facet){
   auto cell=facet->first;
   if(facet->second!=3)throw std::runtime_error("Unexpected intrinsic planar facet representation");
   bool supported=true;
   for(int i=0;i<3;i++)if(CGAL::to_double(CGAL::squared_distance(cell->vertex(i)->point(),cell->vertex((i+1)%3)->point()))>edge*edge)supported=false;
   if(!supported)continue;
   std::uint32_t indices[3];for(int i=0;i<3;i++)indices[i]=cell->vertex(i)->info();
   std::sort(indices,indices+3);output.write(reinterpret_cast<const char*>(indices),12);selected++;
  }
  output.flush();if(!output)throw std::runtime_error("Failed writing intrinsic planar surface");
  std::cerr<<"Selected "<<selected<<" original-point intrinsic planar triangles\n";return 0;
 }
 for(auto cell=dt.finite_cells_begin();cell!=dt.finite_cells_end();++cell){
  auto center=dt.dual(cell);cell->info()={center,std::sqrt(CGAL::to_double(CGAL::squared_distance(center,cell->vertex(0)->point())))};
 }
 std::ofstream output(argv[2],std::ios::binary|std::ios::trunc);std::size_t selected=0,hull=0;
 for(auto facet=dt.finite_facets_begin();facet!=dt.finite_facets_end();++facet){
  auto cell=facet->first;int opposite=facet->second;auto other=cell->neighbor(opposite);
  if(dt.is_infinite(cell)){opposite=other->index(cell);cell=other;other=cell->neighbor(opposite);}
  std::array<int,3> ids{};int next=0;for(int i=0;i<4;i++)if(i!=opposite)ids[next++]=i;
  bool supported=true;
  for(int i=0;i<3;i++)if(CGAL::to_double(CGAL::squared_distance(cell->vertex(ids[i])->point(),cell->vertex(ids[(i+1)%3])->point()))>edge*edge)supported=false;
  if(!supported)continue;
  const auto& a=cell->vertex(ids[0])->point();const auto& b=cell->vertex(ids[1])->point();const auto& c=cell->vertex(ids[2])->point();
  const auto& own=cell->info();double ratio;
  if(dt.is_infinite(other)){
   // Limit of the neighboring empty sphere as its center/radius go to
   // infinity along the outward facet normal: ir = 1 + signed_height/r.
   auto normal=CGAL::cross_product(b-a,c-a);
   if(normal*(cell->vertex(opposite)->point()-a)>0)normal=-normal;
   double length=std::sqrt(CGAL::to_double(normal.squared_length()));
   if(length==0||own.radius==0)continue;
   ratio=1+CGAL::to_double(normal*(own.center-a))/(length*own.radius);
  }else{
   const auto& neighbor=other->info();double r=std::min(own.radius,neighbor.radius);
   if(r==0)continue;
   double distance=std::sqrt(CGAL::to_double(CGAL::squared_distance(own.center,neighbor.center)));
   ratio=(own.radius+neighbor.radius-distance)/r;
  }
  if(!std::isfinite(ratio)||ratio>=delta)continue;
  std::uint32_t indices[3];for(int i=0;i<3;i++)indices[i]=cell->vertex(ids[i])->info();
  std::sort(indices,indices+3);output.write(reinterpret_cast<const char*>(indices),12);
  selected++;if(dt.is_infinite(other))hull++;
 }
 output.flush();if(!output)throw std::runtime_error("Failed writing local surface");
 std::cerr<<"Selected "<<selected<<" original-point triangles; hull facets "<<hull<<"\n";
 return 0;
}catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}}
