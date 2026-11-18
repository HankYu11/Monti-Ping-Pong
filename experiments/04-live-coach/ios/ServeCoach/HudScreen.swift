import UIKit

/** Hud.kt `SpinScale`: 0-4000 RPM, this serve's reading as a bar, the pro average (2760) marked */
final class SpinScaleView: UIView {
    var rpm: Int? { didSet { setNeedsDisplay() } }
    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; backgroundColor = .clear; contentMode = .redraw }
    required init?(coder: NSCoder) { fatalError() }
    private func x(_ r: CGFloat) -> CGFloat { bounds.width * min(r, 4000) / 4000 }
    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let top: CGFloat = 2, barH: CGFloat = 6, W = bounds.width
        c.setFillColor(Hud.cyan(38).cgColor); c.fill(CGRect(x: 0, y: top, width: W, height: barH))
        if let r = rpm { c.setFillColor(Hud.CYAN.cgColor); c.fill(CGRect(x: 0, y: top, width: x(CGFloat(r)), height: barH)) }
        let px = x(CGFloat(SpinLevel.WORLD_AVG_RPM))
        c.setFillColor(Hud.AMBER.cgColor); c.fill(CGRect(x: px - 0.75, y: top - 2.5, width: 1.5, height: barH + 5))
        let f = Hud.mono(11), base = top + barH + 13.5
        drawText("NO SPIN", f, Hud.DIM, x: 0, baseline: base)
        let lbl = "PRO AVG", w = (lbl as NSString).size(withAttributes: [.font: f]).width
        drawText(lbl, f, Hud.AMBER, x: min(max(px - w / 2, 0), W - w), baseline: base)
    }
}

/** draws [s] with its baseline at [baseline], as Android's Canvas.drawText does */
func drawText(_ s: String, _ f: UIFont, _ color: UIColor, x: CGFloat, baseline: CGFloat, em: CGFloat = 0) {
    Hud.spaced(s, f, color, em: em).draw(at: CGPoint(x: x, y: baseline - f.ascender))
}
func textWidth(_ s: String, _ f: UIFont, em: CGFloat = 0) -> CGFloat { Hud.spaced(s, f, .white, em: em).size().width }

/** Hud.kt `SpinSplit`: backspin, side and gyro on one scale, the serve's own rate */
final class SpinSplitView: UIView {
    var spin: Spin? { didSet { setNeedsDisplay() } }
    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; backgroundColor = .clear; contentMode = .redraw }
    required init?(coder: NSCoder) { fatalError() }
    override func draw(_ rect: CGRect) {
        guard let s = spin, let c = UIGraphicsGetCurrentContext() else { return }
        let W = bounds.width
        let total = max(1.0, (s.lift * s.lift + s.curve * s.curve + s.gyro * s.gyro).squareRoot())
        let rows: [(String, Double, Bool)] = [(s.lift >= 0 ? "BACKSPIN" : "TOPSPIN", abs(s.lift), true),
                                              (s.curve >= 0 ? "SIDE RIGHT" : "SIDE LEFT", abs(s.curve), true),
                                              ("GYRO", abs(s.gyro), s.dirKnown)]
        let x0: CGFloat = 104, x1 = W - 38
        let lab = Hud.mono(12.5), val = Hud.display(600, 15)
        drawText("REV/S", lab, Hud.DIM, x: W - textWidth("REV/S", lab, em: 0.1), baseline: 11, em: 0.1)
        for (i, r) in rows.enumerated() {
            let y = 31 + CGFloat(i) * 23
            drawText(r.0, lab, r.2 ? Hud.DIM : Hud.cyan(90), x: 0, baseline: y, em: 0.1)
            let mid = y - 4.2
            c.setFillColor(Hud.cyan(34).cgColor); c.fill(CGRect(x: x0, y: mid - 3, width: x1 - 6 - x0, height: 6))
            c.setFillColor((r.2 ? Hud.CYAN : Hud.cyan(110)).cgColor)
            c.fill(CGRect(x: x0, y: mid - 3, width: (x1 - 6 - x0) * CGFloat(r.1 / total), height: 6))
            let v = String(Int(r.1.rounded(.toNearestOrAwayFromZero)))
            drawText(v, val, r.2 ? Hud.WHITE : Hud.rgb(240, 248, 250, 150), x: W - textWidth(v, val), baseline: y)
        }
    }
}

