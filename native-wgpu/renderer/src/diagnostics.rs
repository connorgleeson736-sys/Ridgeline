//! GLES startup validation and explicit GPU readback; never in the frame loop.
use crate::Renderer;
impl Renderer {
    /// Per mip: [level, pixels differing from the exact checker mean, minimum alpha].
    pub fn mipmap_diagnostics(&self) -> Result<Vec<[u32; 3]>, String> {
        let image = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("diagnostic checker pyramid"),
            size: wgpu::Extent3d {
                width: 256,
                height: 256,
                depth_or_array_layers: 1,
            },
            mip_level_count: 9,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::COPY_DST
                | wgpu::TextureUsages::COPY_SRC
                | self.mipmaps.destination_usage(),
            view_formats: &[],
        });
        let pixels: Vec<u8> = (0..65536)
            .flat_map(|i| {
                let c = if (i % 256 + i / 256) % 2 == 0 { 0 } else { 255 };
                [c, c, c, 255]
            })
            .collect();
        self.queue.write_texture(
            image.as_image_copy(),
            &pixels,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(1024),
                rows_per_image: Some(256),
            },
            image.size(),
        );
        self.mipmaps
            .generate(&self.device, &self.queue, &image, &pixels);
        let mut layout = Vec::new();
        let mut total = 0;
        for level in 0..9 {
            let w = 256u32 >> level;
            let stride = (w * 4).div_ceil(256) * 256;
            layout.push((total, w, stride));
            total += u64::from(stride * w);
        }
        let buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("mip diagnostic readback"),
            size: total,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        for (level, &(offset, w, stride)) in layout.iter().enumerate() {
            encoder.copy_texture_to_buffer(
                wgpu::TexelCopyTextureInfo {
                    texture: &image,
                    mip_level: level as u32,
                    origin: wgpu::Origin3d::ZERO,
                    aspect: wgpu::TextureAspect::All,
                },
                wgpu::TexelCopyBufferInfo {
                    buffer: &buffer,
                    layout: wgpu::TexelCopyBufferLayout {
                        offset,
                        bytes_per_row: Some(stride),
                        rows_per_image: Some(w),
                    },
                },
                wgpu::Extent3d {
                    width: w,
                    height: w,
                    depth_or_array_layers: 1,
                },
            );
        }
        self.queue.submit([encoder.finish()]);
        let (tx, rx) = std::sync::mpsc::channel();
        buffer.slice(..).map_async(wgpu::MapMode::Read, move |v| {
            let _ = tx.send(v);
        });
        self.device
            .poll(wgpu::PollType::wait_indefinitely())
            .map_err(|e| format!("{e:?}"))?;
        rx.recv_timeout(std::time::Duration::from_secs(5))
            .map_err(|e| e.to_string())?
            .map_err(|e| e.to_string())?;
        let mut report = Vec::new();
        {
            let bytes = buffer
                .slice(..)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            for (level, &(offset, w, stride)) in layout.iter().enumerate() {
                let mut bad = 0;
                let mut alpha = 255;
                for y in 0..w {
                    for x in 0..w {
                        let at = offset as usize + (y * stride + x * 4) as usize;
                        let c = if level == 0 {
                            if (x + y) % 2 == 0 { 0 } else { 255 }
                        } else {
                            128
                        };
                        if bytes[at..at + 4] != [c, c, c, 255] {
                            bad += 1;
                        }
                        alpha = alpha.min(bytes[at + 3]);
                    }
                }
                report.push([level as u32, bad, alpha as u32]);
            }
        }
        buffer.unmap();
        Ok(report)
    }
}

