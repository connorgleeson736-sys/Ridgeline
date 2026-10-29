//! Pure, host-testable geographic and terrain primitives. No Android or browser runtime.
pub const EARTH_CIRCUMFERENCE: f64 = 40075016.68557849;
pub fn mercator(lon: f64, lat: f64) -> [f64; 2] {
    let lat = lat.clamp(-85.0511287798066,85.0511287798066).to_radians();
    [(lon+180.)/360., (1.-(lat.tan()+1./lat.cos()).ln()/std::f64::consts::PI)/2.]
}
pub fn terrain_rgb(rgb:[u8;3])->f64 { -10000.+(rgb[0] as f64*65536.+rgb[1] as f64*256.+rgb[2] as f64)*0.1 }
pub fn terrarium(rgb:[u8;3])->f64 {rgb[0] as f64*256.+rgb[1] as f64+rgb[2] as f64/256.-32768.}
#[derive(Clone,Copy,Debug,PartialEq,Eq,Hash)]
pub struct TileId {pub z:u8,pub x:u32,pub y:u32}
impl TileId {
 pub fn parent(self)->Option<Self>{(self.z>0).then(||Self{z:self.z-1,x:self.x/2,y:self.y/2})}
 pub fn bounds(self)->[f64;4]{let n=2f64.powi(self.z as i32);[self.x as f64/n,self.y as f64/n,(self.x as f64+1.)/n,(self.y as f64+1.)/n]}
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn encoding_is_height_not_channel_interpolation(){assert!((terrain_rgb([1,173,176])-1000.).abs()<1e-8);assert_eq!(terrarium([128,0,0]),0.);}
 #[test] fn adjacent_tiles_share_exact_edges(){for z in 1..22{let a=TileId{z,x:0,y:0}.bounds();let b=TileId{z,x:1,y:0}.bounds();assert_eq!(a[2],b[0]);}}
 #[test] fn projection_reference(){assert_eq!(mercator(0.,0.),[0.5,0.5]);let p=mercator(170.141,-43.595);assert!(p[0]>0.97&&p[1]>0.63&&p[1]<0.64);}
}