/** Hud.kt `HexDrawable` as a button: a cut-corner outline with a faint fill, in one colour */
final class HexButton: UIControl {
    let label = UILabel()
    var color: UIColor = Hud.CYAN { didSet { setNeedsDisplay(); restyle() } }
    private let font: UIFont, em: CGFloat, labelColor: UIColor?
    private var text: String
    private let pad: UIEdgeInsets
    var onTap: () -> Void = {}

    init(_ text: String, font: UIFont, em: CGFloat, labelColor: UIColor? = nil, pad: UIEdgeInsets = .zero) {
        self.text = text; self.font = font; self.em = em; self.labelColor = labelColor; self.pad = pad
        super.init(frame: .zero)
        isOpaque = false; backgroundColor = .clear; contentMode = .redraw
        label.textAlignment = .center
        addSubview(label)
        restyle()
        addTarget(self, action: #selector(tapped), for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError() }
    @objc private func tapped() { onTap() }
    func setText(_ s: String) { text = s; restyle(); invalidateIntrinsicContentSize() }
    private func restyle() { label.attributedText = Hud.spaced(text, font, labelColor ?? color, em: em) }
    override var intrinsicContentSize: CGSize {
        let s = label.attributedText?.size() ?? .zero
        return CGSize(width: ceil(s.width) + pad.left + pad.right, height: ceil(font.lineHeight) + pad.top + pad.bottom)
    }
    override func layoutSubviews() { super.layoutSubviews(); label.frame = bounds }
    override var isHighlighted: Bool { didSet { alpha = isHighlighted ? 0.7 : 1 } }
    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let b = bounds, inset: CGFloat = 1.5, cut = b.height * 0.5
        let p = CGMutablePath()
        p.move(to: CGPoint(x: b.minX + cut, y: b.minY + inset)); p.addLine(to: CGPoint(x: b.maxX - cut, y: b.minY + inset))
        p.addLine(to: CGPoint(x: b.maxX - inset, y: b.midY)); p.addLine(to: CGPoint(x: b.maxX - cut, y: b.maxY - inset))
        p.addLine(to: CGPoint(x: b.minX + cut, y: b.maxY - inset)); p.addLine(to: CGPoint(x: b.minX + inset, y: b.midY)); p.closeSubpath()
        var r: CGFloat = 0, g: CGFloat = 0, bl: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &bl, alpha: &a)
        c.addPath(p); c.setFillColor(UIColor(red: r, green: g, blue: bl, alpha: 46.0 / 255).cgColor); c.fillPath()
        c.addPath(p); c.setStrokeColor(color.cgColor); c.setLineWidth(2); c.setLineJoin(.miter); c.strokePath()
    }
}

/**
 * HudScreen.kt: the camera full height with cyan line art over it, the state top left, and one
 * readout column on the right (the ball beside the number, what the spin is, what it is made of).
 */
final class HudScreen: UIView {
    let preview = UIView()
    private let shade = CAGradientLayer()
    private let flash = UIView()
    private let stateText = UILabel()
    private let serveLabel = UILabel()
    private let bigText = UILabel()
    private let scale = SpinScaleView()
    let spinBall = SpinBallView()
    private let typeText = UILabel()
    private let qualText = UILabel()
    private let split = SpinSplitView()
    private let coachBox = UIView()
    private let coachText = UILabel()
    let statsText = UILabel()
    let startBtn = HexButton("START", font: Hud.display(700, 15), em: 0.3)
    private let bottomLeft = UIView()
    private(set) var aiButton: HexButton!
    private(set) var windowsButton: HexButton!
    private(set) var shareButton: HexButton!
    private var devButtons: [HexButton] = []
    private(set) var devMode = false
    private var isRunning = false
    private var splitShown = false
    private var stateColor = Hud.CYAN
    /** "SERVE", or "VIDEO TEST · SERVE" while testing with a video */
    var source = "SERVE"

