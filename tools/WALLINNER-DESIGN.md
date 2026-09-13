# The inner corner (`wallinner`) — the geometry, worked out before any blocks are drawn

## Why mirroring does not do it

Structurize can mirror (`RotationMirror.MIR_NONE/MIR_R90/MIR_R180/MIR_R270`), so no new blueprint
is needed to mirror a piece. But the wall pieces are drawn with the **outside on the left of the
run**, and mirroring puts the outside on the right. That gives a ring laid **anticlockwise** — not
a concave corner. The two are different things and must not be confused.

## What a concave corner actually is

Walk a wall with the outside on your left and the town on your right: you are going **clockwise**
around the town, so every **right** turn is a convex corner and every **left** turn is a concave
one. A town shaped like an L needs exactly one left turn.

Worked example — town = [0,20]² minus a notch [10,20]×[0,10]. Walking clockwise, at (10,10) the
run arrives heading **south** and leaves heading **east**: a left turn. Around that point the
**notch** (the outside) is a 90° wedge and the wall wraps **270°** around it.

Compare the convex corner, where the outside is the 270° side and the town sits in the 90° elbow.
That is the whole difference, and it is why the convex piece has no blocks where the concave one
needs them: at a concave corner the **outer** faces are on the *inside* of the elbow.

## Local coordinates

Angle at the origin, arms along **+x** (outgoing, east) and **−z** (incoming, arriving from the
north heading south). The notch — the outside — is the **+x, −z** quadrant.

    outer face of the east arm      z = -2   for x >= 0
    outer face of the incoming arm  x = +2   for z <= 0

They meet at **(+2, −2)**, inside the notch: the wall wraps round it.

With `u = x` and `v = -z` (both >= 0 into the elbow):

    east arm      n = u        off = z  = -v      ins "south"  outs "north"
    incoming arm  n = v        off = -x = -u      ins "west"   outs "east"
    the elbow     off = -max(u, v)   and the ornament phase is ambiguous, so **quoin it**,
                  exactly as the convex corner quoins its diagonal

So the elbow grades from the walk at the angle out to the talus at (2, −2) by Chebyshev distance —
the mirror image, in role, of the convex corner's `off = min(x, z)`.

## The open question to settle first

The two arms as plain rectangles leave a diagonal notch in the **inner** faces between (0, +2) and
(−2, 0). The convex corner avoids this by filling one 6×6 box with a single `min`/`max` rule
rather than by unioning two rectangles. The inner corner needs the same treatment: **one box with
one rule**, not two arms. Settle the box and the rule, then draw.

## Offsets to derive (and then assert in `check_grow.py`)

`AFTER_CORNER` / `BEFORE_CORNER` for the inner corner will NOT be the convex piece's 2 and 9,
because the arms meet on the other side of the elbow. Derive them, then make `check_grow.py` grow
an L-shaped wall — right turns and one left — and assert it closes on the same slots the ring
arithmetic would give, the way the clockwise ring is already asserted.
