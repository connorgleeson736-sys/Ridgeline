//! A linked profile cursor is its own tiny batch, never a rebuild of all route geometry.
use crate::{Renderer, routes::Vertex};
impl Renderer {
    pub fn inspect_point(&mut self, point: Option<[f64; 2]>) {
        if self.inspection_point != point {
            self.inspection_point = point;
            self.inspection_dirty = true;
        }
    }
    pub fn location_point(&mut self, point: Option<[f64; 2]>) {
        if self.location_point != point {
            self.location_point = point;
            self.inspection_dirty = true;
        }
    }
    pub(crate) fn rebuild_inspection(&mut self) {
        self.inspection_anchor = self
            .inspection_point
            .or(self.location_point)
            .unwrap_or([0.5, 0.5]);
        let anchor = self.inspection_anchor;
        let mut vertices = Vec::with_capacity(24);
        for (point, color) in [
            (
                self.location_point,
                [39. / 255., 122. / 255., 212. / 255., 1.],
            ),
            (
                self.inspection_point,
                [72. / 255., 115. / 255., 88. / 255., 1.],
            ),
        ] {
            if let Some(point) = point {
                if let Some(height) = self.terrain_height(point) {
                    let p = [
                        (point[0] - anchor[0]) as f32,
                        (point[1] - anchor[1]) as f32,
                        height as f32 * self.map_style.height_scale(),
                    ];
                    for (radius, color) in [(8., [1., 1., 1., 1.]), (6., color)] {
                        vertices.push(Vertex {
                            start: p,
                            end: p,
                            corner: [0., 0., radius],
                            color,
                            style: [0.; 3],
                        });
                    }
                }
            }
        }
        self.inspection_gpu
            .upload(&self.device, &self.queue, &vertices);
        self.inspection_dirty = false;
    }
}