    var onStart: () -> Void = {}
    var onTest: () -> Void = {}
    var onReport: () -> Void = {}
    var onAi: () -> Void = {}
    var onWindows: () -> Void = {}
    var onShareClips: () -> Void = {}
    /** the camera picture's layer, sized with the screen */
    var onLayout: (CGRect) -> Void = { _ in }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        addSubview(preview)
        shade.colors = [UIColor.clear.cgColor, Hud.rgb(0, 8, 14, 232).cgColor]
        shade.startPoint = CGPoint(x: 0.34, y: 0.5); shade.endPoint = CGPoint(x: 1, y: 0.5)
        layer.addSublayer(shade)
        flash.alpha = 0; flash.layer.borderColor = Hud.CYAN.cgColor; flash.layer.borderWidth = 10; flash.isUserInteractionEnabled = false
        addSubview(flash)
        stateText.adjustsFontSizeToFitWidth = true; stateText.minimumScaleFactor = 12.0 / 20; stateText.numberOfLines = 1
        addSubview(stateText)
        state("Starting…", raw: "● STARTING…")
        for v in [serveLabel, bigText, typeText, qualText] { v.numberOfLines = 1; addSubview(v) }
        serveLabel.attributedText = Hud.spaced("SPIN RATE", Hud.mono(13), Hud.DIM, em: 0.18)
        bigText.layer.shadowColor = Hud.cyan(150).cgColor; bigText.layer.shadowRadius = 6; bigText.layer.shadowOpacity = 1; bigText.layer.shadowOffset = .zero
        setBig("----")
        typeText.adjustsFontSizeToFitWidth = true; typeText.minimumScaleFactor = 16.0 / 28
        addSubview(spinBall); addSubview(scale); addSubview(split)
        coachBox.layer.cornerRadius = 6
        coachText.numberOfLines = 0
        coachBox.addSubview(coachText)
        addSubview(coachBox)
        message("Checking the setup…")

