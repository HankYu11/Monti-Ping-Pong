# Online serve clips — catalog

Collected 2026-10-14 from YouTube (Chinese, Japanese, Korean and English searches) and
Bilibili. **267 candidates** are in [`all_candidates.csv`](all_candidates.csv); the
useful ones are sorted into tiers below.

- **Downloading:** YouTube blocks downloads from cloud servers, so run
  [`fetch_clips.sh`](fetch_clips.sh) on your own computer. Videos go to `raw/`, which is
  not committed (size and copyright). Use them for private analysis only.
- **Bilibili:** downloads work, but only up to 480p/720p without a login. Several videos
  also exist in 1080p 60 FPS for logged-in members, which is worth it for the Tier A clips.
- **Niconico, Vimeo, Dailymotion:** searched, nothing useful found.

## What screening showed

Frames from 10 downloaded Bilibili tutorials and 20 YouTube thumbnails were checked:

1. **Most "slow motion" is fake.** Typical tutorials are filmed at 24–30 FPS and slowed
   in editing. Real high-speed footage is rare, and it's mostly Japanese (Tier A).
2. **Camera angle is usually wrong for measuring.** Tutorials film from the front or at
   an angle, with the coach talking; the contact is small in the frame. Side-on views of
   the contact are rare.
3. **Labels exist only as the coach's intent.** "This one is backspin, this one is no
   spin" (Tier B), or "bad example / good example" (Tier C). No clip gives a measured spin.
4. **Chinese and Japanese material is strongest for coaching content:** many videos
   specifically about *why a beginner's backspin serve doesn't spin*, which is exactly the
   fault table we need.

