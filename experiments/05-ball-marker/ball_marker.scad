// Ball Marker: 3D-printed tools to mark table tennis balls for the spin meter.
//
// The marking the meter reads (experiments/02-spin-meter/web/README.md, "Colour dots"):
//   - one half of the ball black;
//   - on the white half, three solid dots, red, blue and green, each about 8 mm across,
//     1 cm from the seam (the black/white line), at 12, 4 and 8 o'clock (120 degrees apart).
//
// Two tools (all sizes in mm):
//   cup   - holds the ball in to exactly its middle: paint or marker the half that shows. Its flat
//           top is level with the ball's middle, so a marker tip resting on it draws the line.
//   tray  - cups in a grid, for a batch (spray paint).
//   cap   - the dot stencil: an open ring that sits on the cup over the white half (black half down
//           in the cup) and hugs the ball, so it centres itself; three holes where the dots go,
//           labelled R, G and B. Dab paint markers through them.
// Everything prints without supports, the cup and tray open side up, the cap flat side down.
//
// Use: openscad -D 'part="cup"' -o cup.stl ball_marker.scad   (part: cup, tray, cap, layout)

part = "layout";

// ---- the ball and the marking
ball_d    = 40.2;   // 40+ balls measure 40.0-40.6
dot_d     = 8;      // each dot's diameter
dot_from_seam = 10; // along the surface, from the black/white line to a dot's centre
dot_angles = [0, 120, 240];          // around the ball, looking at the white half
dot_labels = ["R", "G", "B"];        // the colour for each hole (red, green, blue)

// ---- fit and print
clear     = 0.3;    // gap between the ball and a tool, each side
wall      = 2.4;    // the cup's wall around the ball
base      = 2;      // the cup's floor
shell     = 1.6;    // the stencil ring's thickness
ring_top  = 46;     // the stencil ring stops this far up the ball (degrees): steeper won't print
flange_w  = 6;      // the stencil's flat brim, resting on the cup
flange_t  = 2;
rows = 3; cols = 4; // the tray
gap  = 3;           // between cups in the tray
$fn  = 120;

br  = ball_d / 2;
cr  = br + clear;                 // the cup's hollow
cup_r = cr + wall;
cup_h = base + br;                // its top is level with the ball's middle
elev  = dot_from_seam / br * 180 / PI;   // the dots' angle above the line (about 28.5 degrees)

module cup() {
    difference() {
        cylinder(r = cup_r, h = cup_h);
        // the hollow: a sphere sitting on the floor, cut off at the ball's middle
        translate([0, 0, base + cr]) sphere(r = cr);
    }
}

module tray() {
    pitch = 2 * cup_r + gap;
    for (i = [0 : rows - 1], j = [0 : cols - 1])
        translate([j * pitch, i * pitch, 0]) cup();
    // a plate tying the cups together
    translate([-cup_r, -cup_r, 0]) cube([(cols - 1) * pitch + 2 * cup_r, (rows - 1) * pitch + 2 * cup_r, base]);
}

module cap() {
    ir = br + clear;              // hugs the ball
    or = ir + shell;
    difference() {
        union() {
            // the ring: a sphere's band from the line up to ring_top
            intersection() {
                difference() { sphere(r = or); sphere(r = ir); }
                cylinder(r = or + 1, h = or * sin(ring_top));
            }
            // the brim it stands on, level with the ball's middle
            difference() { cylinder(r = ir + flange_w + shell, h = flange_t); cylinder(r = ir, h = flange_t * 3, center = true); }
        }
        // the holes: along the ball's radius, through the ring
        for (k = [0 : len(dot_angles) - 1])
            rotate([0, 0, dot_angles[k]]) rotate([0, 90 - elev, 0])
                cylinder(d = dot_d, h = or + 5);
        // the labels, engraved in the brim under each hole
        for (k = [0 : len(dot_angles) - 1])
            rotate([0, 0, dot_angles[k]]) translate([ir + shell + flange_w / 2 + 0.5, 0, flange_t - 0.6])
                rotate([0, 0, 90]) linear_extrude(1) text(dot_labels[k], size = 4, halign = "center", valign = "center");
    }
}

if (part == "cup") cup();
else if (part == "tray") tray();
else if (part == "cap") cap();
else {                          // everything, side by side
    cup();
    translate([2 * cup_r + 20, 0, 0]) cap();
    translate([0, 2 * cup_r + 20, 0]) tray();
}