        statsText.numberOfLines = 0; statsText.isUserInteractionEnabled = true
        statsText.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(toggleDev(_:))))
        bottomLeft.addSubview(statsText)
        func small(_ t: String, _ tap: @escaping () -> Void) -> HexButton {
            let b = HexButton(t, font: Hud.mono(11, medium: true), em: 0.14, labelColor: Hud.CYAN, pad: UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16))
            b.color = Hud.DIM; b.onTap = tap; bottomLeft.addSubview(b); return b
        }
        aiButton = small("SETUP ASSISTANT: OFF") { [weak self] in self?.onAi() }
        devButtons = [small("TEST VIDEO") { [weak self] in self?.onTest() }, small("REPORT") { [weak self] in self?.onReport() }]
        windowsButton = small("CHIP WINDOWS: OFF") { [weak self] in self?.onWindows() }
        shareButton = small("OFFER TO SHARE CLIPS: OFF") { [weak self] in self?.onShareClips() }
        devButtons += [windowsButton, shareButton]
        for b in devButtons { b.isHidden = true }
        addSubview(bottomLeft)
        startBtn.onTap = { [weak self] in self?.onStart() }
        addSubview(startBtn)
        showSpin(nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func toggleDev(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        devMode.toggle(); for b in devButtons { b.isHidden = !devMode }; setNeedsLayout()
    }

    // ------------------------------------------------------------------ layout (dp = pt)
    override func layoutSubviews() {
        super.layoutSubviews()
        let W = bounds.width, H = bounds.height, safe = safeAreaInsets
        shade.frame = bounds
        preview.frame = bounds
        onLayout(bounds)
        flash.frame = bounds
        let left = max(40, safe.left)
        // the readout column: 400 wide plus whatever the safe area takes from its right padding
        let padR = max(36, safe.right)
        let colW = 400 + (padR - 36)
        let colX = W - colW
        stateText.frame = CGRect(x: left, y: 20, width: max(0, colX - 16 - left), height: 30)

        let cx = colX + 8, cw = colW - 8 - padR
        var y: CGFloat = 22
        serveLabel.frame = CGRect(x: cx, y: y, width: cw, height: ceil(Hud.mono(13).lineHeight)); y = serveLabel.frame.maxY + 6
        let numW = cw - 112 - 10
        let bigH = ceil(Hud.display(300, 64).lineHeight)
        let numH = bigH + 6 + 30
        let rowH = max(112, numH)
        spinBall.frame = CGRect(x: cx, y: y + (rowH - 112) / 2, width: 112, height: 112)
        let ny = y + (rowH - numH) / 2
        bigText.frame = CGRect(x: cx + 122, y: ny, width: numW, height: bigH)
        scale.frame = CGRect(x: cx + 122, y: ny + bigH + 6, width: numW, height: 30)
        y += rowH + 8
        typeText.frame = CGRect(x: cx, y: y, width: cw, height: 36); y += 36
        qualText.frame = CGRect(x: cx, y: y, width: cw, height: ceil(Hud.mono(14).lineHeight)); y = qualText.frame.maxY
        if splitShown { split.frame = CGRect(x: cx, y: y + 8, width: cw, height: 84); y = split.frame.maxY }
        let bottomPad: CGFloat = isRunning ? 14 : 84
        let tw = cw - 24
        let need = ceil(coachText.sizeThatFits(CGSize(width: tw, height: .greatestFiniteMagnitude)).height)
        let boxH = min(need + 19, max(0, H - bottomPad - (y + 8)))
        coachBox.frame = CGRect(x: cx, y: y + 8, width: cw, height: boxH)
        coachText.frame = CGRect(x: 12, y: 9, width: tw, height: max(0, boxH - 19))

        let bx = isRunning ? left : W - padR - 176
        startBtn.frame = CGRect(x: bx, y: H - max(24, safe.bottom) - 48, width: 176, height: 48)

        // bottom left: the stats, the assistant button, the developer row
        let st = statsText.sizeThatFits(CGSize(width: W / 2, height: .greatestFiniteMagnitude))
        var by: CGFloat = 0
        statsText.frame = CGRect(x: 0, y: 0, width: ceil(st.width), height: ceil(st.height)); by = statsText.frame.maxY + 8
        let ai = aiButton.intrinsicContentSize
        aiButton.frame = CGRect(x: 0, y: by, width: ai.width, height: ai.height); by = aiButton.frame.maxY
        var bw = max(statsText.frame.maxX, aiButton.frame.maxX)
        if devMode {
            by += 8; var bxx: CGFloat = 0; var rowH: CGFloat = 0
            for b in devButtons { let s = b.intrinsicContentSize; b.frame = CGRect(x: bxx, y: by, width: s.width, height: s.height); bxx += s.width + 10; rowH = max(rowH, s.height) }
            by += rowH; bw = max(bw, bxx - 10)
        }
        bottomLeft.frame = CGRect(x: left, y: H - max(24, safe.bottom) - by, width: bw, height: by)
        bottomLeft.isHidden = isRunning
    }

    // ------------------------------------------------------------------ what HudScreen.kt offers
    private func state(_ s: String, raw: String? = nil) {
        stateText.attributedText = Hud.spaced(raw ?? ("● " + s.uppercased()), Hud.mono(20, medium: true), stateColor, em: 0.1)
    }
    private var lastState = "● STARTING…"
    func state(_ s: String) { lastState = "● " + s.uppercased(); state(s, raw: lastState) }
    /** only the state line changes colour: cyan ready, amber measuring, red a problem */
    func tone(_ c: UIColor) { stateColor = c; state("", raw: lastState) }
    func busy(_ on: Bool) { spinBall.hold(on) }

    /** ready for the next serve: the border flashes once */
    func readyCue() {
        flash.layer.removeAllAnimations()
        flash.alpha = 0
        UIView.animate(withDuration: 0.14, delay: 0, options: [.curveEaseInOut], animations: { self.flash.alpha = 1 }) { done in
            guard done else { return }
            UIView.animate(withDuration: 0.7, delay: 0.26, options: [.curveEaseInOut], animations: { self.flash.alpha = 0 })
        }
    }

    func running(_ on: Bool) {
        isRunning = on
        startBtn.setText(on ? "STOP" : "START")
        startBtn.color = on ? Hud.RED : Hud.CYAN
        setNeedsLayout()
    }

    private func setBig(_ s: String, rpm: Int? = nil) {
        let f = Hud.display(300, 64)
        let a = NSMutableAttributedString(string: s, attributes: [.font: f, .foregroundColor: Hud.CYAN])
        if rpm != nil { a.append(NSAttributedString(string: " RPM", attributes: [.font: Hud.display(300, 64 * 0.3), .foregroundColor: Hud.CYAN])) }
        bigText.attributedText = a
    }
    private func setType(_ s: String) { typeText.attributedText = Hud.spaced(s, Hud.display(700, 28), Hud.WHITE, em: 0.06) }
    private func setQual(_ s: String) { qualText.attributedText = Hud.spaced(s, Hud.mono(14), Hud.DIM, em: 0.04) }
    private func setLabel(_ s: String) { serveLabel.attributedText = Hud.spaced(s, Hud.mono(13), Hud.DIM, em: 0.18) }

    /** a serve read: its number, RPM (to 10), what the spin is, any line for the player, the spin to draw */
    func reading(_ serve: Int, rpm: Int, type: String, coach: String, spin: Spin?, kmh: Int?) {
        let t = type.lowercased()
        setLabel("\(source) " + String(format: "%02d", serve) + (kmh.map { " · \($0) km/h" } ?? ""))
        setBig("\(rpm)", rpm: rpm)
        setType(SpinLevel.outcome(rpm, t).uppercased())
        setQual(Words.qualifier(t, spin))
        scale.rpm = rpm
        showSpin(spin)
        message(coach)
    }
    func noRead(_ serve: Int, _ text: String) {
        setLabel("\(source) " + String(format: "%02d", serve) + " · NO READ")
        setBig("----"); setType(""); setQual(""); scale.rpm = nil
        showSpin(nil)
        message(text)
    }
    /** setting up: the on-device check's advice; the state line names it */
    func advice(short: String, text: String, good: Bool) {
        state("Setup · \(short)"); tone(good ? Hud.CYAN : Hud.AMBER)
        if problemShowing { heldAdvice = text; return }
        if !aiTalking { message(text) }
    }

    private var problemAt: CFTimeInterval = 0
    private static let PROBLEM_HOLD: CFTimeInterval = 15
    private var problemShowing: Bool { CACurrentMediaTime() - problemAt < HudScreen.PROBLEM_HOLD }
    private var heldAdvice: String?
    /** something the player can act on went wrong: larger, red edged; setup advice waits 15 s before covering it */
    func problem(_ text: String) {
        problemAt = CACurrentMediaTime()
        message("⚠  \(text)", problem: true)
        let at = problemAt
        DispatchQueue.main.asyncAfter(deadline: .now() + HudScreen.PROBLEM_HOLD) { [weak self] in
            guard let self, self.problemAt == at, let h = self.heldAdvice else { return }
            self.heldAdvice = nil; self.message(h)
        }
    }
    private var aiLine = ""
    private var aiTalking = false
    func aiText(_ t: String) {
        if t == "\n" { aiTalking = false; aiLine = ""; return }
        aiTalking = true; aiLine += t
        if !problemShowing { message("ASSISTANT  \(aiLine.trimmingCharacters(in: .whitespacesAndNewlines))") }
    }
    func aiState(_ on: Bool) { aiButton.setText(on ? "SETUP ASSISTANT: ON" : "SETUP ASSISTANT: OFF"); if !on { aiTalking = false }; setNeedsLayout() }
    func windowsState(_ on: Bool) { windowsButton.setText(on ? "CHIP WINDOWS: ON" : "CHIP WINDOWS: OFF"); setNeedsLayout() }
    func shareState(_ on: Bool) { shareButton.setText(on ? "OFFER TO SHARE CLIPS: ON" : "OFFER TO SHARE CLIPS: OFF"); setNeedsLayout() }
    func stats(_ s: String) { statsText.attributedText = Hud.spaced(s, Hud.mono(9.5), Hud.DIM, em: 0); setNeedsLayout() }

    func testStarted(_ name: String) {
        source = "VIDEO TEST · SERVE"
        setLabel("VIDEO TEST"); setBig("····"); setType("READING THE VIDEO"); setQual(""); scale.rpm = nil
        showSpin(nil)
        message(name)
    }
    func testFoundNone(_ why: String) {
        setLabel("VIDEO TEST"); setBig("NONE"); setType("NO SERVE FOUND"); setQual(""); scale.rpm = nil
        showSpin(nil)
        message(why)
    }

    private func message(_ text: String, problem: Bool = false) {
        if !problem { problemAt = 0; heldAdvice = nil }
        let ps = NSMutableParagraphStyle(); ps.lineHeightMultiple = 1.15
        coachText.attributedText = NSAttributedString(string: text, attributes: [
            .font: Hud.mono(problem ? 20 : 16), .foregroundColor: problem ? Hud.WHITE : Hud.rgb(235, 248, 252, 235), .paragraphStyle: ps])
        coachBox.isHidden = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        coachBox.backgroundColor = Hud.rgb(0, 20, 30, 150)
        coachBox.layer.borderWidth = problem ? 3 : 1
        coachBox.layer.borderColor = (problem ? Hud.RED : Hud.cyan(90)).cgColor
        setNeedsLayout()
    }

    private func showSpin(_ spin: Spin?) {
        split.spin = spin
        if let s = spin { spinBall.isHidden = false; spinBall.show(s); splitShown = true }
        else { spinBall.clear(); spinBall.isHidden = true; splitShown = false }
        split.isHidden = !splitShown
        setNeedsLayout()
    }
}
