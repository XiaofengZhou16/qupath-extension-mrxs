# Runtime dependencies and distribution

The extension source is distributed under the repository's [GPLv3 license](LICENSE).
The plugin JAR includes a copy at `licenses/LICENSE`. Preserve the corresponding
source and license when preparing a release; the local checkpoint ZIP is a
development artifact, not a reviewed public source distribution.

## What the plugin contains

The inspected `0.5.0-SNAPSHOT` plugin contains project classes, QuPath service
registration files, its manifest and the project license. It does not contain
QuPath, JavaFX, an InstanSeg runtime, DJL/PyTorch, model weights or slide data.
Recheck the actual archive whenever dependencies or packaging change. This
inventory does not establish the provenance of every source-code contribution.

## Dependencies supplied separately

| Component | Purpose | Distribution boundary |
| --- | --- | --- |
| QuPath 0.7.0 | Viewer, image and object APIs, GUI and shared runtime libraries | Install QuPath separately; it is not bundled in the plugin. |
| InstanSeg extension and DJL | Optional AI inference | Install the official extensions separately. MRXS reading and classical analysis do not require AI. |
| Trusted fluorescence model | Optional nuclear/cell segmentation | Obtain separately from its publisher and retain its model metadata and applicable terms. No weights are included in this repository's plugin JAR. |
| PyTorch native runtime | Execution through DJL | Managed separately by DJL; first use may require a download. An offline installation needs its runtime prepared beforehand. |
| Gradle wrapper and build plugins | Building from source | These have their own upstream terms. Build dependencies are separate from the released plugin's runtime contents. |

The locally exercised AI combination is QuPath 0.7.0, InstanSeg 0.1.7 and
fluorescence model 0.1.1 on Apple Silicon. This is a tested combination, not a
claim that all versions or platforms are supported. See the README for setup.

Before distributing a combined application, runtime cache or model bundle,
review the specific versions' upstream licenses and notices. The project license
does not grant redistribution rights to separately obtained software, models or
research images. Local test installations and caches are not release attachments.

## Release verification

- Build from the exact source revision associated with the release.
- Verify the JAR manifest version, embedded license and complete archive contents.
- Include matching source and checksums with a clear installation guide.
- Keep private images, model caches, credentials and local acceptance outputs out
  of public source archives and release attachments.
- Record which tests actually ran; skipped private fixtures are not passed tests.
