# LIFTools
This is a utility for Android for performing various actions relating to Leia Image Format (LIF)
files

**What you can do with it so far**

1. Ability to open a LIF image export it as a SBS stereo pair
   - currently, the Lume Pad camera app does not offer a Pro-SBS option, & other control options that the RED Hydrogen One camera app has
2. Export LIF images as a "crossview" SBS stereo pair with the L and R images swapped
3. Export 2V or 4V images as a "**4V ST**" quad
   - this is something i occasionally do for posting to LeiaPix when i want to bypass the AI
       depth estimation. When you bake a quad with L,L,R,R the normal stereo depth is retained and
       viewable in LeiaPix. (Hopefully in the future, they add an option to switch between 4V/ST
       viewing modes like they do in LeiaPlayer)
4. View/Export Disparity Maps

**TODO**
- wigglegram export (2V,4V) (gif & mp4)
- export 2D with bokeh baked in
- expose option to select png or jpg, and desired quality (compression) level
- link to open exported files
- recently opened files list
- ability to select export directory (defaults to /Pictures/LIFToolsExports)
- progress bar when exporting multiple files
  - parallize exporting multiple?
- useful error message when there is trouble opening a file
- app icon
- ability to specify filename
- ability to pinch zoom / pan preview
- ability to extract 2v or 4v from video by selecting left / right frame manually, and adjusting alignment for proper disparity
- ability to crop? (maybe beyond scope, not trying to re-invent LeiaPlayer)

**I have an idea!**

Let me know if there's something I can add to the app for you. Open an issue on this repo, send a tweet to me [@jakedowns](https://twitter.com/jakedowns), or hit me up on the [LeiaLoft  Forum](https://forums.leialoft.com/u/jakedowns/)

---

## Original Repo
This repo is originally a fork of the `sample-render-lif` app in the [LeiaInc/MediaSdkSamples repo](https://github.com/LeiaInc/MediaSdkSamples/tree/master/sample-render-lif)

## Documentation
You can find the documentation for using the Leia Android Media SDK here
[Leia Android Media SDK Documentation](https://docs.leialoft.com/leia-android-media-sdk/)