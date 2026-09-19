# The inner corner (`wallinner`) — settled 14 Sep 2026

The geometry below is no longer a sketch: `wallinner.py` is drawn from it and
`check_walls.inner_matches_section` holds every cell of the piece to `wallsegment.section` at all
five levels. The open question this note used to end on is answered, and the rule it proposed was
wrong. Both are recorded here rather than quietly replaced, because the wrong rule is the sort
that builds, looks like a wall, and joins nothing.

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

## The rule

Angle at the origin, arms along **+x** (outgoing, east) and **−z** (incoming, arriving from the
north heading south). The notch — the outside — is the **+x, −z** quadrant. With `u = x` and
`v = -z`, both counted positive out along their own arm:

    off = -min(u, v)        distance from the wall's centre line, outward negative
    n   =  max(u, v)        distance along this cell's own arm

That is the exact dual of the convex corner's `off = min(x, z)`, `n = max(x, z)`. The sign on
`off` is the whole difference, and it is the sign because "outward" has swapped sides.

### It is `min`, and the first draft of this note said `max`

| cell | what it is | wanted `off` | `-min` | `-max` |
|---|---|---|---|---|
| (x=2, z=0)  | the walk, two east of the angle | 0  | **0**  | −2 (out on the batter) |
| (x=3, z=−1) | one course off the east arm's centre line | −1 | **−1** | −3 (not a line of the section) |
| (x=2, z=−2) | where the two outermost lines meet | −2 | **−2** | −2 (they agree on the diagonal) |

Seventeen of the twenty cells of an arm come out on the wrong line under `-max`, and none under
`-min`. The two rules agree only on the diagonal, which is exactly the place a quick check would
look.

## One box, one rule — the question this note used to leave open

The worry was that two arms drawn as plain rectangles would leave a diagonal notch in the **inner**
faces between (0, +2) and (−2, 0). They would. The answer is that the piece is not two rectangles:
it is **one 6 × 6 box** with the rule above, exactly as the convex corner is one box with `min`.

Over `u, v ∈ [−2, 3]` the rule is total, and the only cell it throws away is the far corner of the
notch at `(u, v) = (3, 3)`, where `off` would be −3 and no such line exists — the mirror of the one
cell the convex piece drops inside its own elbow. The inner face comes out as an unbroken L through
`(u, v) = (−1, −1)`, and the walk as an unbroken L of seven cells through `(0, 0)`. Nothing had to
be said about turning, in either piece.

## What the angle gets, and what it does not

**Not a bartizan.** A turret is corbelled off a projecting angle, and this angle points into the
field. What a re-entrant angle has instead is the thing that makes bastions worth building: the two
faces **flank each other**, so anyone at the foot of one stands in front of the other.

- **the machicoulis** — at level 5 the section's own holes are left open right across the angle.
  The convex corner fills those two cells to make its turret a solid shaft; here there is no shaft,
  and the notch is the one place on a wall where what you drop covers both faces at once.
- **the colours** — from level 4, a banner on the outermost line of each face. That line
  (`off = TALUS`) exists only where both arms are at least two out, so the pair is `(u,v) = (3,2)`
  and `(2,3)`; the third such cell, `(2,2)`, is the quoined diagonal.
- **the light** — a lamp on the banquette rail one down each arm, at `n = 3`, a mirror pair.

## The anchor

`(-2, 1, 2)` — on the berm in the open elbow behind the angle, the far side from the notch. It
cannot go where the convex corner's does: `(u, v) = (2, 2)` is on the talus here, which carries the
batter and the buttress piers from level 4.

## Still to do

`AFTER_CORNER` / `BEFORE_CORNER` for a **left** turn are not the convex piece's 2 and 9, because
the anchor sits at the near end of the arms rather than the far one: the piece runs 0 .. +5 out of
its anchor along each arm, where the convex corner runs −4 .. +1. Derive both, then make
`check_grow.py` grow an L-shaped wall — right turns and one left — and assert it closes on the same
slots the ring arithmetic would give, the way the clockwise ring is already asserted.
