"""Validated NZTM/NZVD2016 -> ellipsoid -> ECEF -> common local ENU.

No optional grid, ballpark datum or constant vertical offset is permitted.
LOD/source fidelity and datum-operation accuracy remain distinct quantities.
"""
from pathlib import Path
import urllib.request
import numpy as np
import pyproj
from pyproj.aoi import AreaOfInterest
from pyproj.enums import TransformDirection
from pyproj.transformer import TransformerGroup
from PIL import Image
from .state import atomic_bytes,atomic_json,digest,key

GRID_NAME='nz_linz_nzgeoid2016.tif'
GRID_SHA256='b986261d778e30b52b7d9ae24280c704c280c13bb02e51f3dca65fa862d1f027'
GRID_URL='https://cdn.proj.org/'+GRID_NAME


def ensure_grid(directory):
    path=Path(directory)/GRID_NAME
    if path.exists():
        if digest(path)!=GRID_SHA256:raise ValueError('Existing NZGeoid2016 grid checksum mismatch')
        return path
    with urllib.request.urlopen(GRID_URL,timeout=120) as response:data=response.read(8<<20)
    import hashlib
    if hashlib.sha256(data).hexdigest()!=GRID_SHA256:raise ValueError('Downloaded NZGeoid2016 checksum mismatch')
    atomic_bytes(path,data);return path


