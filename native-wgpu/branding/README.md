# Razorback Maps logo

The kea on a sky gradient. `razorback-logo-source-512.png` is the approved drawing;
`razorback-logo-2048.png` is it redrawn crisply (flat colours, smooth outlines, the sky
as a fitted gradient) and `razorback-kea-transparent.png` the bird alone. Every logo is
made from these two:

    python3 -m venv /tmp/logo-env && /tmp/logo-env/bin/pip install numpy pillow scipy
    /tmp/logo-env/bin/python -I tools/sharpen.py razorback-logo-source-512.png /tmp/kea 12 4
    /tmp/logo-env/bin/python -I tools/icons.py /tmp/kea-full.png /tmp/kea-bird.png ../..

`icons.py` writes the website's favicons and icons (website/src/img), the app's map
button (assets/ui/ridgeline-kea.png) and launcher foreground (mipmap-*; its sky is
drawable/ic_launcher_background.xml, #82BCFD to #C2E4FE), the store icons here and
`ridgeline-icon-preview.png` (round, rounded and square, large and small).
