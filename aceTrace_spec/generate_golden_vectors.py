"""
Golden Vector Generator for AceTrace
Generates canonical test vectors for Cubic Bezier, Centripetal Catmull-Rom, and Time Mapping.
Both Kotlin and Swift test suites MUST pass against these golden files.
"""

import json
import math
import os

def cubic_bezier_point(p0, p1, p2, p3, u):
    one_minus_u = 1.0 - u
    c0 = one_minus_u ** 3
    c1 = 3.0 * (one_minus_u ** 2) * u
    c2 = 3.0 * one_minus_u * (u ** 2)
    c3 = u ** 3
    x = c0 * p0[0] + c1 * p1[0] + c2 * p2[0] + c3 * p3[0]
    y = c0 * p0[1] + c1 * p1[1] + c2 * p2[1] + c3 * p3[1]
    return {"x": round(x, 6), "y": round(y, 6)}

def centripetal_catmull_rom_point(p0, p1, p2, p3, t, alpha=0.5):
    def dist_sq(a, b):
        return (a[0] - b[0])**2 + (a[1] - b[1])**2
    
    t0 = 0.0
    t1 = t0 + math.pow(dist_sq(p0, p1), alpha * 0.5)
    if t1 == t0:
        t1 += 0.0001
    t2 = t1 + math.pow(dist_sq(p1, p2), alpha * 0.5)
    if t2 == t1:
        t2 += 0.0001
    t3 = t2 + math.pow(dist_sq(p2, p3), alpha * 0.5)
    if t3 == t2:
        t3 += 0.0001
    
    # Map u in [0, 1] to t in [t1, t2]
    actual_t = t1 + t * (t2 - t1)

    def interp(p_a, p_b, ta, tb, t_val):
        factor = (t_val - ta) / (tb - ta)
        return (p_a[0] + factor * (p_b[0] - p_a[0]), p_a[1] + factor * (p_b[1] - p_a[1]))

    a1 = interp(p0, p1, t0, t1, actual_t)
    a2 = interp(p1, p2, t1, t2, actual_t)
    a3 = interp(p2, p3, t2, t3, actual_t)

    b1 = interp(a1, a2, t0, t2, actual_t)
    b2 = interp(a2, a3, t1, t3, actual_t)

    c = interp(b1, b2, t1, t2, actual_t)
    return {"x": round(c[0], 6), "y": round(c[1], 6)}

def time_mapping(frame, f_start, f_apex, f_landing):
    if frame < f_start:
        return {"frame": frame, "progress": 0.0, "u": 0.0, "status": "before_start"}
    if frame > f_landing:
        return {"frame": frame, "progress": 1.0, "u": 1.0, "status": "landed"}
    
    if frame <= f_apex:
        s = (frame - f_start) / float(f_apex - f_start)
        s_eased = 1.0 - (1.0 - s) ** 2
        u = 0.5 * s_eased
        return {"frame": frame, "progress": round(u, 6), "u": round(u, 6), "status": "ascending"}
    else:
        s = (frame - f_apex) / float(f_landing - f_apex)
        s_eased = s ** 2
        u = 0.5 + 0.5 * s_eased
        return {"frame": frame, "progress": round(u, 6), "u": round(u, 6), "status": "descending"}

def main():
    golden_dir = os.path.join(os.path.dirname(__file__), "golden")
    os.makedirs(golden_dir, exist_ok=True)

    # 1. Bezier Golden Vector
    # Standard golf shot: start at (0.42, 0.78), apex at (0.55, 0.18), landing at (0.71, 0.62)
    # Default handles:
    # Segment 1: Start -> Apex
    p0 = (0.42, 0.78)
    p1 = (0.42 + (0.55 - 0.42)/3.0, 0.78 + (0.18 - 0.78)/3.0) # (0.463333, 0.58)
    p2 = (0.55 - (0.55 - 0.42)/3.0, 0.18)                     # (0.506667, 0.18)
    p3 = (0.55, 0.18)

    # Segment 2: Apex -> Landing
    p4 = (0.55, 0.18)
    p5 = (0.55 + (0.71 - 0.55)/3.0, 0.18)                     # (0.603333, 0.18)
    p6 = (0.71 - (0.71 - 0.55)/3.0, 0.62 - (0.62 - 0.18)/3.0)# (0.656667, 0.473333)
    p7 = (0.71, 0.62)

    bezier_samples = []
    for u_global in [0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0]:
        if u_global <= 0.5:
            u_seg = u_global / 0.5
            pt = cubic_bezier_point(p0, p1, p2, p3, u_seg)
        else:
            u_seg = (u_global - 0.5) / 0.5
            pt = cubic_bezier_point(p4, p5, p6, p7, u_seg)
        bezier_samples.append({"u": u_global, "point": pt})

    bezier_data = {
        "description": "Standard 3-keypoint golf trajectory with cubic bezier curves",
        "keypoints": [
            {"role": "start", "point": {"x": p0[0], "y": p0[1]}, "handleOut": {"x": round(p1[0], 6), "y": round(p1[1], 6)}},
            {"role": "apex", "point": {"x": p3[0], "y": p3[1]}, "handleIn": {"x": round(p2[0], 6), "y": round(p2[1], 6)}, "handleOut": {"x": round(p5[0], 6), "y": round(p5[1], 6)}},
            {"role": "landing", "point": {"x": p7[0], "y": p7[1]}, "handleIn": {"x": round(p6[0], 6), "y": round(p6[1], 6)}}
        ],
        "samples": bezier_samples
    }

    with open(os.path.join(golden_dir, "bezier_golden.json"), "w", encoding="utf-8") as f:
        json.dump(bezier_data, f, indent=2)

    # 2. Centripetal Catmull-Rom Golden Vector
    # 4 points representing a curved disc golf drive
    pts = [
        (0.2, 0.8),
        (0.35, 0.45),
        (0.65, 0.35),
        (0.85, 0.60)
    ]
    # Evaluate segment between P1 and P2
    catmull_samples = []
    for t_step in [0.0, 0.2, 0.4, 0.5, 0.6, 0.8, 1.0]:
        pt = centripetal_catmull_rom_point(pts[0], pts[1], pts[2], pts[3], t_step, alpha=0.5)
        catmull_samples.append({"t": t_step, "point": pt})

    catmull_data = {
        "description": "Centripetal Catmull-Rom spline (alpha=0.5) between points 1 and 2",
        "control_points": [{"x": p[0], "y": p[1]} for p in pts],
        "samples": catmull_samples
    }

    with open(os.path.join(golden_dir, "catmull_rom_golden.json"), "w", encoding="utf-8") as f:
        json.dump(catmull_data, f, indent=2)

    # 3. Time Mapping Golden Vector
    # f_start = 120, f_apex = 165, f_landing = 230
    test_frames = [100, 120, 135, 150, 165, 180, 200, 230, 250]
    time_samples = [time_mapping(f, 120, 165, 230) for f in test_frames]

    time_data = {
        "description": "Time to path progress mapping with gravitational ease-out ascent and ease-in descent",
        "f_start": 120,
        "f_apex": 165,
        "f_landing": 230,
        "samples": time_samples
    }

    with open(os.path.join(golden_dir, "time_mapping_golden.json"), "w", encoding="utf-8") as f:
        json.dump(time_data, f, indent=2)

    print("Golden vectors generated successfully in:", golden_dir)

if __name__ == "__main__":
    main()
