import Foundation

/**
 * Checks run in the app itself (`-selftest`), printed as SCTEST lines: the Android SpinBallTest
 * ported (the ball's pixels have to agree with its marks), and the words the player sees and hears.
 */
enum SelfTest {
    private static var failed = 0, passed = 0
    private static func check(_ ok: Bool, _ what: @autoclosure () -> String) {
        if ok { passed += 1 } else { failed += 1; print("SCTEST FAIL: \(what())") }
    }

    static func run() -> Bool {
        failed = 0; passed = 0
        spinBall()
        words()
        fonts()
        print("SCTEST \(failed == 0 ? "OK" : "FAILED"): \(passed) passed, \(failed) failed")
        return failed == 0
    }

    // ---- SpinBallTest ----
    private static let n = 160
    private static func dotAngle(_ a: [Double], _ t: Double, _ pick: (Int, Int, Int) -> Bool) -> Double? {
        var px = [UInt8](repeating: 0, count: n * n * 4)
        SpinMath.sphere(a, t, n, into: &px)
        var sx = 0.0, sy = 0.0, k = 0
        for i in 0..<(n * n) where px[i * 4 + 3] != 0 {
            if pick(Int(px[i * 4]), Int(px[i * 4 + 1]), Int(px[i * 4 + 2])) { sx += Double(i % n); sy += Double(i / n); k += 1 }
        }
        if k < 20 { return nil }
        return atan2(sy / Double(k) - Double(n) / 2, sx / Double(k) - Double(n) / 2)
    }
    private static let red = { (r: Int, g: Int, b: Int) in r > 90 && r > g * 17 / 10 && r > b * 17 / 10 }
    private static let green = { (r: Int, g: Int, b: Int) in g > 80 && g > r * 15 / 10 && g > b * 15 / 10 }
    private static func wrap(_ x: Double) -> Double { var v = x; while v > .pi { v -= 2 * .pi }; while v < -.pi { v += 2 * .pi }; return v }

    private static func spinBall() {
        for s in [Spin(lift: 45, curve: 0, gyro: 0, dirKnown: true), Spin(lift: -45, curve: 0, gyro: 0, dirKnown: true),
                  Spin(lift: 0, curve: 40, gyro: 0, dirKnown: true), Spin(lift: 0, curve: -40, gyro: 0, dirKnown: true),
                  Spin(lift: 29.9, curve: -30.6, gyro: 15.2, dirKnown: true), Spin(lift: -21.3, curve: -12.2, gyro: 46.3, dirKnown: true)] {
            let a = SpinMath.axisOf(s)!
            let moved = SpinMath.turn(a, 0.004, [0, 0, 1])
            let want = SpinMath.faceTravel(a)
            let gl = hypot(moved[0], moved[1]), wl = hypot(want[0], want[1])
            if wl < 1e-9 { continue }
            let agree = (moved[0] * want[0] + moved[1] * want[1]) / (gl * wl)
            check(agree > 0.999, "\(s): the arrow and the face disagree (cos \(agree))")
        }
        do {
            let a = SpinMath.axisOf(Spin(lift: 45, curve: 0, gyro: 0, dirKnown: true))!
            let m = SpinMath.turn(a, 0.01, [0, 0, 1])
            check(m[1] > 0, "backspin must roll the near face down, got \(m[1])")
            check(abs(m[0]) < 1e-9, "backspin not sideways, got \(m[0])")
        }
        for g in [40.0, -40.0] {
            let a = SpinMath.axisOf(Spin(lift: 0, curve: 0, gyro: g, dirKnown: true))!
            guard let b = dotAngle(a, 0, red), let af = dotAngle(a, 0.30, red) else { check(false, "red dot lost, gyro \(g)"); continue }
            check((wrap(af - b) > 0) == SpinMath.chevronsClockwise(a), "gyro \(g): chevrons turn against the ball")
        }
        check(SpinMath.chevronsClockwise(SpinMath.axisOf(Spin(lift: 0, curve: 0, gyro: 46.3, dirKnown: true))!), "positive gyro must read clockwise")
        do {
            let a = SpinMath.axisOf(Spin(lift: 0, curve: 0, gyro: 40, dirKnown: true))!
            let r0 = dotAngle(a, 0, red)!, r1 = dotAngle(a, 0.30, red)!, g0 = dotAngle(a, 0, green)!, g1 = dotAngle(a, 0.30, green)!
            check(abs(wrap(r1 - r0) - wrap(g1 - g0)) <= 0.05, "red and green dots sweep different angles")
            check(abs(abs(wrap(r1 - r0)) - 0.30) <= 0.03, "the dots' sweep is not the turn: \(wrap(r1 - r0))")
        }
        check(hypot(SpinMath.axisOf(Spin(lift: 0, curve: 0, gyro: 46.3, dirKnown: true))![0], SpinMath.axisOf(Spin(lift: 0, curve: 0, gyro: 46.3, dirKnown: true))![1]) < 1e-9, "pure gyro has an axle")
        check(SpinMath.axisOf(Spin(lift: 0, curve: 0, gyro: 0, dirKnown: true)) == nil, "no spin must draw nothing")
    }

