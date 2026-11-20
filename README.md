# 盲区回头 Shoulder Check

> **v0.2.0 · 研发版 · 模拟器自测，真机待验**（Development build · emulator self-tested, headset check pending）

[中文](#中文) · [English](#english)

---

## 中文

坐在驾驶座上，一轮几秒：要变道、并线、起步或开车门时，先看镜子、再回头，没车就点「走」。有车藏在盲区里，你一走它就按喇叭，慢动作回放给你看它藏在哪。一局 8 轮，算分、连击、刷最好成绩。PICO OS 6，Full Space。

- **4 关 × 4 个现实局面**：白天车少 → 白天车多 → 快速路 → 傍晚；变道 · 路边起步 · 匝道并线 · 开车门
- **陷阱轮**：镜子里一片空，盲区里正好藏着一辆车，只有回头才看得见
- **点一下就行**：3D「走」按钮、车门内把手、「暂停」都能用手柄射线、手势射线或眼手点一下；手柄 A 键也能走
- **计分**：安全 / 等对了 / 侥幸 / 险情 / 超时；看镜 + 回头 + 快 = 三星，连击 ×1.5 / ×2；今日挑战每天一套 8 轮
- **判定宽松但要真回头**：回头阈值永远比同侧后视镜远 10° 以上，差一点且确实看得见时才自动放宽
- 默认「自车慢慢开」（36 km/h），晕车可开舒适模式；中英双语，不联网、不需要账号

## English

Sit in the driver's seat. Each round takes seconds: change lanes, merge, pull out or open your door. Check the mirror,
look over your shoulder, and tap Go when it's clear. A car hiding in your blind spot honks when you go, and a slow-motion
replay shows where it was. 8 rounds a game, with points, combos and a best score. PICO OS 6, Full Space.

- 4 levels × 4 real situations; trap rounds where only a shoulder check shows the car
- One tap on the 3D Go button, the door handle or Pause with the controller ray, the hand ray or eye-hand; the A key goes too
- Lenient but honest judging: the shoulder threshold is always 10°+ past the same-side mirror
- Your car rolls at 36 km/h by default; comfort mode keeps it still. Chinese / English, offline, no account

## Build

See `AGENTS.md` (build, debug broadcast, screenshot list).