5. **30 FPS cannot show the contact.** Frame-by-frame check of a fixed-camera serve
   practice clip ([BV1Znaw6EEEZ](https://www.bilibili.com/video/BV1Znaw6EEEZ), 852×480,
   30 FPS, camera behind the far end): the whole racket swing through the ball takes about
   3 frames, the racket is a smear in those frames, the ball is hidden behind the
   racket/body at contact, and the next frame already shows the follow-through. Racket
   angle and contact point can't be measured at 30 FPS, even with a better angle. The
   experiment needs **at least 120 FPS, ideally 240 FPS**, which none of the downloadable
   Bilibili clips have.

So: **Tier A** is for testing measurement (racket angle, contact, ball rotation),
**Tier B** for a rough spin vs no-spin test, **Tier C** for the beginner fault list.

---

## Tier A — real high-speed footage of the serve contact (measurement tests)

| # | Lang | Video | Why |
|---|---|---|---|
| A1 | EN | [Slow motion table tennis; how spinny is your serve?](https://www.youtube.com/watch?v=LFp78dMr3Rk) — Paul Redman | High-speed close-up of serve contact on a dark background; ball rotation looks readable. **Top candidate.** |
| A2 | JP | [Vertical Backspin Reveal: High-Speed Camera Analysis](https://www.youtube.com/watch?v=rRPh_Y5VJTU) — ITS三鷹卓球クラブ | High-speed camera, backspin serve, racket and ball clearly visible |
| A3 | JP | [下回転サーブが切れる瞬間をスーパースローにしてみた](https://www.youtube.com/watch?v=k_crfUrDyVI) — WRM-TV | Super-slow of the moment a backspin serve "cuts"; close-up from the front |
| A4 | JP | [下回転サーブで回転力をアップ！スロー分析](https://www.youtube.com/watch?v=pBR4g7DoSuc) — WRM-TV | Slow analysis with **bad vs good example side by side** (also Tier C) |
| A5 | JP | [戻る下回転サーブのコツ](https://www.youtube.com/watch?v=hFGKoSMHKO8) — WRM-TV | Backspin serve that comes back; outcome visible |
| A6 | JP | [【卓球】スーパースロー撮影をしました。](https://www.youtube.com/watch?v=8GMNuF-eTB4) — Tera賀茂塾 | Filmed with a Sony RX10 IV (high-speed camera); check for serves |
| A7 | JP | [高速度カメラで撮影してみたシリーズ　卓球](https://www.youtube.com/watch?v=sphWvygdyEg) — 理科フリースクールマイム | High-speed camera, orange ball close-up (16 s) |
| A8 | EN | [Table Tennis Backspin Serve Slowmotion](https://www.youtube.com/watch?v=luWJofKQUqY) — Sam Humphreys | 9 minutes of backspin serves in slow motion; check FPS |
| A9 | EN | [table tennis serves in 8x slow motion](https://www.youtube.com/watch?v=dolQO-M8LJE) — kroliknor | Filmed at 240 FPS |
| A10 | EN | [A ball tracking system - Table Tennis Service](https://www.youtube.com/watch?v=2y2WpX1p2LE) — ouxgmt | Research demo: ball tracking on a serve, with plotted trajectory |
| A12 | JP | [DITECT multi-angle super slow — table tennis](https://www.youtube.com/watch?v=yfug_BlwvT0) — DITECT (high-speed camera maker) | Professional high-speed, but rallies, not serves. For tool testing only. |

## Tier B — same motion, labelled spin vs no spin (rough classification test)

| # | Lang | Video | Notes |
|---|---|---|---|
| B1 | JP | [ラケット角度一定で上回転と下回転を打ち分ける考え方](https://www.youtube.com/watch?v=Je7OoYsVNWo) — 卓球の物理学 | "Table tennis physics": how the swing path, not the racket angle, decides topspin vs backspin. Useful theory for the feature list. |
| B2 | CN/EN | [Same motion: ghost underspin vs no-spin](https://www.youtube.com/watch?v=rfS9hgRQ7QY) — Table tennis teaching channel | Retired Chinese national team player |
| B3 | CN | [一个动作发出下旋球和不转球](https://www.youtube.com/watch?v=F-cUCHj7ibQ) — 乒乓国球汇 | Backspin and no-spin from one motion |
| B4 | CN | [用王楚钦发球慢动作！教你用下旋动作发不转球](https://www.youtube.com/watch?v=2qSxBl3Omlw) — 小元爱乒乓 | Slow-motion comparison |
| B5 | CN | [下旋球和不转球到底怎么判断？发球动作有什么区别？](https://www.youtube.com/watch?v=4xD5kbtWqrw) — 雨洋乒乓 | 20 min on telling backspin from no-spin by the motion |
| B6 | CN | [死转？不转？超实用发球教学](https://www.bilibili.com/video/BV1hBpA6WE1X) — Top创乒社 (Bilibili) | Screened: 30 FPS, rear/oblique views; serves land on table with visible outcome |
| B7 | JP | [下回転・ナックルサーブ見極め](https://www.youtube.com/watch?v=RuYhHx3s9-Y) — 卓球三昧 | Telling backspin from knuckle (no-spin) |
| B8 | EN | [The Fake Motion Serve: Spin vs No Spin](https://www.youtube.com/watch?v=plg3Cxc6MBs) — Table Tennis Teacher | |
| B10 | JP (CN re-upload) | [【乒乓球慢镜】超慢速拍下旋发球瞬间的](https://www.bilibili.com/video/BV1QP4y1W7iJ) — re-upload of a WRM-TV video (Bilibili) | Downloaded and screened: 1280×720 at 24 FPS, so not raw high-speed. Each serve is labelled ○/× "Spin" / "No Spin", with diagrams of where the ball should touch the rubber (start low near the handle and brush toward the tip, versus hitting the centre). Also 6 numbered fault topics: Tier C material too. |
| B9 | EN | [Spin/No-Spin Serve Secrets: Same Motion](https://www.youtube.com/watch?v=Ag0VJBfgQLw) — Table Tennis Decoded | |

Caution: these serves are *designed* to look the same, so they are the hardest case, not
a typical beginner case.

## Tier C — why a beginner's backspin serve doesn't spin (fault list)

| # | Lang | Video | Notes |
|---|---|---|---|
| C1 | JP | [下回転サーブが切れない原因は「コレ」！](https://www.youtube.com/watch?v=UzyUDurIR-I) — 坂本竜介 (former national player) | Main cause of no spin, and how to add spin |
| C2 | JP | [下回転サーブが切れない方の特徴5選](https://www.youtube.com/watch?v=_qdeDyuo_Eo) — 瀬能卓球 | **5 typical faults** of players whose backspin doesn't spin |
| C3 | JP | [下回転サーブを切りたいなら「サーブは切るな」](https://www.youtube.com/watch?v=vLublUZAqlQ) — 下川裕平 | Counter-intuitive cue |
| C4 | JP | [下回転サーブを切るコツ（初級編）](https://www.youtube.com/watch?v=f6BVD8bZw9Y) — 卓球ジュニア強化 | Beginner level |
| C5 | CN | [下旋发球练了很久还是不转？关键不是发力，而是先找到摩擦！](https://www.youtube.com/watch?v=c_WE17iFnAw) — 大桥老师 | "Not power, find the brushing contact first" |
| C6 | CN | [下旋发球为什么不转？关键是球拍要"超过球"！](https://www.youtube.com/watch?v=0uelKjwOsqA) | "The racket must pass under/beyond the ball" |
| C7 | CN | [正手和反手发下旋球都发不转怎么办？](https://www.youtube.com/watch?v=oKbhVI7PPD4) — 乒乓国球汇 | Amateur players corrected on camera |
| C8 | CN | [下旋发球超细教学](https://www.bilibili.com/video/BV1YN4y1F7pq) — 冀烨隆乒乓教磕书 (Bilibili, 180k views) | Screened: key points shown include 拍形要平 (flat racket face), 靠小臂 (use the forearm), contact at the bottom then forward |
| C9 | CN | [不会发下旋球？真相只有一个！](https://www.bilibili.com/video/BV1fG4y1b7FN) — 乒乓国球汇 (Bilibili, 320k views) | |
| C10 | CN | [怎么发很转的下旋球？发出去竟然还能回跳](https://www.bilibili.com/video/BV1R94y1B7Pp) — 乒乓国球汇 (Bilibili) | Screened: "brush the bottom of the ball", "flat racket face", plus the come-back outcome on the table |
| C11 | KR | [강력한 하회전 서브는 이렇게 하세요! 노하우 4가지](https://www.youtube.com/watch?v=tKzr0ILn4Jo) — 윤탁구 | 4 tips for strong backspin |
| C12 | EN | [How to return a heavy backspin serve and correct backspin serve errors](https://www.youtube.com/watch?v=jGqitInXWkY) — TI LONG | |
| C13 | EN | [How to serve a backspin ball that bounces back](https://www.youtube.com/watch?v=bQTenTT3bz0) — TT SpinMaster | |

## Tier D — pro serves in slow motion (reference only)

- 《乒乓球慢动作教学视频》 series by yunpeng guo on YouTube, for example
  [水谷隼正手重下旋发球](https://www.youtube.com/watch?v=K2t3OOV2RZQ) and
  [水谷隼正手迷惑性下旋发球](https://www.youtube.com/watch?v=lpEhmWkWCiI). Hundreds of episodes.
- Bilibili: [慢镜头下马龙发球](https://www.bilibili.com/video/BV12t4y1Y7vt),
  [马龙发球慢动作解析](https://www.bilibili.com/video/BV1oW41137cW),
  [马龙的发球近距离慢动作](https://www.bilibili.com/video/BV16t411C73f).

---

## Next steps

1. Run `fetch_clips.sh` locally (Tier A first).
2. For each Tier A clip, check the real frame rate (count frames across one serve) and
   whether the racket edge and ball markings are visible at contact. Cut the usable serves
   into short clips.
3. Tier B: try the hand-measured features on "spin" vs "no spin" serves.
4. Tier C: write the beginner fault list (feeds the fault → cause table in `PLAN.md`).
5. For real spin numbers, a small own recording is still needed. Tip from a Singapore
   study: paint **half the ball black** to read the rotation from 200 FPS video
   ([ISBS proceedings](https://ojs.ub.uni-konstanz.de/cpa/article/view/1311/1232)).
