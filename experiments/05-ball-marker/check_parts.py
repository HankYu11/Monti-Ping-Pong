"""Check the exported STLs against real balls (needs: pip install trimesh rtree scipy).

    python check_parts.py            # reads stl/cup.stl, stl/cap.stl, stl/tray.stl

Uses the defaults in ball_marker.scad (40.2 mm design ball, 0.3 mm clearance, base 2 mm).
For each ball size in BALLS: where its middle sits against the cup's rim, the smallest gap to the
cup and to the stencil, and where each stencil hole's paint lands on the ball (distance from the
black/white line along the surface, spacing around the ball, size).
"""
import sys
import numpy as np
import trimesh

BASE = 2.0            # the cup's floor (ball_marker.scad: base)
BALLS = [40.0, 40.2, 40.6]
STL = sys.argv[1] if len(sys.argv) > 1 else "stl"


def sphere_points(r, n=40000, centre=(0, 0, 0)):
    i = np.arange(n) + 0.5
    phi = np.arccos(1 - 2 * i / n)
    th = np.pi * (1 + 5 ** 0.5) * i
    d = np.c_[np.cos(th) * np.sin(phi), np.sin(th) * np.sin(phi), np.cos(phi)]
    return d, np.asarray(centre) + r * d


def gap(mesh, pts, inside_ok):
    """Smallest distance from points to the mesh; negative if a point is inside the solid."""
    q = trimesh.proximity.ProximityQuery(mesh)
    dist = q.signed_distance(pts)          # positive inside the solid
    return -dist.max()


def main():
    ok = True
    meshes = {p: trimesh.load(f"{STL}/{p}.stl") for p in ("cup", "cap", "tray")}
    for p, m in meshes.items():
        print(f"{p:5s} watertight={m.is_watertight} volume={m.volume / 1000:.1f} cm3 "
              f"size={np.round(m.extents, 1)} mm")
        ok &= m.is_watertight and m.is_volume

    cup, cap = meshes["cup"], meshes["cap"]
    rim = cup.bounds[1][2]
    cx, cy = cup.bounds.mean(axis=0)[:2]
    for d in BALLS:
        r = d / 2
        zc = BASE + r                                   # resting on the floor of the hollow
        _, pts = sphere_points(r, 20000, (cx, cy, zc))
        g_cup = gap(cup, pts[pts[:, 2] > BASE + 2], False)   # the sides (it rests on the floor)
        g_rim = gap(cup, pts[(pts[:, 2] > rim - 3) & (pts[:, 2] < rim)], False)  # where the line is drawn
        # the stencil: brim on the rim, black half down; cap's line (z=0) at the rim
        capm = cap.copy()
        ccx, ccy = capm.bounds.mean(axis=0)[:2]
        capm.apply_translation([-ccx, -ccy, 0])
        dirs, pts2 = sphere_points(r, 60000, (0, 0, zc - rim))
        g_cap = gap(capm, pts2, False)
        # paint through the holes: rays from the ball's surface straight out, white half only,
        # within the ring's height; a ray that misses the stencil means paint lands there
        band = (pts2[:, 2] > 0.3) & (dirs[:, 2] < np.sin(np.radians(44)))
        o, v = pts2[band], dirs[band]
        hit = capm.ray.intersects_any(o + 0.01 * v, v)
        open_pts = o[~hit]
        holes = []
        az = np.degrees(np.arctan2(open_pts[:, 1], open_pts[:, 0])) % 360
        for a0 in (0, 120, 240):
            sel = np.abs((az - a0 + 180) % 360 - 180) < 40
            p = open_pts[sel]
            c = p.mean(axis=0) - [0, 0, zc - rim]
            c = c / np.linalg.norm(c)
            # the line is where the rim meets the ball: arc from there to the hole's centre
            rim_lat = np.arcsin(np.clip((rim - zc) / r, -1, 1))
            arc = r * (np.arcsin(c[2]) - rim_lat)
            area = sel.sum() / 60000 * 4 * np.pi * r * r   # seen straight on; the hole is wider inside
            holes.append((np.degrees(np.arctan2(c[1], c[0])) % 360, arc, 2 * np.sqrt(area / np.pi)))
        line_off = zc - rim
        print(f"ball {d:.1f}: middle {line_off:+.2f} mm from the rim; gap to cup {g_cup:.2f} (at the rim {g_rim:.2f}), "
              f"to stencil {g_cap:.2f} mm")
        for (a, arc, dd), lab in zip(holes, "RGB"):
            print(f"   {lab}: at {a:6.1f} deg, {arc:5.2f} mm from the line, ~{dd:.1f} mm across")
        ok &= g_cup > 0
        if g_cap <= 0:
            print(f"   note: the stencil touches this ball ({-g_cap:.2f} mm): it rides up off the brim")
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
