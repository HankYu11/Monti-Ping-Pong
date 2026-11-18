import UIKit

/**
 * SpinBall.kt `SpinMath`, line for line. The frame is the screen's: x right, y DOWN, z toward you
 * (left handed). The sign traps were caught by tracking the painted dots (SelfTest checks the same
 * things SpinBallTest does), so nothing here is re-derived.
 */
enum SpinMath {
    static let WHITE: [Double] = [244, 244, 241]
    static let INK: [Double] = [20, 20, 20]
    static let DOT_RGB: [[Double]] = [[208, 32, 42], [31, 71, 196], [30, 154, 60]]
    static let DOT_DIR: [[Double]] = [0.0, 120.0, 240.0].map { deg in
        let th = 61.4 * .pi / 180, ph = deg * .pi / 180
        return [cos(th), sin(th) * cos(ph), sin(th) * sin(ph)]
    }
    static let DOTCOS = cos(17.0 * .pi / 180)
    static let LIGHT = unit([-0.22, -0.3, 0.93])
    static let AMBIENT = 0.46
    static let REST = rot(unit([0, -0.978, -0.210]), 1.876)

    static func unit(_ v: [Double]) -> [Double] {
        var m = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).squareRoot(); if m == 0 { m = 1 }
        return [v[0] / m, v[1] / m, v[2] / m]
    }
    /** Rodrigues about [a] by [t], row major */
    static func rot(_ a: [Double], _ t: Double) -> [Double] {
        let c = cos(t), s = sin(t), k = 1 - c, x = a[0], y = a[1], z = a[2]
        return [c + x * x * k, x * y * k - z * s, x * z * k + y * s,
                y * x * k + z * s, c + y * y * k, y * z * k - x * s,
                z * x * k - y * s, z * y * k + x * s, c + z * z * k]
    }
    static func matmul(_ a: [Double], _ b: [Double]) -> [Double] {
        var o = [Double](repeating: 0, count: 9)
        for r in 0..<3 { for c in 0..<3 { o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] } }
        return o
    }
    static func apply(_ m: [Double], _ p: [Double]) -> [Double] {
        [m[0] * p[0] + m[1] * p[1] + m[2] * p[2], m[3] * p[0] + m[4] * p[1] + m[5] * p[2], m[6] * p[0] + m[7] * p[1] + m[8] * p[2]]
    }
    static func axisOf(_ s: Spin) -> [Double]? {
        let v = [-s.lift, -s.curve, s.gyro]
        let m = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).squareRoot()
        return m < 1e-6 ? nil : [v[0] / m, v[1] / m, v[2] / m]
    }
    static func rateOf(_ s: Spin) -> Double { (s.lift * s.lift + s.curve * s.curve + s.gyro * s.gyro).squareRoot() }
    static func turn(_ a: [Double], _ t: Double, _ p: [Double]) -> [Double] { apply(rot(a, t), p) }
    /** the way the face you can see is travelling, on screen (y down) */
    static func faceTravel(_ a: [Double]) -> [Double] { [a[1], -a[0]] }
    static func chevronsClockwise(_ a: [Double]) -> Bool { a[2] > 0 }

    /** the ball as n×n RGBA bytes (premultiplied, opaque inside), Mᵀ·p as SpinMath.sphere */
    static func sphere(_ a: [Double], _ t: Double, _ n: Int, into: inout [UInt8]) {
        let m = matmul(rot(a, t), REST)
        let half = Double(n) / 2, rad = Double(n) * 0.5
        var i = 0
        for py in 0..<n {
            let dy = (Double(py) + 0.5 - half) / rad
            for px in 0..<n {
                let dx = (Double(px) + 0.5 - half) / rad
                let rr = dx * dx + dy * dy
                if rr >= 1 { into[i] = 0; into[i + 1] = 0; into[i + 2] = 0; into[i + 3] = 0; i += 4; continue }
                let nz = (1 - rr).squareRoot()
                let bx = m[0] * dx + m[3] * dy + m[6] * nz
                let by = m[1] * dx + m[4] * dy + m[7] * nz
                let bz = m[2] * dx + m[5] * dy + m[8] * nz
                var col = bx < 0 ? INK : WHITE
                if bx >= 0 {
                    for k in 0..<3 { let d = DOT_DIR[k]; if bx * d[0] + by * d[1] + bz * d[2] > DOTCOS { col = DOT_RGB[k]; break } }
                }
                let lit = dx * LIGHT[0] + dy * LIGHT[1] + nz * LIGHT[2]
                let sh = AMBIENT + (1 - AMBIENT) * (lit > 0 ? lit : 0)
                into[i] = UInt8(Int(col[0] * sh)); into[i + 1] = UInt8(Int(col[1] * sh)); into[i + 2] = UInt8(Int(col[2] * sh)); into[i + 3] = 255
                i += 4
            }
        }
    }
}