    // ---- the Android app's fonts, loaded (not the system's fallbacks) ----
    private static func fonts() {
        check(Hud.mono(12).fontName == "IBMPlexMono-Regular", "mono is \(Hud.mono(12).fontName)")
        check(Hud.mono(12, medium: true).fontName == "IBMPlexMono-Medium", "mono medium is \(Hud.mono(12, medium: true).fontName)")
        for w in [300, 600, 700] { check(Hud.display(w, 20).familyName == "Oxanium", "display \(w) is \(Hud.display(w, 20).familyName)") }
    }

    // ---- words ----
    private static func words() {
        check(SpinLevel.rpm(45.2) == 2710, "45.2 rev/s -> \(SpinLevel.rpm(45.2)), want 2710")
        check(SpinLevel.rpm(45.1) == 2710, "45.1 rev/s -> 2710")
        check(SpinLevel.outcome(2710, "backspin + gyro") == "Strong backspin", "outcome strong backspin: \(SpinLevel.outcome(2710, "backspin + gyro"))")
        check(SpinLevel.outcome(2000, "side-backspin (curves right) + gyro") == "Medium side-backspin", "outcome medium side-backspin")
        check(SpinLevel.outcome(1000, "Topspin") == "Light topspin", "outcome light topspin")
        check(SpinLevel.outcome(500, "backspin") == "Almost no spin", "outcome almost no spin")
        check(SpinLevel.outcome(3000, "no spin") == "Almost no spin", "outcome no spin")
        check(SpinLevel.of(600) == 1 && SpinLevel.of(599) == 0 && SpinLevel.of(2400) == 3, "bands")
        check(Words.qualifier("sidespin (curves right) + gyro", Spin(lift: 1, curve: 2, gyro: 3, dirKnown: false)) == "curves right · off the bat · drill way unknown", "qualifier 1")
        check(Words.qualifier("backspin", Spin(lift: 1, curve: 0, gyro: 0.5, dirKnown: false, afterBounce: true)) == "after the bounce", "qualifier 2")
        check(Words.qualifier("backspin", nil) == "off the bat", "qualifier 3")
        let t = TipPicker()
        check(t.next("thick") == nil && t.next("good") == nil, "tips: nothing before the 3rd")
        check(t.next("slip")?.0 != nil, "tips: a fault tip on the 3rd")
        check(t.next("good") == nil && t.next("good") == nil && t.next("good") == nil, "tips: none when all good")
        check(Words.whyNoServe([:]).hasPrefix("Nothing moved like a ball in flight."), "why none")
        check(Words.whyNoServe(["short flight": 3, "short": 9, "upward (toss)": 5]) ==
              "What it saw instead: balls going up (tosses): 5; flights too short to measure: 3. The ball has to fly across the picture, over the table, from a phone that stays still.", "why list")
    }
}