impl Renderer {
    /// Startup-only probe of two distinct array layers and all nine mips. The
    /// second chain reuses scratch before any CPU fence, checking queue ordering.
    pub fn array_mipmap_diagnostics(&self) -> Result<Vec<[u32; 4]>, String> {
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("array mip startup probe"),
            size: wgpu::Extent3d {
                width: 256,
                height: 256,
                depth_or_array_layers: 2,
            },
            mip_level_count: 9,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::COPY_DST
                | wgpu::TextureUsages::COPY_SRC
                | self.mipmaps.destination_usage(),
            view_formats: &[],
        });
        for layer in 0..2 {
            let pixels: Vec<u8> = (0..65536)
                .flat_map(|i| {
                    if layer == 0 {
                        let c = if (i % 256 + i / 256) % 2 == 0 { 0 } else { 255 };
                        [c, c, c, 255]
                    } else {
                        [17, 33, 65, 129]
                    }
                })
                .collect();
            self.queue.write_texture(
                wgpu::TexelCopyTextureInfo {
                    texture: &texture,
                    mip_level: 0,
                    origin: wgpu::Origin3d {
                        x: 0,
                        y: 0,
                        z: layer,
                    },
                    aspect: wgpu::TextureAspect::All,
                },
                &pixels,
                wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(1024),
                    rows_per_image: Some(256),
                },
                wgpu::Extent3d {
                    width: 256,
                    height: 256,
                    depth_or_array_layers: 1,
                },
            );
            self.mipmaps
                .generate_layer(&self.device, &self.queue, &texture, &pixels, layer);
        }
        let mut layout = Vec::new();
        let mut total = 0;
        for layer in 0..2 {
            for level in 0..9 {
                let w = 256u32 >> level;
                let stride = (w * 4).div_ceil(256) * 256;
                layout.push((layer, level, total, w, stride));
                total += u64::from(stride * w);
            }
        }
        let buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("array mip probe readback"),
            size: total,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        for &(layer, level, offset, w, stride) in &layout {
            encoder.copy_texture_to_buffer(
                wgpu::TexelCopyTextureInfo {
                    texture: &texture,
                    mip_level: level,
                    origin: wgpu::Origin3d {
                        x: 0,
                        y: 0,
                        z: layer,
                    },
                    aspect: wgpu::TextureAspect::All,
                },
                wgpu::TexelCopyBufferInfo {
                    buffer: &buffer,
                    layout: wgpu::TexelCopyBufferLayout {
                        offset,
                        bytes_per_row: Some(stride),
                        rows_per_image: Some(w),
                    },
                },
                wgpu::Extent3d {
                    width: w,
                    height: w,
                    depth_or_array_layers: 1,
                },
            );
        }
        self.queue.submit([encoder.finish()]);
        let (tx, rx) = std::sync::mpsc::channel();
        buffer.slice(..).map_async(wgpu::MapMode::Read, move |v| {
            let _ = tx.send(v);
        });
        self.device
            .poll(wgpu::PollType::wait_indefinitely())
            .map_err(|e| format!("{e:?}"))?;
        rx.recv_timeout(std::time::Duration::from_secs(5))
            .map_err(|e| e.to_string())?
            .map_err(|e| e.to_string())?;
        let mut report = Vec::new();
        {
            let bytes = buffer
                .slice(..)
                .get_mapped_range()
                .map_err(|e| e.to_string())?;
            for &(layer, level, offset, w, stride) in &layout {
                let mut bad = 0;
                let mut alpha = 255;
                for y in 0..w {
                    for x in 0..w {
                        let at = offset as usize + (y * stride + x * 4) as usize;
                        let expected = if layer == 1 {
                            [17, 33, 65, 129]
                        } else {
                            let c = if level == 0 {
                                if (x + y) % 2 == 0 { 0 } else { 255 }
                            } else {
                                128
                            };
                            [c, c, c, 255]
                        };
                        if bytes[at..at + 4] != expected {
                            bad += 1;
                        }
                        alpha = alpha.min(bytes[at + 3]);
                    }
                }
                report.push([layer, level, bad, alpha as u32]);
            }
        }
        buffer.unmap();
        Ok(report)
    }
}
