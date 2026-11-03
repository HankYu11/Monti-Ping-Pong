# AI / CV for table-tennis spin estimation (focus: serves, single phone at 240 fps)

Research date: 2026-11-03. Setup assumed: Pixel 8, 240 fps, 1920x1080, tripod beside the table, ball about 25-35 px across, some motion blur. Today: marked balls (half-black or dots) give rpm within about 5% of hand counts.

Notes on confidence:
- Numbers are quoted from the cited source. "Unverified" means I saw it only in an abstract, a vendor page or a secondary source.
- "My estimate" marks numbers I derived myself. No paper has published them.

---

## 0. Physical limits that matter for us

- **Serve spin range.** Elite serves run about 16-83 rps (about 1,000-5,000 rpm).
  - A Japanese journal snippet gives men 35.7-83.3 rps and women 16.4-66.7 rps. I could not confirm which study it comes from, so treat it as unverified.
  - Tamaki et al. measured 1,773 elite serves ([J. Sports Analytics 2025](https://lida.sport-iat.de/twm/Record/4096355)).
- **Aliasing at 240 fps.** For a feature seen once per revolution, 240 fps is alias-free only below about 120 rps, and in practice less.
  - The openspin project quotes "alias-free below 113 rps at 240 fps".
  - The event-camera paper states the frame-camera limit as "fps > |ω|/2" ([Gossard et al., CVPRW 2024](https://arxiv.org/html/2404.09870v1)).
  - Serves fit inside this limit. Our marked-ball pipeline already exploits this.
- **The logo is often not visible from one camera.**
  - Gossard 2024: "barely visible or hidden from a single camera's point of view".
  - Under top/backspin the logo enters and leaves the view. Under sidespin it is either always visible or always hidden.
  - In their example only 2 frames of a flight showed the logo. Logo lines thinner than about 1 mm produced too few events to detect.
- **Magnus effect is strong for drives and weak for serves.**
  - The SPIN dataset (Google) found pro topspin drives fall at about 17.5-24 m/s². A no-spin ball falls at 9.8 m/s². So Magnus can exceed 1 g on attacking strokes.
  - Serves are slow (a few m/s) and their segments are short, so the Magnus signal is much smaller. For serves, **the bounce** carries most of the trajectory information. Backspin kills or reverses the horizontal speed at the bounce. Topspin keeps or increases it, and gives a lower, flatter rebound.
- **Sidespin is the weak axis for a side-on camera.**
  - Sidespin curvature and sideways bounce kick move the ball toward or away from the camera, which is our depth axis and the least precise one.
  - Top/backspin acts in the image plane, which is the axis we see best.

---

## 1. Spin on UNMARKED balls (logo- or appearance-based)

| Work | Input | Result | Public? |
|---|---|---|---|
| **Tebbe et al., "Spin Detection in Robotic Table Tennis", ICRA 2020** ([arXiv 1905.07967](https://arxiv.org/abs/1905.07967)) | PointGrey camera at 380 fps (1920x400 ROI, 0.25 ms exposure). Ball about **70x70 px**. Logo found by background subtraction or by a ResNet-18 that predicts ball orientation from the crop. | Logo-direction error 4.2-5.8°. **Spin-type classification: 79.6% (background subtraction), 85.3% (segment fit), 86.7% (CNN)**, versus **88.2% for trajectory-only Magnus fitting**. No rpm error reported because there was no ground truth. CNN takes 3.7 ms per crop on a GTX 1080 Ti. | Paper only |
| **Gossard et al., event-camera logo spin, CVPRW 2024** ([arXiv 2404.09870](https://arxiv.org/abs/2404.09870)) | Prophesee EVK4 (1280x720) about 2 m above the table, about 1,930 lux. | Event camera: success 0.81, **10.7±17.3 rps** magnitude error, **32.9±38.2°** axis error. Their frame-based logo baseline (ref. [39]): success 0.79, **8.8±15.6 rps**, 26.6±33.4°. | Paper |
| **Event-based gaze control, 2026** ([arXiv 2606.26780](https://arxiv.org/abs/2606.26780)) | Event camera plus galvo mirrors plus a tunable telephoto lens. Unmodified balls in pro matches. | Online: **8.8% magnitude and 6.4° axis** versus their offline method, on the 50% most confident predictions. The main failure mode is an invisible logo. | Paper only. Lab hardware. |
| **Tamaki, Yamagata, Hashizume, IJCSS 2024** ([record](https://lida.sport-iat.de/twm/Record/4087655), doi 10.2478/ijcss-2024-0003) | Several **unsynchronized, non-high-speed** cameras tracking the printed logos, combined with trajectory to resolve aliasing. | Median error **0.78 rps** and **12.5°** axis. Distinguishes axes 30° apart with 95.8% confidence. Measured **92.1% of serves** in T-League (reported after broadcasts). | Paper. Camera count and fps not in the abstract. |
| **Boracchi / Caglioti / Giusti 2008-09**, spin from a single motion-blurred image ([pdf](https://boracchi.faculty.polimi.it/docs/2009_09_09_InstantaneousBall3DMotion_Boracchi.pdf)) | One calibrated, blurred image of a textured ball. | Works only if the ball has texture. Gossard notes that blur-based spin "does not seem to scale well to higher spin values". | Paper |
| Baseball analogues: ResNet-LSTM markerless spin rate and axis at 960 fps validated against TrackMan ([SPIE 2025](https://proceedings.spiedigitallibrary.org/conference-proceedings-of-spie/14072/1407230/)); patent "Estimating spin rate and axis of a ball using deep learning" (US 12582870). | Large balls with seams at 960 fps. | Numbers not retrieved. | No |

**Assessment for our setup.**
- Every successful unmarked-ball result used one of these:
  - a much larger ball image (about 70 px at 380 fps),
  - special sensors (event cameras, galvo tracking), or
  - several cameras plus trajectory fusion (Tamaki).
- At 25-35 px the logo is about 5-10 px, low-contrast, blurred, and visible in only some frames, and possibly none under sidespin.
- My estimate:
  - Logo-only spin would yield a reading on a minority of serves, perhaps 30-60%.
  - When it works, errors would be about 10-20%, similar to the Gossard frame baseline.
  - The axis would be poor.
- **Training data problem for an appearance deep net.**
  - Labels from marked balls do not transfer to plain-ball crops, because the appearance differs.
  - You would need synthetic renders (Blender: logo, blur, rolling shutter, 30 px) or a separate ground-truth rig.
- **Feasibility:** medium-high effort with uncertain payoff. Best used as an **opportunistic refinement** when a logo is seen in 3 or more frames, fused with a trajectory prior (as Tamaki does). It should not be the primary estimator.
- **Hardware lever.** Zooming or cropping so the ball is about 60+ px (camera closer to the server's half) would help logo methods far more than any model change. Doing that costs field of view.

---

## 2. Spin from trajectory / bounce physics (optionally learned)

| Work | Input | Result | Public? |
|---|---|---|---|
| **Kienzle et al., CVPRW 2025** ([arXiv 2504.19863](https://arxiv.org/abs/2504.19863), [project](https://kiedani.github.io/CVPRW2025/)) | 2D ball pixels per frame plus 13 table keypoints. Monocular broadcast video (2560x1440 at 50 Hz). Trained **only on physically simulated synthetic data**. | **92.0% top/backspin accuracy** (macro-F1 0.917, AUC 0.990) on 50 real pro trajectories. Spin-vector error on synthetic data: 48.7 Hz. Real labels were derived from paddle orientation. Sidespin and magnitude are not evaluated on real data. | Code, weights, synthetic data and annotations are on the project page. |
| **Kienzle et al., "Uplifting Table Tennis", WACV 2026** ([arXiv 2511.20250](https://arxiv.org/abs/2511.20250), [code](https://github.com/KieDani/UpliftingTableTennis)) | Full pipeline: Segformer++ ball and table-keypoint detectors, then a 2D-to-3D "uplifting" transformer. Robust to missed detections and **varying frame rates** (trained at 20-60 fps). 1920x1080 input. | Top/back accuracy **89.5% on TTHQ** (57 trajectories) and **97.1% on TTST** (34). No rpm error. Detectors run at about 18 fps on a V100, so they are heavy. | **GPL-3.0**, pretrained weights, TTHQ dataset, synthetic data (about 4 GB), MuJoCo generator. |
| **TT4D, 2026** ([arXiv 2605.01234](https://arxiv.org/abs/2605.01234)) | Monocular broadcast video at 25 fps or more. Per-frame 3D spin vector. Classes: top / back / side-left / side-right / no-spin (no-spin means ≤5 Hz). Explicit serve handling: toss and two bounces. | Synthetic spin-vector error about **16-17 Hz**. Real top/back macro-F1 **1.00** (clean) to 0.88 (half fps plus missing detections) on TTST. More than 140 h of reconstructed play. | Dataset "to be released upon acceptance". No code link. |
| **Calandre et al., CBMI 2021** ([pdf](https://pageperso.univ-lr.fr/laurent.mascarilla/pdf/CBMI2021.pdf)) | **Single camera at 240 fps** (very close to ours). A CNN on 5 stacked 64x64 crops estimates ball size, which gives depth. An ODE trajectory fit includes the bounce. | Synthetic: **rotation error 2.7 rps overall** (3.9 rps topspin, 1.5 rps push), speed error 0.03 m/s. Real data had no spin ground truth. Stroke classification from (speed, spin) reached 91.8%. Fit took 27 s per stroke. | Paper only |
| **Tebbe et al. 2020** (above) | Stereo at 380 fps, Magnus polynomial fit. | 88.2% spin-type classification, the best of their methods. | Paper |
| **Spin-aware quadruped table tennis, 2025** ([arXiv 2510.08754](https://arxiv.org/abs/2510.08754)) | Stereo 1400x1080 at 165 fps. Least-squares Magnus fit plus a **small residual NN trained on 650 real trajectories**. | Spin error converges **below 55 rad/s (about 8.8 rps, about 525 rpm)** within 150 ms. The residual NN raised R² from **0.42 to 0.70**. | Paper |
| **SPIN dataset (Google), 2019** ([arXiv 1912.06640](https://arxiv.org/abs/1912.06640)) | Stereo 1024x1280 at 150 fps, 53 h. | Spin labels came only from trajectory: Δv at the bounce plus fitted vertical acceleration. Three clean clusters appeared (no-spin, light topspin, heavy topspin). | Released (link not verified) |
| Achterhold et al. 2023, gray-box vs black-box ([arXiv 2305.15189](https://arxiv.org/abs/2305.15189)) | Learned physics with spin and impacts. | Spin from positions alone is hard. A learned prior on the launch spin helps "drastically". | Code likely |

**Assessment for our setup. This is the strongest direction.**
- We already have:
  - a calibrated table,
  - 240 fps tracking,
  - bounce detection, and
  - **ground-truth rpm and axis from marked balls**.
- Marked and unmarked balls fly identically, so **every marked-ball serve is a free training label for a trajectory-only model that then runs on plain balls**. Appearance methods do not have this property.
- Features to compute:
  - pre- and post-bounce velocity (horizontal and vertical),
  - rebound angle,
  - residual vertical acceleration versus g on each segment (Magnus),
  - lateral curvature and kick,
  - serve speed,
  - first-bounce position,
  - toss and contact timing.
- Model: gradient-boosted trees or a small MLP or 1D-CNN.
  - Start with a physics fit (Nakashima-style bounce plus Magnus/drag ODE) and add a learned residual, the gray-box approach.
  - It trains in minutes, runs in under 1 ms on the phone, and needs no cloud.
- Optional: use the Kienzle uplifting network (GPL-3.0, so cloud or reimplementation only) as a pretrained prior and fine-tune it at 240 fps.
- Expected accuracy (my estimate, extrapolated from 88-97% top/back in the literature and R² of about 0.7 in the stereo study):
  - **Top / back / no-spin: about 85-95%**.
  - **rpm: about ±15-30%** for top/back components after a few hundred to a thousand labelled serves.
  - **Sidespin magnitude and axis: weak** from a side camera. A coarse left/right sign is plausible from the bounce kick and lateral curvature, provided depth is decent.
- Data needed: a few hundred serves per spin class with marked-ball labels, across several servers. Users of the marked-ball mode generate this data automatically.
- Risks:
  - Depth error from one camera.
  - Short serves give only one short segment after the first bounce before the second bounce.
  - Bounce coefficients vary by table. A per-table calibration from a few no-spin drops may help.

---

## 3. Spin / serve type from the server's racket and arm motion

| Work | Input | Result | Public? |
|---|---|---|---|
| **SPIN dataset baselines (Google), 2019** ([arXiv 1912.06640](https://arxiv.org/abs/1912.06640)) | 150 fps video. Predicts the trajectory-derived spin cluster **from frames before and around the hit**. | Spin cluster accuracy: **61.6% video only, 68.2% with a pose task, 72.8% with pose plus ball** (3 clusters, pro and amateur players). | Dataset offered |
| **TT4D 2026** | 4DHumans meshes. | States that current pose estimators **do not reliably capture hand or racket orientation**, so they infer racket pose from the ball trajectory instead. | — |
| **RacketVision, AAAI 2026** ([arXiv 2511.17045](https://arxiv.org/abs/2511.17045), [GitHub](https://github.com/OrcustD/RacketVision), HF dataset) | Racket bounding box plus **5 racket keypoints**, ball tracks. Table tennis, tennis and badminton (1,672 clips, 435k frames). | Naively adding racket pose made trajectory prediction **worse**. Cross-attention fusion was needed. No spin labels. | Code and data public |
| Kulkarni and Shenoy 2021 ([arXiv 2104.09907](https://arxiv.org/abs/2104.09907)) | 2D pose, 11 stroke classes. | 99.4% stroke-type accuracy. Stroke type is not spin. | — |
| Racket IMU: Blank, Groh, Eskofier 2017 ([ACM](https://dl.acm.org/doi/10.1145/3123021.3123040)) | Racket-mounted IMU, 8 subjects. | Spin estimation accuracy **73.5% (topspin strokes)** and **75.0% (backspin)**. Speed 79-87%. | No |
| Piezo racket array 2025 ([PLOS One](https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0333735)) | 5x5 piezo sensors under the rubber. | More than 70% spin accuracy. | No |

**Assessment.**
- Serves are designed to **disguise** spin: the contact lasts about 1 ms, and fake motions are common. The best published video-plus-pose result is about 73%.
- At 240 fps the racket is visible and blurred. A racket-keypoint model trained on RacketVision could give racket angle and speed near contact.
- Useful as an **auxiliary feature** (for example, open versus closed face, brushing direction, racket speed) fused into the trajectory model. Weak as a standalone rpm estimator.
- On-device pose (for example MediaPipe or MoveNet) is feasible offline on a Pixel 8. Racket keypoints need a custom model.
- My estimate: about 65-80% serve-type accuracy alone, and a few points of gain when fused.

---

## 4. Other signals, products and datasets

- **Sound** (Gossard et al., IEEE STAR 2025, [arXiv 2409.11760](https://arxiv.org/abs/2409.11760)):
  - Racket-impact audio, energy-peak detection plus a CNN.
  - Detects bounce time (ms accuracy), surface (racket, table, floor) and **whether spin was applied**.
  - 3,396 samples across 10 rackets. Dataset and code are said to be public.
  - I could not retrieve accuracy figures (unverified).
  - The phone microphone is free, so this is a cheap extra feature for spin versus no-spin, and for precise contact and bounce timing.
- **Commercial: Spinsight** (ESN Digital GmbH; [App Store](https://apps.apple.com/us/app/spinsight/id6741476615), [help center](https://intercom.help/spinsight/en/articles/12638664-does-the-measurement-also-work-with-normal-table-tennis-balls)):
  - The **closest competitor**: a phone on a tripod measures spin (up to 150 rps), axis and speed in real time.
  - It **requires its own dot-pattern balls**, the same approach as ours.
  - The vendor says spin does **not** work on normal balls. The stamp occasionally yields unreliable values.
  - No published accuracy figures (marketing only). iOS app rating is 3.4/5 from 5 ratings.
- **openspin** ([GitHub](https://github.com/JamesQFreeman/openspin)):
  - Open-source iPhone 240 fps dotted-ball spin, using "streak-field" arcs fitted within a single frame.
  - Self-reported ±30% on a real drill. Real ground truth is still to-do.
  - Peer project, unverified.
- **PingPi / SportArc**, SPEEDUP: phone video analysis of placement and speed. **No spin.**
- **WTT and broadcasts:** no evidence of an official broadcast spin product. T-League published serve spin using Tamaki's multi-camera logo system.
- **Smart rackets:** no shipping table-tennis spin-sensor product found. Research prototypes reach about 70-75% (see above). Tennis has Babolat and Sony, which do not apply here.
- **Datasets:**
  - TTHQ and TTST plus synthetic trajectories (Kienzle, public).
  - TT4D (to be released).
  - SPIN (150 fps stereo, spin clusters).
  - SpinDOE trajectories with spin ([GitHub](https://github.com/cogsys-tuebingen/spindoe), dot-pattern method: **<1% spin error, 2.4° orientation error, up to 175 rps at 350 fps**).
  - "1000 Rallies" event-camera dataset with 200 fps 14-camera pseudo-ground-truth spin ([arXiv 2606.25620](https://arxiv.org/abs/2606.25620); release unconfirmed).
  - RacketVision (racket pose).
  - Gossard sound dataset.
  - None of these is a phone-at-240-fps serve dataset with rpm labels. **Our own marked-ball logs would be unique.**
- **Non-AI lever:** pre-printed patterned balls (Spinsight balls, or bulk two-tone or dotted training balls) remove the hand-marking step. This might solve the "few marked balls" problem faster than any model. I did not research availability or pricing.

---

## 5. Ranked recommendation

1. **Learned trajectory + bounce model, trained on our own marked-ball labels (do first).**
   - What: log every marked-ball serve (track, bounce events, ground-truth rpm and axis). Build a physics fit (bounce model plus Magnus/drag ODE) with a learned residual or GBM on physics features. Ship it as the "plain ball" mode with a confidence score.
   - Effort: about 1-2 weeks once labels exist. On-device, under 1 ms.
   - Data: 300-1,000+ labelled serves across servers and spin types. Mark a few balls and let users generate the data.
   - Expected (my estimate): **top / back / no-spin about 85-95%, rpm about ±15-30%**. Sidespin only coarse.
   - Optional benchmark: Kienzle's GPL code and weights on a server (license blocks bundling it in the app).
2. **Opportunistic logo refinement on plain balls.**
   - What: detect the logo blob in the ball crop. When it is tracked across 3 or more frames, solve rotation constrained by the trajectory prior (Tamaki-style fusion). Otherwise return the result of step 1.
   - Effort: 2-4 weeks, including a synthetic-render set at 30 px with blur and rolling shutter for a small crop CNN. Validate on balls carrying a small mark on the far hemisphere, or on manual counts.
   - Expected (my estimate): a reading on perhaps 30-60% of serves at about 10-20% error. Better if the camera is closer, so the ball is 50+ px.
3. **Racket / pose + audio as extra features for serve-type classification.**
   - What: on-device body pose plus a racket-keypoint model (fine-tuned from RacketVision), plus impact-sound features. Feed them into model 1.
   - Effort: 3-6 weeks.
   - Expected: about 65-80% alone, a modest gain fused. Most useful for no-spin versus heavy spin and for detecting deception patterns, less for rpm.

In parallel, consider pre-printed patterned balls as the cheapest fix for the ball-pickup pain.
