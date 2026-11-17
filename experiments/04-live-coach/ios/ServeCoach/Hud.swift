import UIKit
import CoreText

/** Hud.kt: the cockpit's colours and type. Cyan line art, Oxanium numerals, IBM Plex Mono labels. */
enum Hud {
    static let CYAN = rgb(95, 242, 255)
    static let AMBER = rgb(255, 211, 106)
    static let RED = rgb(255, 95, 109)
    static let WHITE = rgb(240, 248, 250)
    static let DIM = cyan(150)

    static func rgb(_ r: Int, _ g: Int, _ b: Int, _ a: Int = 255) -> UIColor {
        UIColor(red: CGFloat(r) / 255, green: CGFloat(g) / 255, blue: CGFloat(b) / 255, alpha: CGFloat(a) / 255)
    }
    /** the cyan at an Android alpha (0-255) */
    static func cyan(_ a: Int) -> UIColor { rgb(95, 242, 255, a) }

    private static var registered = false
    /** the Android app's own font files, copied into the bundle's `fonts` folder by the build */
    static func registerFonts() {
        if registered { return }
        registered = true
        guard let dir = Bundle.main.resourceURL?.appendingPathComponent("fonts"),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for f in files where f.pathExtension == "ttf" { CTFontManagerRegisterFontsForURL(f as CFURL, .process, nil) }
    }

    private static var cache: [String: UIFont] = [:]
    /** Oxanium at a weight on its `wght` axis (300 light .. 800 extra bold), at [size] pt */
    static func display(_ weight: Int, _ size: CGFloat) -> UIFont {
        let key = "ox\(weight)@\(size)"
        if let f = cache[key] { return f }
        registerFonts()
        let wght = 0x77676874   // 'wght'
        let desc = UIFontDescriptor(fontAttributes: [.name: "Oxanium",
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [wght: weight]])
        let f = UIFont(descriptor: desc, size: size)
        let out = f.familyName.lowercased().contains("oxanium") ? f : UIFont.systemFont(ofSize: size, weight: .light)
        cache[key] = out
        return out
    }
    /** IBM Plex Mono, regular or medium */
    static func mono(_ size: CGFloat, medium: Bool = false) -> UIFont {
        registerFonts()
        return UIFont(name: medium ? "IBMPlexMono-Medium" : "IBMPlexMono", size: size)
            ?? UIFont.monospacedSystemFont(ofSize: size, weight: medium ? .medium : .regular)
    }

    /** text with Android's letter spacing (em): kern = em × size, after every glyph as Android does */
    static func spaced(_ s: String, _ font: UIFont, _ color: UIColor, em: CGFloat) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color, .kern: em * font.pointSize])
    }
}

/**
 * A serve's spin split, in rev/s, as the engine reports it (SpinBall.kt `Spin`): lift > 0 backspin,
 * curve > 0 bends right, gyro > 0 points away down the table. dirKnown: the gyro's direction can be trusted.
 */
struct Spin: Equatable {
    let lift: Double, curve: Double, gyro: Double, dirKnown: Bool
    var afterBounce = false
}

/** Hud.kt `SpinLevel`: how strong a serve's spin is (2009 World Championships: men's serves averaged 2,760 RPM) */
enum SpinLevel {
    static let WORLD_AVG_RPM = 2760
    static let EDGES = [600, 1500, 2400]
    static func of(_ rpm: Int) -> Int { EDGES.firstIndex { rpm < $0 } ?? 3 }
    /** "Strong backspin", "Medium side-backspin", "Almost no spin": the kind without its qualifiers */
    static func outcome(_ rpm: Int, _ type: String) -> String {
        var kind = type.lowercased()
        if let r = kind.range(of: " (") { kind = String(kind[..<r.lowerBound]) }
        if let r = kind.range(of: " + ") { kind = String(kind[..<r.lowerBound]) }
        kind = kind.trimmingCharacters(in: .whitespaces)
        let k = of(rpm)
        return (k == 0 || kind.isEmpty || kind == "no spin") ? "Almost no spin" : "\(["", "Light", "Medium", "Strong"][k]) \(kind)"
    }
    /** RPM to the nearest 10, as the screen and the voice give it (Kotlin roundToInt: half up) */
    static func rpm(_ rps: Double) -> Int { Int((rps * 6).rounded(.toNearestOrAwayFromZero)) * 10 }
}

enum Words {
    /** HudScreen.qualifier: "curves right · off the bat · drill way unknown" */
    static func qualifier(_ type: String, _ spin: Spin?) -> String {
        var bits: [String] = []
        let t = type.lowercased()
        if let o = t.range(of: "("), let c = t.range(of: ")", range: o.upperBound..<t.endIndex) { bits.append(String(t[o.upperBound..<c.lowerBound])) }
        bits.append(spin?.afterBounce == true ? "after the bounce" : "off the bat")
        if let s = spin, !s.dirKnown, abs(s.gyro) >= 1 { bits.append("drill way unknown") }
        return bits.joined(separator: " · ")
    }

    /** MainActivity.whyNoServe: what a video with no serve showed instead, top 3 by count */
    static func whyNoServe(_ rejected: [String: Int]) -> String {
        let words = ["slow (a body?)": "slow movements (a body?)", "too big (a body?)": "large moving shapes (a body?)",
                     "wandering (a body?)": "people moving about (not a ball)", "picture moving": "moments with the picture moving",
                     "toss only": "tosses with no flight after", "upward (toss)": "balls going up (tosses)",
                     "straight drop": "balls dropping straight down", "short flight": "flights too short to measure"]
        let seen = rejected.compactMap { k, n in words[k].map { ($0, n) } }.sorted { $0.1 > $1.1 }.prefix(3)
        let head = seen.isEmpty ? "Nothing moved like a ball in flight." : "What it saw instead: " + seen.map { "\($0.0): \($0.1)" }.joined(separator: "; ") + "."
        return head + " The ball has to fly across the picture, over the table, from a phone that stays still."
    }
}

/** Tips.kt `TipPicker`: every [every] readable serves, the tip for the fault seen most since the last one */
final class TipPicker {
    static let TITLES: [String: (String, String)] = [
        "thick": ("You pushed the ball instead of brushing it", "Open the face and brush along under the ball."),
        "good": ("Strong backspin", "Keep this contact."),
        "slip": ("The ball slid on the rubber", "Let the ball drop lower and brush slightly into it."),
        "slow": ("The brush was too slow", "Accelerate through the contact: snap the wrist."),
    ]
    private let every: Int
    private var since = 0
    private var faults: [String: Int] = [:]
    init(every: Int = 3) { self.every = every }
    func reset() { since = 0; faults.removeAll() }
    func next(_ kind: String) -> (String, String)? {
        if !kind.isEmpty && kind != "good" && TipPicker.TITLES[kind] != nil { faults[kind, default: 0] += 1 }
        since += 1
        if since < every { return nil }
        since = 0
        let worst = faults.max { $0.value < $1.value }?.key
        faults.removeAll()
        return worst.flatMap { TipPicker.TITLES[$0] }
    }
}