/**
 * The spin, drawn (SpinBall.kt): a marked ball turning the way the serve turned, 55 times slower
 * than life, about 30 frames a second; the axle (dashed), the gyro ring (dashed, chevrons when the
 * direction is known) and the one solid arrow, the way the visible face travels.
 */
final class SpinBallView: UIView {
    private var axis: [Double]?
    private var rps = 0.0
    private var gyroKnown = true
    private(set) var angle = 0.0
    private var last: CFTimeInterval = 0
    private var still = false
    private var link: CADisplayLink?
    private var texPx: [UInt8] = []
    private var texN = 0
    private let slow = 55.0

    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; backgroundColor = .clear; contentMode = .redraw }
    required init?(coder: NSCoder) { fatalError() }

    func show(_ spin: Spin) {
        guard let a = SpinMath.axisOf(spin) else { clear(); return }
        axis = a; rps = SpinMath.rateOf(spin); gyroKnown = spin.dirKnown; last = 0
        isHidden = false
        startLink(); setNeedsDisplay()
    }
    func clear() { axis = nil; rps = 0; stopLink(); setNeedsDisplay() }
    /** held still while a serve is measured; no jump when it starts again */
    func hold(_ on: Bool) {
        if still == on { return }
        still = on; last = 0
        if on { stopLink() } else { startLink(); setNeedsDisplay() }
    }
    /** a fixed pose, in turns, for stills */
    func poseTurns(_ turns: Double) { angle = turns * 2 * .pi; still = true; stopLink(); setNeedsDisplay() }

    private func startLink() {
        guard link == nil, axis != nil, !still else { return }
        let l = CADisplayLink(target: self, selector: #selector(tick))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 24, maximum: 30, preferred: 30)
        l.add(to: .main, forMode: .common)
        link = l
    }
    private func stopLink() { link?.invalidate(); link = nil }
    override func didMoveToWindow() { if window == nil { stopLink() } else { startLink() } }

    @objc private func tick(_ l: CADisplayLink) {
        if !still && axis != nil {
            if last != 0 { angle += 2 * .pi * (rps / slow) * min(l.timestamp - last, 0.05) }
            last = l.timestamp
        }
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let a = axis, let c = UIGraphicsGetCurrentContext() else { return }
        let w = bounds.width
        if w < 8 { return }
        let cxy = w / 2, r = w * 0.345
        ball(c, a, cxy, r)
        marks(c, a, w, r, cxy)
    }

    /** drawn small in device pixels and scaled up with bilinear filtering */
    private func ball(_ c: CGContext, _ a: [Double], _ cxy: CGFloat, _ r: CGFloat) {
        let scale = window?.screen.scale ?? UIScreen.main.scale
        let n = (min(max(Int(2 * r * scale), 96), 220) / 2) * 2
        if texN != n { texN = n; texPx = [UInt8](repeating: 0, count: n * n * 4) }
        SpinMath.sphere(a, angle, n, into: &texPx)
        let img: CGImage? = texPx.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            return ctx.makeImage()
        }
        guard let im = img else { return }
        c.saveGState()
        c.interpolationQuality = .medium
        // CGContext draws images y-up: flip so the texture's first row is the top
        c.translateBy(x: cxy - r, y: cxy + r); c.scaleBy(x: 1, y: -1)
        c.draw(im, in: CGRect(x: 0, y: 0, width: 2 * r, height: 2 * r))
        c.restoreGState()
    }

    private func marks(_ c: CGContext, _ a: [Double], _ w: CGFloat, _ r: CGFloat, _ cxy: CGFloat) {
        c.saveGState()
        c.translateBy(x: cxy, y: cxy)
        c.setLineCap(.round); c.setLineJoin(.round)
        let ax = -a[0], ay = -a[1]
        if hypot(ax, ay) > 0.06 {
            c.setStrokeColor(Hud.cyan(110).cgColor); c.setLineWidth(w * 0.013)
            c.setLineDash(phase: 0, lengths: [w * 0.028, w * 0.022])
            let l = Double(r * 1.42)
            c.move(to: CGPoint(x: -ax * l, y: -ay * l)); c.addLine(to: CGPoint(x: ax * l, y: ay * l)); c.strokePath()
            c.setLineDash(phase: 0, lengths: [])
        }
        if abs(a[2]) > 0.1 {
            let ring = Double(r * 1.2)
            c.setStrokeColor(Hud.cyan(118).cgColor); c.setLineWidth(w * 0.014)
            c.setLineDash(phase: 0, lengths: [w * 0.032, w * 0.028])
            // Android's drawCircle starts at 3 o'clock and runs clockwise on screen
            c.addArc(center: .zero, radius: CGFloat(ring), startAngle: 0, endAngle: 2 * .pi, clockwise: false); c.strokePath()
            c.setLineDash(phase: 0, lengths: [])
            if gyroKnown {
                let tw: Double = SpinMath.chevronsClockwise(a) ? 1 : -1
                c.setStrokeColor(Hud.cyan(205).cgColor); c.setLineWidth(w * 0.017)
                let k = Double(w * 0.046)
                for j in 0..<3 {
                    let th = -Double.pi / 2 + Double(j) * 2 * .pi / 3
                    let qx = ring * cos(th), qy = ring * sin(th)
                    let at = atan2(tw * cos(th), -tw * sin(th))
                    c.move(to: CGPoint(x: qx + k * cos(at + .pi - 0.62), y: qy + k * sin(at + .pi - 0.62)))
                    c.addLine(to: CGPoint(x: qx, y: qy))
                    c.addLine(to: CGPoint(x: qx + k * cos(at + .pi + 0.62), y: qy + k * sin(at + .pi + 0.62)))
                    c.strokePath()
                }
            }
        }
        let f = SpinMath.faceTravel(a)
        let mm = hypot(f[0], f[1])
        if mm > 0.1 {
            let ux = f[0] / mm, uy = f[1] / mm
            let l = Double(r) * 0.66 * min(mm + 0.25, 1)
            let hw = Double(w) * 0.062
            c.setStrokeColor(Hud.CYAN.cgColor); c.setLineWidth(w * 0.022)
            c.move(to: CGPoint(x: -ux * l, y: -uy * l)); c.addLine(to: CGPoint(x: ux * (l - hw * 0.7), y: uy * (l - hw * 0.7))); c.strokePath()
            let ah = atan2(uy, ux)
            c.setFillColor(Hud.CYAN.cgColor)
            c.move(to: CGPoint(x: ux * (l + hw * 0.45), y: uy * (l + hw * 0.45)))
            c.addLine(to: CGPoint(x: ux * l - hw * cos(ah - 0.5), y: uy * l - hw * sin(ah - 0.5)))
            c.addLine(to: CGPoint(x: ux * l - hw * cos(ah + 0.5), y: uy * l - hw * sin(ah + 0.5)))
            c.closePath(); c.fillPath()
        }
        c.restoreGState()
    }
}
