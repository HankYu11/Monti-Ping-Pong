import Foundation
import ServeCoachCore

/** A bounded queue that blocks: LinkedBlockingQueue's put, offer and poll, as LivePipeline uses them. */
final class BlockingQueue<T> {
    private var items: [T] = []
    private var head = 0
    private let cond = NSCondition()
    let capacity: Int
    init(capacity: Int) { self.capacity = capacity }
    var count: Int { cond.lock(); defer { cond.unlock() }; return items.count - head }
    var remainingCapacity: Int { capacity - count }
    func put(_ x: T) {
        cond.lock(); while items.count - head >= capacity { cond.wait() }
        items.append(x); cond.broadcast(); cond.unlock()
    }
    func offer(_ x: T) -> Bool {
        cond.lock(); defer { cond.unlock() }
        if items.count - head >= capacity { return false }
        items.append(x); cond.broadcast(); return true
    }
    /** the next item, waiting up to [ms] */
    func poll(ms: Int) -> T? {
        cond.lock(); defer { cond.unlock() }
        let end = Date().addingTimeInterval(Double(ms) / 1000)
        while items.count == head { if !cond.wait(until: end) { return nil } }
        let x = items[head]; head += 1
        if head > 256 && head * 2 > items.count { items.removeFirst(head); head = 0 }
        cond.broadcast(); return x
    }
}

/** A value set once, waited for (Java's Future.get). */
final class Future<T> {
    private var value: T?
    private let cond = NSCondition()
    func set(_ v: T) { cond.lock(); value = v; cond.broadcast(); cond.unlock() }
    func get() -> T { cond.lock(); defer { cond.unlock() }; while value == nil { cond.wait() }; return value! }
}

func nowMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

extension Data {
    var kotlin: KotlinByteArray {
        return withUnsafeBytes { raw in IosFrames.shared.bytes(p: UnsafeMutableRawPointer(mutating: raw.baseAddress!), len: Int32(count)) }
    }
}

extension KotlinByteArray {
    var data: Data {
        var d = Data(count: Int(size))
        d.withUnsafeMutableBytes { raw in if size > 0 { IosFrames.shared.doCopyOut(a: self, p: raw.baseAddress!) } }
        return d
    }
}

extension Array where Element == Int32 {
    var kotlin: KotlinIntArray { let a = KotlinIntArray(size: Int32(count)); for (i, v) in enumerated() { a.set(index: Int32(i), value: v) }; return a }
}
extension KotlinIntArray {
    var swift: [Int32] { (0..<size).map { get(index: $0) } }
}
