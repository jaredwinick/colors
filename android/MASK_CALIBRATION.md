# Adjusting the fixed sky mask

Use this procedure whenever the phone or mount moves, or when a fixed object
starts appearing in extracted palettes. Weather, clouds, sunlight, and moving
shadows are not reasons to adjust the mask.

The source photograph is always kept complete. The mask only selects pixels for
palette extraction.

## How the coordinates work

The mask is stored in `sky-mask.json`. Every `[x, y]` coordinate is normalized:

- `[0.0, 0.0]` is the upper-left corner.
- `[1.0, 0.0]` is the upper-right corner.
- `[0.5, 0.5]` is the center.
- Increasing `x` moves right.
- Increasing `y` moves down.

The first two points in `include_polygon` are the upper corners. Leave those
alone for a normal skyline adjustment. The remaining points trace the lower
sampling boundary from the right side of the image back to the left side.

To move one part of the boundary **up**, reduce that point's `y` value. To move
it **down**, increase the `y` value. For example:

```json
[0.4, 0.9]
```

Changing it to `[0.4, 0.88]` moves the boundary upward by 2% of the image
height. On a 1920-pixel-tall capture, that is about 38 pixels. Change values in
small steps such as `0.005` or `0.01`.

## Recalibration procedure on the phone

1. Capture a new normalized image with the phone in its permanent position:

   ```sh
   ~/colors/capture_image.sh
   ```

   Copy the printed `image_path`; it identifies the JPEG to use below.

2. Back up the working mask and install a text editor if needed:

   ```sh
   cp ~/colors/sky-mask.json ~/colors/sky-mask.json.backup
   pkg install nano -y
   ```

3. Before editing, generate a labeled calibration preview:

   ```sh
   python ~/colors/sky_mask.py preview \
     --config ~/colors/sky-mask.json \
     --image /PASTE/THE/IMAGE_PATH/HERE.jpg \
     --output ~/storage/shared/colors-mask-calibration.jpg \
     --calibration-grid
   ```

4. Open `colors-mask-calibration.jpg` in Gallery and zoom into the skyline.
   Yellow labels are the array positions in `include_polygon`. Find the point
   nearest the object that crossed into the cyan sampled area.

5. Open the mask with `nano ~/colors/sky-mask.json` and change that point's `y`
   value. Keep the points in their existing order and retain the commas. Save
   with **Ctrl+O**, press **Enter**, and exit with **Ctrl+X**.

6. Validate the JSON and generate the preview again:

   ```sh
   python ~/colors/sky_mask.py validate \
     --config ~/colors/sky-mask.json --width 1440 --height 1920
   python ~/colors/sky_mask.py preview \
     --config ~/colors/sky-mask.json \
     --image /PASTE/THE/IMAGE_PATH/HERE.jpg \
     --output ~/storage/shared/colors-mask-calibration.jpg \
     --calibration-grid
   ```

7. Repeat small adjustments until every roof, tree, window edge, and other
   fixed object is red. Leave a narrow strip of sky red above the object so
   wind, vibration, and minor mount movement do not put it back inside the mask.

8. Check the finished mask against at least one more capture. A windy image is
   useful when branches form part of the skyline. Also verify a second image
   size:

   ```sh
   python ~/colors/sky_mask.py validate \
     --config ~/colors/sky-mask.json --width 720 --height 960
   ```

9. Copy the updated `sky-mask.json` back to this repository and commit it so the
   deployed phone configuration and source-controlled calibration stay in sync.

## Adding or removing boundary points

Adjusting only `y` is usually enough after a small movement. If an object now
falls between two points, add another `[x, y]` pair between them. Keep the
skyline points ordered from larger `x` values to smaller `x` values. Remove a
point only when the two neighboring line segments still leave the whole fixed
object in red.

`exclude_polygons` and `exclude_rectangles` can remove isolated fixed objects
inside the main sky region, such as a window latch or a reflection that never
moves. Prefer adjusting the main `include_polygon` for the roofline and trees.

## Recovering from a bad edit

If validation reports invalid JSON or the preview is clearly wrong, restore the
backup:

```sh
cp ~/colors/sky-mask.json.backup ~/colors/sky-mask.json
```

Do not replace the backup until the new mask has passed both preview checks.
