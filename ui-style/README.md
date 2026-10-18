# Flyme Auto presentation sources

`DiPlayPalette`, `FlymeAutoStyle` and `CarDialogStyle` are the existing DiPlay presentation system. `common`, `home`, and `maphost` compile this directory through `androidComponents.onVariants`, so the standalone sample apps share styling without depending on CarPlay services or native libraries.

Change semantic colors and sizes here rather than introducing module-local copies. App-owned chrome uses neutral day/night surfaces, blue accent, shared rounded controls and large touch targets. Video, crop canvases, third-party widgets, and OEM cluster renderers retain their purpose-specific content colors. `FlymeSampleStyle` applies system-mode colors and safe insets only for the independent sample apps; the main app keeps its existing appearance lifecycle.
