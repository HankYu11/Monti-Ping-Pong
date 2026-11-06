# Serve Coach Live: 60-second commercial (zh-TW)

A 1920×1080, 30 fps commercial for Taiwanese partners and social media. Built with Remotion (React)
and Three.js: every shot is code, so text, prices and numbers can change and the film re-renders.

    npm install
    npx remotion studio          # preview and scrub
    npx remotion render Commercial out/serve-coach-60s.mp4
    node stills.mjs 100 560 1240 # half-size stills of chosen frames, for checking

## Script

| Time | Scene | Voice (zh-TW) | On screen |
|---|---|---|---|
| 0:00 | Hook: the marked ball spinning, close up | 你的發球，到底轉多快？ | 你的發球，轉多快？ |
| 0:05 | A plain ball flies over the table | 旋轉看不見。對手感覺得到，你卻不知道。 | 旋轉，看不見。 |
| 0:10 | Phone on a tripod at the side of the table; the real HUD on its screen | 把手機架在球桌旁，每秒 240 格，看清每一顆球。 | 240 FPS |
| 0:17 | The app's screen (the 0.9.2 HUD): a serve, "measuring", then 2450 RPM | (app voice) 2450 RPM, strong spin, backspin. / 轉速、旋轉種類、強弱，幾秒內直接說給你聽。 | 轉速・旋轉・強弱 幾秒內說給你聽 |
| 0:28 | The strength scale, the world-class average | 世界頂尖選手的發球，平均約 2,760 轉。你還差多少？ | 2,760 RPM, 還差 310 轉 |
| 0:35 | The 3D-printed cup and stencil marking a ball | 半顆塗黑，再點上三個色點。用 3D 列印的標記工具，一分鐘完成。 | 一分鐘標記 |
| 0:44 | Three reasons | 不用網路，不必買專用球。教練一支手機，整班輪流練發球。 | 離線運作・不必買專用球・整班輪流 |
| 0:51 | End card | Serve Coach Live。讓每一顆發球，都有數字。 | Android 搶先體驗・經銷合作洽詢 |

Every claim is one the app makes today (v0.9.2): 240 fps, spoken RPM, type and band, the 2,760 RPM
reference (2009 World Championships quarterfinals, Tamaki et al.), offline, marked balls (black
half + three dots, `05-ball-marker`), NEXT SERVE for a group. Two things are said carefully:
- the two-tone (orange) ball is **not** claimed: it is untested (experiment E2), so the film shows
  the black-half marking;
- the app speaks **English**; the film plays its English line and captions it as the app's voice.

## Before it runs as a paid ad

1. **Replace two shots with real footage** (the rest can stay as is):
   - 0:10–0:16: a real phone on a tripod beside a real table, a player serving. 4K or 1080p, 2–3
     takes, the phone screen visible.
   - 0:17–0:28: a screen recording of the app reading a real serve (it shows the number and speaks).
     Use the real number it gives and change `HudScene` / the voice line to match.
   Drop the clips in `public/` and use `<OffthreadVideo>` in place of the 3D scene.
2. **Voice**: the voice is Microsoft's neural TTS (zh-TW HsiaoChen, through `edge-tts`). Its terms for
   paid advertising are unclear; for a paid campaign, re-record with a voice actor or a TTS licensed
   for commercial use (e.g. ElevenLabs paid plans, Google Cloud TTS) using the script above. The
   timings in `src/theme.ts` (`VO`) then need the new durations.
3. **3D-printed tools**: the marking scene is a render of the STL files, labelled 示意渲染; they have
   not been printed yet. A real photo or clip of a printed cup is better once one exists.
4. **Licences**: music is Mixkit "Rising Forest" (Mixkit Stock Music Free License: use in
   commercial videos, no attribution); fonts Oxanium, IBM Plex Mono, Noto Sans TC (all OFL).
   Remotion is free for individuals and companies of up to 3 people; larger companies need a
   Remotion company licence.

## How it was made (what others do with AI for video, late 2026)

The common pattern for AI-made commercials is code-first motion graphics: the model writes the
animation (Remotion or HyperFrames), a headless browser renders each frame, and real footage or
generated B-roll is cut in for live-action shots ([opus5video](https://opus5video.com/how-to-make-videos-with-claude/),
[BetterToken](https://bettertoken.ai/en/blog/claude-opus-5-5-remotion-video-workflow/),
[wireflow](https://www.wireflow.ai/blog/hyperframes-vs-remotion),
[Pexo](https://pexo.ai/blog/programmatic-vs-ai-generated-video-with-claude-code-remotion-hyperframes-and-pexo-compared-6108)).
Their advice, followed here: brief and shot list first, stills before animating, voice first and shots
timed to it, real UI rather than invented screens, at most a few words on screen, a held last frame,
and a render-and-look loop. Generated video (Veo, Kling, Sora) was **not** used for the serve shots:
it can't show a real measurement, and a made-up reading in an ad for a measuring tool would mislead.
