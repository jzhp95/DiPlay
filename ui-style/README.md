# Flyme Auto presentation sources

`DiPlayPalette`, `FlymeAutoStyle` and `CarDialogStyle` define DiPlay's presentation system. The `common` library compiles this directory through `androidComponents.onVariants` for the main `mobile` application.

Change semantic colors and sizes here rather than introducing local copies. App-owned chrome uses neutral day/night surfaces, blue accent, shared rounded controls and large touch targets. Video and crop canvases retain their purpose-specific content colors. The main app owns its appearance lifecycle.