class Frame:
    def __init__(self,config,grid):
        self.config=config;self.grid=Path(grid).resolve()
        if self.grid.name!=GRID_NAME or digest(self.grid)!=GRID_SHA256:raise ValueError('A verified NZGeoid2016 grid is mandatory')
        pyproj.network.set_network_enabled(False)
        pyproj.datadir.append_data_dir(str(self.grid.parent))
        xy=np.asarray(config['bounds'],float)
        projected=pyproj.Transformer.from_crs('EPSG:2193','EPSG:4167',always_xy=True,allow_ballpark=False,only_best=True)
        corners=np.array([[xy[0],xy[1]],[xy[2],xy[1]],[xy[0],xy[3]],[xy[2],xy[3]],[(xy[0]+xy[2])/2,(xy[1]+xy[3])/2]])
        lon,lat=projected.transform(corners[:,0],corners[:,1],errcheck=True)
        aoi=AreaOfInterest(float(min(lon)),float(min(lat)),float(max(lon)),float(max(lat)))
        group=TransformerGroup('EPSG:2193+7839','EPSG:4959',always_xy=True,allow_ballpark=False,area_of_interest=aoi)
        if not group.best_available or not group.transformers:raise ValueError('Best authoritative vertical operation unavailable')
        self.vertical=pyproj.Transformer.from_crs('EPSG:2193+7839','EPSG:4959',always_xy=True,
            allow_ballpark=False,only_best=True,area_of_interest=aoi)
        definition=self.vertical.definition
        if GRID_NAME not in definition or '@' in definition or 'null' in definition or 'multiplier=1' not in definition:
            raise ValueError('Unexpected or optional vertical grid operation')
        used=[g for op in self.vertical.operations for g in op.grids]
        if not used or any(not g.available or Path(g.full_name).name!=GRID_NAME or digest(g.full_name)!=GRID_SHA256 for g in used):
            raise ValueError('The selected operation did not use the verified physical grid')
        self.ecef=pyproj.Transformer.from_crs('EPSG:4959','EPSG:4978',always_xy=True,
            allow_ballpark=False,only_best=True,area_of_interest=aoi)
        absolute=np.column_stack([corners,np.full(5,float(config['origin'][2]))])
        llh=self.to_geographic(absolute)
        correction=llh[:,2]-absolute[:,2];independent=self.sample_geoid(llh[:,0],llh[:,1])
        geoid_error=float(np.max(np.abs(correction-independent)))
        if geoid_error>1e-5:raise ValueError('Independent bilinear geoid sampling disagrees with PROJ')
        restored=np.column_stack(self.vertical.transform(*llh.T,direction=TransformDirection.INVERSE,errcheck=True))
        inverse_error=float(np.linalg.norm(restored-absolute,axis=1).max())
        if inverse_error>.001:raise ValueError('Coordinate inverse failed the 1 mm round-trip gate')
        origin_llh=llh[-1];self.origin=np.asarray(self.ecef.transform(*origin_llh,errcheck=True))
        # Derive the axes in WGS84 geographic coordinates used by ECEF.
        origin_wgs=pyproj.Transformer.from_crs('EPSG:4978','EPSG:4979',always_xy=True).transform(*self.origin,errcheck=True)
        lam,phi=np.deg2rad(origin_wgs[:2]);sl,cl=np.sin(lam),np.cos(lam);sp,cp=np.sin(phi),np.cos(phi)
        self.rotation=np.array([[-sl,cl,0],[-sp*cl,-sp*sl,cp],[cp*cl,cp*sl,sp]],np.float64)
        matrix=np.eye(4);matrix[:3,:3]=self.rotation.T;matrix[:3,3]=self.origin
        self.transform=matrix.flatten(order='F').tolist()
        horizontal=self.ecef.get_last_used_operation()
        self.metadata=dict(source_crs='EPSG:2193+7839',geographic_crs='EPSG:4959',target_crs='EPSG:4978',
            grid=dict(name=GRID_NAME,sha256=GRID_SHA256,url=GRID_URL),pyproj_version=pyproj.__version__,proj_version=pyproj.proj_version_str,
            vertical_operation=dict(description=self.vertical.description,pipeline=self.vertical.definition,accuracy_m=self.vertical.accuracy),
            horizontal_operation=dict(description=horizontal.description,pipeline=horizontal.definition,accuracy_m=horizontal.accuracy,
                epoch=None,note='Published static NZGD2000-to-WGS84 operation; no centimetre-level dynamic epoch claim'),
            anchor_nztm_nzvd=absolute[-1].tolist(),anchor_nzgd2000_llh=origin_llh.tolist(),anchor_ecef=self.origin.tolist(),
            enu_to_ecef_column_major=self.transform,geoid_corrections_at_corners_center_m=correction.tolist(),
            independent_geoid_max_error_m=geoid_error,vertical_roundtrip_max_error_m=inverse_error,
            gltf_axes='(E,U,-N); runtime glTF Y-up to tile Z-up yields (E,N,U)',
            accuracy_note='Datum/grid published accuracy is not LOD error or source survey accuracy')

    def sample_geoid(self,longitude,latitude):
        with Image.open(self.grid) as image:
            scale=image.tag_v2[33550];tie=image.tag_v2[33922];keys=image.tag_v2[34735]
            geokeys={keys[i]:keys[i+3] for i in range(4,len(keys),4)}
            if geokeys.get(1025)!=2 or geokeys.get(2048)!=4167:raise ValueError('Expected geographic PixelIsPoint grid')
            values=np.asarray(image,dtype=np.float64)
        x=(np.asarray(longitude)-tie[3])/scale[0]+tie[0]
        y=(tie[4]-np.asarray(latitude))/scale[1]+tie[1]
        ix=np.floor(x).astype(int);iy=np.floor(y).astype(int)
        if np.any(ix<0)|np.any(iy<0)|np.any(ix+1>=values.shape[1])|np.any(iy+1>=values.shape[0]):
            raise ValueError('Coordinates outside verified geoid grid')
        fx=x-ix;fy=y-iy
        result=(values[iy,ix]*(1-fx)+values[iy,ix+1]*fx)*(1-fy)+(values[iy+1,ix]*(1-fx)+values[iy+1,ix+1]*fx)*fy
        if not np.isfinite(result).all():raise ValueError('Invalid geoid interpolation')
        return result

    def to_geographic(self,absolute):
        absolute=np.asarray(absolute,dtype=np.float64)
        result=np.column_stack(self.vertical.transform(*absolute.T,errcheck=True))
        if not np.isfinite(result).all():raise ValueError('Nonfinite transformed geographic point')
        return result

    def to_enu(self,localized):
        absolute=np.asarray(localized,dtype=np.float64)+np.asarray(self.config['origin'])
        geographic=self.to_geographic(absolute)
        ecef=np.column_stack(self.ecef.transform(*geographic.T,errcheck=True))
        enu=(ecef-self.origin)@self.rotation.T
        if not np.isfinite(enu).all():raise ValueError('Nonfinite local ECEF geometry')
        return enu

    @staticmethod
    def to_gltf(enu):
        enu=np.asarray(enu);return np.column_stack([enu[:,0],enu[:,2],-enu[:,1]])

    @staticmethod
    def from_gltf(positions):
        p=np.asarray(positions);return np.column_stack([p[:,0],-p[:,2],p[:,1]])


def checkpoint(state):
    directory=state.root/'data/coordinates';grid=ensure_grid(directory);frame=Frame(state.config,grid)
    signature=key(dict(stage='validated-coordinate-frame-v1',grid=GRID_SHA256,config=state.config,
        implementation=digest(__file__),pyproj=pyproj.__version__,proj=pyproj.proj_version_str))
    task='coordinate-frame'
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return frame
        state.start(task,signature)
        try:
            metadata=directory/'frame.json';atomic_json(metadata,frame.metadata)
            state.stage(task,signature,[grid,metadata],frame.metadata)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('Coordinate grid/frame await persistent checkpoint')
            return frame
        except Exception as error:
            if not state.valid(task,signature):state.fail(task,error)
            raise
