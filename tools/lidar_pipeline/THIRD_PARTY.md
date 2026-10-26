# Processing dependencies

These are preprocessing dependencies, not browser dependencies.

- CGAL Advancing Front Surface Reconstruction: GPL-3.0-or-later or commercial
  license; https://doc.cgal.org/latest/Advancing_front_surface_reconstruction/.
  The standalone backend source and build instructions are included. Terrain
  observations and generated terrain assets retain their source-data attribution.
- meshoptimizer 1.3.0 official npm WASM simplifier, MIT, Arseny Kapoulkine.
  `tools/meshopt_simplifier.mjs` SHA-256:
  `c3098ee4cb4fc242b84f064c88898e76e44bf58b0e1f693507f323c08cdbb5ed`.
  Existing encoder/decoder licenses remain under `public/assets/vendor/meshoptimizer`.
- Open3D: MIT. Screened Poisson implementation derives from PoissonRecon;
  it is only an evaluated alternative unless validation selects it.
- Original LINZ/Environment Canterbury LiDAR: CC BY 4.0, survey contributors.

Experimental local filter: independently implemented published mathematical
criterion from Parakkat, Ohrhallinger, Eisemann and Memari, *BallMerge*, CGF
2024, https://hal.science/hal-04457247; and Marin et al., *Distributed Surface
Reconstruction*, EG Posters 2024, https://doi.org/10.2312/egp.20241037.
No authors' research-only implementation is included. This uses the same pinned
CGAL backend and retains original input vertex positions.

Do not bundle development environments, third-party binaries, or credentials
into the browser asset tree or source repository.
