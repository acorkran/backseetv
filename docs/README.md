# Recording the demo GIF

A short guide to producing the `docs/demo.gif` referenced in the top-level
[`README.md`](../README.md) Demo section.

## Capture

For a single tablet screen, record straight off the device with `adb`:

```bash
adb devices                                  # find the tablet's <serial>
adb -s <serial> shell screenrecord --time-limit 20 /sdcard/demo.mp4
adb -s <serial> pull /sdcard/demo.mp4        # copy it to your machine
```

`--time-limit 20` caps the clip at 20 seconds, which is plenty for the demo.

For the full **"one phone driving two synced tablets"** story, a screen
recording of a single device can't show the whole setup. Shoot an external
phone video of both tablets (and the phone remote) in frame — the physical
lock-step is the point, and it reads far better than an on-device capture.

## Convert to GIF

Turn the `.mp4` into a web-friendly, looping GIF with `ffmpeg`:

```bash
ffmpeg -i demo.mp4 -vf "fps=12,scale=640:-1:flags=lanczos" docs/demo.gif
```

- `fps=12` keeps the file small while staying smooth enough.
- `scale=640:-1` fixes the width at 640px and preserves the aspect ratio.
- `flags=lanczos` gives a cleaner downscale.

## Drop it in

Save the result as `docs/demo.gif`, then in the top-level `README.md` Demo
section swap the placeholder HTML comment for the image line:

```markdown
![Anancy demo](docs/demo.gif)
```
