/* LINZ's printed-map raster service, separate from aerial colour processing. */
(function(root){'use strict';
const tiles=key=>`ridge://linz/v1/tiles/topo-raster/WebMercatorQuad/{z}/{x}/{y}.webp?api=${encodeURIComponent(key)}`;
const attribution='<a href="https://www.linz.govt.nz/products-services/maps/new-zealand-topographic-maps">Topo50 / Topo250 © Toitū Te Whenua LINZ · CC BY 4.0</a>';
const source=key=>({type:'raster',tiles:[tiles(key)],tileSize:256,minzoom:0,maxzoom:16,bounds:[165,-49,180,-33],attribution});
const api={tiles,source,attribution};root.RidgeLinzTopo=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
