//! Lazy, bounded array pages. Layer generations prevent a stale lease from
//! releasing a newer occupant. All writes/draws use the renderer's one queue.
use std::sync::{Arc, Mutex};
struct Slots {
    free: Vec<u32>,
    generations: Vec<u64>,
    live: usize,
}
struct Page {
    texture: wgpu::Texture,
    bind: Arc<wgpu::BindGroup>,
    slots: Mutex<Slots>,
}
pub(crate) struct LayerLease {
    page: Arc<Page>,
    pub layer: u32,
    generation: u64,
}
impl LayerLease {
    pub fn texture(&self) -> wgpu::Texture {
        self.page.texture.clone()
    }
    pub fn bind(&self) -> Arc<wgpu::BindGroup> {
        self.page.bind.clone()
    }
}
impl Drop for LayerLease {
    fn drop(&mut self) {
        let mut slots = self.page.slots.lock().unwrap();
        let index = self.layer as usize;
        if slots.generations[index] == self.generation {
            slots.free.push(self.layer);
            slots.live -= 1;
        }
    }
}
pub(crate) struct TextureArrays {
    pages: Vec<Arc<Page>>,
    width: u32,
    height: u32,
    mips: u32,
    layers: u32,
    max_pages: usize,
    format: wgpu::TextureFormat,
    usage: wgpu::TextureUsages,
    pub creations: u64,
    pub reuses: u64,
}
impl TextureArrays {
    pub fn new(
        width: u32,
        height: u32,
        mips: u32,
        layers: u32,
        max_slots: usize,
        format: wgpu::TextureFormat,
        usage: wgpu::TextureUsages,
    ) -> Self {
        Self {
            pages: vec![],
            width,
            height,
            mips,
            layers,
            max_pages: (max_slots + 1).div_ceil(layers as usize),
            format,
            usage,
            creations: 0,
            reuses: 0,
        }
    }
    pub fn allocate(
        &mut self,
        device: &wgpu::Device,
        layout: &wgpu::BindGroupLayout,
        sampler: Option<&wgpu::Sampler>,
    ) -> Result<LayerLease, String> {
        let page = if let Some(page) = self
            .pages
            .iter()
            .find(|p| !p.slots.lock().unwrap().free.is_empty())
        {
            self.reuses += 1;
            page.clone()
        } else {
            if self.pages.len() >= self.max_pages {
                return Err("Texture array pool is full".into());
            }
            let texture = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("Ridgeline lazy texture array page"),
                size: wgpu::Extent3d {
                    width: self.width,
                    height: self.height,
                    depth_or_array_layers: self.layers,
                },
                mip_level_count: self.mips,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: self.format,
                usage: self.usage,
                view_formats: &[],
            });
            let view = texture.create_view(&wgpu::TextureViewDescriptor {
                dimension: Some(wgpu::TextureViewDimension::D2Array),
                ..Default::default()
            });
            let mut entries = vec![wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::TextureView(&view),
            }];
            if let Some(sampler) = sampler {
                entries.push(wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(sampler),
                });
            }
            let bind = Arc::new(device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Ridgeline array page binding"),
                layout,
                entries: &entries,
            }));
            let page = Arc::new(Page {
                texture,
                bind,
                slots: Mutex::new(Slots {
                    free: (0..self.layers).rev().collect(),
                    generations: vec![0; self.layers as usize],
                    live: 0,
                }),
            });
            self.pages.push(page.clone());
            self.creations += 1;
            page
        };
        let (layer, generation) = {
            let mut slots = page.slots.lock().unwrap();
            let layer = slots.free.pop().unwrap();
            slots.generations[layer as usize] += 1;
            slots.live += 1;
            (layer, slots.generations[layer as usize])
        };
        Ok(LayerLease {
            page,
            layer,
            generation,
        })
    }
    pub fn trim_empty(&mut self) {
        self.pages.retain(|p| p.slots.lock().unwrap().live > 0);
    }
    pub fn bytes(&self) -> u64 {
        let pixels: u64 = (0..self.mips)
            .map(|m| (self.width >> m).max(1) as u64 * (self.height >> m).max(1) as u64)
            .sum();
        let per = 4;
        pixels * self.layers as u64 * per * self.pages.len() as u64
    }
    pub fn page_count(&self) -> usize {
        self.pages.len()
    }
}
