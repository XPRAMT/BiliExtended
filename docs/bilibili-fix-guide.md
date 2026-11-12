# Bilibili Android 高幀率／倍速播放卡頓：修復指引

給 Bilibili Android 播放器（IJK）工程師的修復指引。內容全部來自 Bili Extended 這個 LSPosed 模組在官方 App（9.13.0、9.17.0、9.18.0）上的執行期實驗與 Perfetto trace：每一條都寫「現象 → 怎麼發現 → 根因 → 怎麼修」，所有修法都可以一對一搬回 IJK 與播放器原始碼。模組的實作在 `app/src/main/cpp/native_refresh_cap.cpp`、`cadence_lock.h` 與 `app/src/main/java/dev/xpramt/bilisurfacebridge/`。

**一句話結論**：照 Media3（ExoPlayer）的方式釋放影片幀——提前約 30–45 ms、帶目標上屏時間戳，並把時間戳帶到真正送給 SurfaceFlinger 的那個 buffer 上；倍速時以「節奏鎖定」挑幀；另外有一個與排程無關、但會讓畫面停 80–144 ms 的問題：播放器效能取樣佔住了傳遞畫面通知的執行緒（B.5）。

測試環境：Nubia NX809J（Snapdragon 8 Elite Gen 5，Android 16，面板實測 120.28 Hz）、小米平板 6 Pro（Snapdragon 8+ Gen 1，HyperOS／Android 15，120 Hz）、Sony Xperia XZ Premium（Android 9）。測試片以《屏幕測試 (UFO,4K)》的「UFO Test 120」「UFO Test 60fps」（4K AVC）為主，另有 1080p24、4K30、4K60 AV1 與 1080p30 HEVC 的片。

---

## A. 基礎：為什麼 IJK 在高幀率會卡

### A.1 物理下限（拍頻）

內容幀率 × 倍速和面板刷新率不是整數比時，每隔一段時間必然多停或少停一格，任何播放器都消除不了。例如 120 fps 內容在 120.285 Hz 面板上，每 1／(120.285 − 120) ≈ 3.5 秒必然有一幀多停一格。

判斷一個停頓是不是物理的：間隔應為 `1 / |刷新率 − 內容幀率 × 倍速|` 秒，而且很規律。不規則、成對（相隔 < 0.1 s）的停頓都不是物理的。

### A.2 IJK 不提前釋放、也不帶有效時間戳（最大宗）

- **現象**：120 fps 1x 原樣每 30 秒約 300 次多停一格（物理下限 8.5）；60 fps × 2 約 532 次（下限 26.6）。
- **怎麼發現**：SurfaceFlinger trace 的 `latchBuffer <layer>` 下 `desiredPresentTime` → `presentIn -1042580352 ms`：IJK 送進去的時間戳是媒體 PTS（幾秒），等於「立刻顯示」。對照同一台手機上 Media3 1.8.1 播同一個檔案只有 8 次。
- **根因**：IJK 的 `video_refresh` 在「現在就該顯示」的那一刻呼叫 `releaseOutputBuffer(idx, true)`，buffer 進 SurfaceFlinger 的相位由 IJK 醒來的時間決定。120.000 fps 對 120.285 Hz 時這個相位每約 3.5 s 掃過整個週期，接近 SurfaceFlinger latch 邊界時 ±1 ms 的抖動就讓連續幾幀在「趕上／沒趕上」之間反覆：原樣直連 Surface 30 秒 143 個缺幀裡，136 個是「buffer 已 Queue，但在 latch 前被下一幀取代」。
- **修法**：照 Media3 1.8.1（原始碼對照見 [media3-reference.md](media3-reference.md)）：
  1. 距離上屏不到約 50 ms 才交出（`VideoFrameReleaseControl.MAX_EARLY_US_THRESHOLD`）；
  2. 時間戳用 `FixedFrameRateEstimator` 在**內容時間**上估固定幀長，平滑後偏差 ≤ 20 ms 才採用，對齊**最近**的 VSync 再減 80% 週期；
  3. `releaseOutputBuffer(index, releaseTimeNs)`，SurfaceFlinger 會依時間戳扣住 buffer 到該上屏的那一格；
  4. 釋放時間落在前一幀已佔用的 VSync 上（內容比螢幕快）就 `releaseOutputBuffer(index, false)` 跳過；
  5. 晚 30 ms 以上才丟幀；晚 30 ms 且 100 ms 沒有畫面就強制顯示。
- **結果**（NX809J、Surface external，每 30 秒多停格數）：120 fps 1x 約 300 → 9～13（下限 8.5）；60 fps 1x 31 → 13；60 fps × 2 532 → 26.4（下限 26.6）。

### A.3 三種渲染模式在 MediaCodec 之後都還有一次 GL 重繪

```
Surface（直連）    MediaCodec ─▶ ijkservice 內的 SurfaceTexture ─GL(ijk_ff_vout)─▶ SurfaceView ─▶ SF
Surface external   MediaCodec ─▶ 主程序 SurfaceTexture ─GL(ijk_ext_render, EGL10)─▶ SurfaceView ─▶ SF
Texture（預設）    MediaCodec ─▶ 主程序 SurfaceTexture ─GL(ijk_ext_render)─▶ TextureView ─HWUI─▶ App 視窗 ─▶ SF
```

- **根因**：`releaseOutputBuffer(idx, ts)` 的時間戳只掛在 MediaCodec 輸出的 buffer 上，SurfaceFlinger 收到的卻是後面 GL 重繪的輸出。
- **修法**：在那次 `eglSwapBuffers` 前用 `eglPresentationTimeANDROID(display, surface, ts)` 掛上時間戳。Surface external：`updateTexImage()` 後以 `SurfaceTexture.getTimestamp()` 讀回時間戳。`IjkEglCore` 用的是 EGL10，沒有 presentation time API；swap 當下該 surface 是 current，可以用 `EGL14.eglGetCurrentDisplay()`／`eglGetCurrentSurface(EGL_DRAW)` 取得同一個原生物件呼叫 `EGLExt.eglPresentationTimeANDROID`。
- **建議預設改用 Surface external**：保留 transform 與截圖，少一次 GPU 合成、少一格延遲，而且能完整套用時間戳。Texture 模式最後送給 SurfaceFlinger 的是 HWUI 合成的整個視窗，沒有逐幀時間戳可掛；120 fps 勉強可以，60 fps 在 120 Hz 的 App VSync 下無法穩定對齊。

### A.4 圖片佇列只有三格（`video-pictq-size=3`）

- **現象**：每 5 s 左右硬解碼器停 16–30 ms（`queue_bak avpackets is full 350`）時就掉幀。
- **根因**：提前釋放需要手上有好幾張已解碼的畫面；MediaCodec 給 15 個輸出 slot，IJK 只持有 3 個。
- **修法**：`video-pictq-size = 12`（AVOption 範圍 [3, 16]），或依 fps 設 `max(3, fps / 10)`。注意：9.13 在 Java 設完之後 native 又設回 3，而且**線上詳情頁根本不呼叫 `IjkMediaPlayer.setOption`**，要改 `ffp_create()` 的預設值。

### A.5 管線執行緒全是 nice 0

`ijk_ff_vout`、`ijk_ff_videodec`、`ijk_ext_render` 常被 App 自己的 `DefaultDispatch`、GC 和系統程序搶 2–6 ms。三條執行緒設 `setpriority(-10)`（介於 DISPLAY 與 AUDIO 之間，不跟音訊搶）。

---

## B. 照抄 Media3 時會踩的坑（每一條都實測踩過）

Media3 本身沒問題；這些都來自「IJK 的結構和 Media3 不同」。Media3 完全沒有、本專案另外加的機制（相位鎖定、節奏鎖定、交出幀門檻、保活等）整理在 [beyond-media3.md](beyond-media3.md)。修正前同一版本、同一台手機，30 秒缺幀會在 11 到 921 之間隨機跳動。

### B.1 提前量要鎖在 IJK 的實際送出時間上

- **現象**：每次開播的結果隨機落在三種狀態：時間戳已過期（等於沒帶）、時間戳生效但 SurfaceFlinger latch 時 fence 還沒 signal（`fence unsignaled`，同一格兩幀、丟一幀，最高每秒 28 次）、或只剩拍頻。
- **怎麼發現**：逐幀記錄「時間戳 − 實際送出時間」，發現它是每次開播隨機決定的常數（−25、+11～17、+33～43 ms）。
- **根因**：時間戳來自只在開播時錨定一次的 PTS 時間線，而 IJK 照自己的節奏送幀。
- **修法**：每一幀量 `stamp − now`，連續 8 幀偏離目標（33 ms，取整到 VSync、至少 2 格）超過 0.75 格，就整格挪動交給 SurfaceFlinger 的時間戳，兩個方向都可以。**只挪時間戳，不挪 IJK 的呼叫時間**：挪呼叫時間 IJK 會跟著晚送，提前量永遠追不上。

### B.2 外部渲染器必須「幀一到就畫」

- **根因**：`IjkExternalRenderThread` 在 `onFrameAvailable` 才 `postFrameCallback`，等下一個 App VSync 才畫，而且 `updateTexImage()` 只保留最新一幀；幀抵達相位落在 App VSync 前 1–2 ms 時常趕不上，兩幀擠進下一次、丟一幀（鎖在 1,1,1,1,2 節奏，約 98 fps）。
- **修法**：改為幀一到立刻在 render 執行緒畫（`postAtFrontOfQueue`）；每一幀已帶時間戳，SurfaceFlinger 自己會對齊。

### B.3 `IjkExternalRenderThread` 有兩個 SurfaceTexture

`updateAllTexImage()` 每次更新硬解（`mAmcSurfaceTexture`）與軟解（`mAvSurfaceTexture`）兩個 SurfaceTexture，閒置的那個帶很舊的時間戳。取時間戳時只看承載影片的那一個，否則偶爾那次 swap 不帶時間戳、早一格顯示（每 30 秒 4–6 次）。

### B.4 `compute_target_delay` 回傳 0 會讓 IJK 卡住

IJK 只在 `delay > 0 && time − frame_timer > AV_SYNC_THRESHOLD_MAX` 時重設 `frame_timer`。排程回傳 0 時 IJK 不前進也不重設，之後每一幀都「已過期」、再回傳 0，變成以解碼器速度自由跑（約 100 fps，由 0.5x 切回 1x 觸發）。修法：已落後 100 ms 以上卻算出 0 時回傳 1 µs。

### B.5 【9.18.0】播放器效能取樣佔住畫面通知的執行緒，畫面停 80–144 ms

- **現象**：Surface external 模式下，每次開始播放約 12 秒處，以及之後約每 30 秒，畫面停住 80–144 ms（120 Hz 下 10–17 格），解碼器與排程都正常。
- **怎麼發現**：
  1. trace 裡 `ijk_ext_render`（外部渲染 GL 執行緒）在空檔期間一直睡著，這段時間 5–6 幀已經釋放、也進了 SurfaceTexture；
  2. 平時叫醒它的是主程序的 `ijk_java_player`；空檔期間 `ijk_java_player` 被 ijkservice 的 binder 執行緒叫醒了 5 次、system_server 2 次（它在做同步跨程序呼叫），ijkservice 那邊花了約 32 ms CPU；
  3. 在 `Handler.dispatchMessage` 與 `BinderProxy.transact` 上記錄 `ijk_java_player` 上的長工作與呼叫堆疊，抓到 `IjkMediaPlayer$SomeWorkHandler` 的訊息 35：`TrySamplePerformance` → `IjkPerformanceManager.sampleBatteryInfo`（讀電池）＋`IIjkMediaPlayer$Stub$Proxy.SamplePerformance`（同步呼叫 ijkservice），一次 71–126 ms。
- **根因**：SurfaceTexture 的 frame-available 回呼和 `SomeWorkHandler` 跑在同一個 Looper（`ijk_java_player`）。效能取樣在這個 Looper 上做同步 binder 呼叫時，後面排隊的畫面通知全被擋住，GL 執行緒沒被叫醒，畫面就停。取樣由 `startPerformanceSampling`（`prepareAsync` 之後）開始，訊息 35 每 30 秒自己重排一次。
- **取樣的用途**（9.18.0 反編譯）：結果只累加進 `IjkMediaPlayerItem` 的效能統計；item 釋放時 `reportPerformanceForSoftStrategy` 把 ijkservice 平均 CPU 以 `FFP_ITEM_PROP_INT64_NOTIFY_CPU_USAGE` 交給 native（只在支援 AV1／H.266 軟解時）。
- **修法（官方）**：任選其一——(1) 效能取樣改在獨立執行緒做，不要和 frame-available 共用 Looper；(2) SurfaceTexture 的 `setOnFrameAvailableListener` 指定專用的 Handler；(3) `SamplePerformance` 改成非同步（oneway）呼叫。模組的做法：預設不取樣、不累加、也不送 CPU 通知（只取樣不送會送出 0%，對軟解策略是假資料），訊息 35 的迴圈與每 10 次的 `AbrParamsInterface.setEnableHighExperience()` 保留。
- **結果**：冷啟動第 1 輪原本 6／6 出現 84–144 ms 空檔；修正後最長空檔 ≤ 30 ms。

### B.6 時間線落後要重新對齊

PTS 時間線原本只在偏離 60 格（約 500 ms）以上才重新對齊；某次開播卡在落後 200–370 ms，所有時間戳都過期，30 秒 921 個缺幀。修法：連續 8 幀落後實際刷新 50 ms 以上就重新對齊，並以「現在」為下限。

### B.7 VSync 取樣資料「比現在新」不等於過期（競態）

- **現象**：每輪 14–43 次排程放棄帶時間戳，其中 1–8 次變成沒帶時間戳的幀，SurfaceFlinger 提早一格顯示、丟掉前一張（4 格空檔）。
- **怎麼發現**：在每次放棄時記錄原因與網格年齡，原因全是「網格過舊」，而網格年齡是 −1 µs。
- **根因**：VSync 由另一條執行緒取樣更新；呼叫者先讀 `now`，取樣執行緒才寫入新網格，於是 `now < updated`，被判成過期。
- **修法**：比 `now` 新的網格算新鮮，只有 `now − updated` 超過門檻才算過期。凡是「另一條執行緒更新的時間戳」和「呼叫者的 now」比較時都要這樣處理。

### B.8 倍速

- **倍率要在播放器所在的程序取得**：IJK 跑在 `:ijkservice`，倍速經 AIDL 到 `IjkMediaPlayerClient.setPropertyFloat(10003, rate)`；主程序的 `IjkMediaPlayer.setSpeed` 在那裡不會執行。「2.0X」實際送的是 1.99。
- **IJK 的倍速只靠音畫同步實現**：PTS 步長不縮放，是 `compute_target_delay` 對倍速後音訊時鐘的修正讓影片變速。任何把延遲換成 PTS 步長的改動都會讓影片停在 1x、只有聲音變速。
- **倍速也要排程**：PTS 步長除以倍率照常排程；全交回 IJK 時 60 fps × 2 只剩 109 fps。
- **內容比螢幕快時跳過同格的幀**：120 fps × 2 每秒 238 幀，全部渲染時 SurfaceFlinger 每格拿最新那張，格數雖滿但內容步長忽大忽小。只適用 Surface external：直連 Surface 的 GL 重繪在沒有新幀時會把舊幀不帶時間戳再送一次。
- **起播時樣本不足不要回退到 2.0x**：音訊位置樣本不足 4 筆時用了上限 2.0 的後備倍率，4x 的片起播後前約 0.5 秒被當 2x 排程，影片落後聲音約 130 ms、要 4 秒才追回。修法：樣本不足時直接用已知的實際倍率。

### B.9 稠密倍速（2x 以上）要「節奏鎖定」

- **現象**：120 fps × 2、60 fps × 4、24 fps × 10 等稠密倍速時，穩定段每 8 秒 7–11 次「改留另一幀」，畫面奇偶翻轉（同一個運動忽快忽慢）。
- **根因**：面板 120.4 Hz 與 240 幀／秒內容每秒差約 0.7 幀，排程用「改挑隔壁那幀」吸收這個差，等於相位翻轉。
- **修法**：固定留同一組幀（例如偶數幀），節拍到了就讓同一幀多停一格，相位不變。相位追隨用時間常數 1.5 s 的 EMA（追太快時，IJK 偶爾超前約 230 ms 的單筆音訊位置會讓它連丟數幀）；丟保留幀的門檻 = max(半格 + 3 ms, 晚到允許丟幀的設定值)。中位數與快追試過都變差。結果：穩定段奇偶翻轉 0。

### B.10 晚幀處理（追趕）

1. 釋放時晚超過 30 ms 才跳過（Media3 `shouldDropFrame`）；
2. 降速（含回 1x）後 1.5 s 內不跳：放開長按的瞬間影片管線停約 0.2 s、聲音時鐘照舊速率走，否則會連丟 9–23 幀；
3. 距上次顯示超過 2 格（120 Hz 約 16.7 ms，上限 100 ms）就強制渲染、不再跳過：Media3 `shouldForceRenderOutputBuffer` 是 100 ms，沒有這條時，解碼只比需求快 2% 的情況下（4K60 AV1 × 4）每幀都被跳過、3–8 秒沒有畫面；縮短到 2 格讓停頓後更快恢復（見 [beyond-media3.md](beyond-media3.md) 第 7 節）；
4. 只有「解碼出列 → 釋放」的等待夠長（有已解碼的餘量）時才允許跳過；跳過發生在解碼之後，不會讓解碼變快。

### B.11 幀率估計要在內容時間上做、取整到微秒；對齊 VSync 要有遲滯

- Bilibili 的 PTS 是毫秒精度（120 fps 間隔 8／9 ms 交替），剛好落在 Media3 估計器 1 ms 容差邊上。把除以倍率後的間隔餵給估計器，0.5x 時永遠不同步；即使在內容時間上估，`/1.99 × 1.99` 也會多出幾 ns 超過容差，要取整到微秒。
- `closestVsync()` 的基準每格更新、帶微小抖動，拍頻跨越格線時連續幾幀會在兩格之間來回翻。修法：要比「每幀正常格數」多跳一格時，平滑後的時間必須超過兩格中點 1.5 ms。

### B.12 暫停後恢復，聲音位置平均偏後

- **現象**：從暫停恢復後 0.5x 的畫面比聲音早 0.2–0.4 秒，要 6–12 秒才追回，造成恢復後第 5–8 秒成串的長間隔。
- **根因**：(1) 聲音位置的平滑環在暫停時仍收進「位置不動、時間照走」的樣本，恢復後要約 6 秒才排出去；(2) 節奏鎖定把鎖定後前 30 幀的平均誤差當成正常值，起頭偏差被當成基準。
- **修法**：位置不動就清空平滑環並直接用當下位置；相位基準用 0。

### B.13 丟幀規則

9.13 的 `is_need_drop_frame()` 幾乎每一幀都判定「晚了」（以音訊時鐘判斷，對幾 ms 的遲到就丟，曾連鎖丟掉 40%）。只在幀的時間戳已過去 30 ms 以上才放行；這個規則下 1x 實際丟幀為 0。

---

## C. 平台與硬體相關的坑

### C.1 高通解碼器輸出 fence 會讓硬解失敗（NX809J，Android 16）

- **現象**：設定 `vendor.qti-ext-output-fence.enable`／`fence_type` 後，解碼器每次都退回 FFmpeg 軟體解碼（開 6／6、關 7／7 硬解）。
- **怎麼發現**：整機 logcat 串流：`CCodecConfig` 接受這兩個鍵 → 核心 `msm_vidc: cap_id 0x3f (OUTPUT_TX_FENCE_TYPE) not allowed in state INPUT_STREAMING` → `QC2V4l2Driver: Failed to set V4L2_CID_MPEG_VIDC_OUTPUT_TX_FENCE_TYPE` → `failed to streamon for port:OUT` → 解碼器死亡、IJK 退回軟解。
- **結論**：這支手機的 Codec2 HAL 在輸入開始串流後才設 fence 類型，不能開。解碼器沒宣告 `vendor.qti-ext-output-sw-fence-enable.value` 時不要只憑前兩個鍵就啟用；啟用後要確認解碼器仍是硬體。

### C.2 HDR 影片走外部渲染器會變成 SDR

外部渲染器每一幀都用 GL 重畫進 8-bit sRGB，HDR 被色調映射成 SDR，系統的 HDR 模式也不會觸發。HDR 影片（「HDR 真彩」「杜比視界」）應改用直連 Surface，讓畫面連同 HDR 資訊直接送到顯示器。

### C.3 解碼器吞吐量上限

- 小米平板 6 Pro（SM8475）的 AVC 解碼器宣告 `blocks-per-second` 最多 1,958,400（4K 約 60 幀）、效能點 3840x2160@60；實際 4K 每秒 120 幀可以穩定解（領先上屏 15–32 ms、零晚幀），每秒 180 幀時平均出幀勉強跟上，但幾乎每一幀出來時都已晚於上屏時間，畫面節奏不均。**4K 每秒 180 幀超過這台的硬體能力**（4K120 的 1.5 倍、4K60 的 3 倍）。
- NX809J 的 4K30 AV1 實測約每秒 247 幀封頂。
- 建議：高倍速時依「解析度 × 幀率 × 倍速」與解碼器能力決定可選的倍速，或在超出時告知使用者。

### C.4 HyperOS 依解碼器宣告的幀率鎖定合成刷新率

小米 HyperOS 播放影片時，SurfaceFlinger 依解碼器宣告的影片幀率鎖定合成刷新率（counter `VTFps`），不看倍速：60 fps × 2 仍只合成到 60 Hz。告訴解碼器 `frame-rate`／`operating-rate` = 幀率 × 倍速、或改用同一時間戳都無效；`Surface.setFrameRate()` 從 ijkservice 呼叫回傳 `-38 Function not implemented`（跨程序 producer），內容幀率提示到不了 SurfaceFlinger。另外 `dumpsys display` 在這台顯示的 60 Hz 是錯的，刷新率要從 trace 的 VSYNC 計數器量。

### C.5 系統降頻會讓 SurfaceFlinger 趕不上提交

平板上 SurfaceFlinger、composer 與 `ijk_ext_render` 都跑在 cpu4–6；系統把這一群降頻時，SurfaceFlinger 同樣的合成工作慢約 13–47%，提交在 VSync 前不到約 3 ms 才開始就趕不上該格，出現 1／3 格交替（frametimeline 標成 `Display HAL`）。這是系統行為，播放器無法修；量測時要固定 CPU 頻率或記錄實際時脈來排除。

---

## D. 其他順手看到的問題

- 線上切畫質時偶發硬解退回軟解：1080p30 → 4K120 切換時新的解碼器解了 180 幀都 `releaseOutputBuffer(idx, false)` 追進度，隨後 `hw_decode_fallback_enable:1`，整段改軟解。
- 建 MediaCodec 時 `frame-rate = 1`，應填實際 fps。
- 每次建解碼器都印 `SDL_AMediaFormatJava_getInt32: failed, name=stride / slice-height` 的 NPE。
- 播放清單頁偶發 `BufferQueue has been abandoned` → 硬解啟動失敗退回軟解、黑屏。
- seek／影片循環後約有 0.5–0.7 s 的解碼停頓，2x 要 2–4 秒才恢復。

---

## E. 量測方法與量測上的坑

修播放器之前，先要量得準。下面每一條都讓我們一度做出錯誤結論。

| 坑 | 現象 | 正確做法 |
|---|---|---|
| Perfetto 緩衝區太小 | 64 MB 時每輪只剩 7–11 秒資料、SurfaceFlinger 逐幀資料遺失；100 MB 時 4K120 1x 在 13.5 秒被截斷 | 緩衝區 200 MB；每輪檢查錄製涵蓋（VSYNC 計數器的時間範圍）≥ 錄製長度 × 0.95；`trace_bounds` 不能用（含錄製前的舊核心事件） |
| 把播放器自己的事件當上屏時間 | 釋放事件與 SurfaceFlinger 的收幀配對錯誤，製造假的長間隔（每輪多 9–30 個） | 長／短間隔只用 SurfaceFlinger 影片層的 `PresentFenceSignaled` 時間；配對只用來看內容時間戳的步長 |
| 釋放事件在 `releaseOutputBuffer` 之後才寫 | 偶爾晚於 SurfaceFlinger 收到，每秒 120 幀時分不出是哪一張 | 事件在呼叫釋放之前寫 |
| 平板外部渲染的延遲分兩群 | 「最接近典型延遲」的配對規則一路錯位（只配到 82%） | 按順序的序列對齊（配對數最多、延遲在合理範圍） |
| 寫死 120 Hz | NX809J 實測 120.28 Hz，0.25 倍的 24 fps（R/F = 20.05）被誤判成非整數比例 | 面板週期從 trace 的 `VSYNC-app`／`VSYNC-sf` 計數器量；整數倍判斷用相對誤差（< 1%） |
| 假設播放速度剛好等於請求值 | 0.25 倍實際約 0.248 倍，必然多出的長間隔被當成錯誤 | 預期的多停／少停格數用「內容時間戳對上屏時間」的回歸斜率計算；不要用音訊位置取樣的斜率（雜訊大，1% 的誤差在 120 Hz 下就是 11 秒內約 13 個間隔） |
| 把開頭的恢復過渡算進判定 | 每輪開頭從暫停恢復時多 1–3 個間隔 | 以起播 5 秒後的穩定段判定 |
| 系統壓頻混在結果裡 | 同一組條件時好時壞 | 每輪錄製前檢查 CPU 頻率上限與硬體限頻（`scaling_max_freq`、`dcvsh_freq_limit`），被壓就等 30 秒再查；並記錄錄製期間的實際時脈（`cpuinfo_cur_freq`） |
| 評分器一直改 | 每換一種條件就冒出新的評分錯誤 | 準備一組已知答案的 trace（截斷、正常、慢速、120 幀、平板、手機），評分器每次修改都要全部通過才使用（本專案用 12 條已知答案的回歸集） |

量測流程：root Perfetto 錄 15 秒（SurfaceFlinger frame、frametimeline、process stats、atrace `gfx`/`view`/`sched`），播放器事件以 atrace instant 寫入同一份 trace（SurfaceFlinger 是 MONOTONIC、atrace 是 BOOTTIME，用 `clock_snapshot` 換算）。頁面有兩個 SurfaceView 圖層時只用編號較小的影片圖層。

---

## F. 走過的錯路（避免重蹈）

- 「SurfaceFlinger 不遵守 `desiredPresentTime`」是錯的：時間戳掛在 MediaCodec 的 buffer 上，根本沒到 SurfaceFlinger。
- 精準控制呼叫時間（提前 2.2 格、依 FrameTimeline deadline 推算、查 compositor timing、上屏回饋）在 120 fps 接近下限，但 60 fps 做不到、也無法跨裝置；`GET_COMPOSITOR_TIMING` 在 NX809J 回傳錯誤值。
- Java 層扣住 `releaseOutputBuffer` 到正確時間再放：解碼器少一個 buffer，更卡。
- 縮小圖片佇列：問題是太淺不是太深。
- 對 SurfaceView 投 120 Hz（`CHANGE_FRAME_RATE_ALWAYS`）：SurfaceFlinger 每幀重設投票，每秒切換顯示模式約 88 次，實際只剩 87 Hz。
- 把外部渲染的送出時間對齊主程序 App VSync：相位散成好幾群，更差。
- 用 `EGL_ANDROID_get_frame_timestamps` 的 RENDERING_COMPLETE 當回饋：它會跟著要求的 present time 移動。
- 把呼叫時間跟著時間戳一起往後挪（1x）：IJK 跟著晚送，提前量追不上（B.1）。
- 全面封鎖 IJK 丟幀：在 B.13 的規則下實際丟幀已是 0，封鎖沒有作用。
- 把 GPU 頻率一起拉滿來排除系統降頻：結果和不拉一樣差，只拉 CPU 才有改善（推測 GPU 吃掉了功耗預算，未證實）。

---

## G. 建議的修復順序

| 順序 | 改動 | 位置 |
|---|---|---|
| 1 | 效能取樣移出 `ijk_java_player`，或 frame-available 用專用 Handler（B.5） | `IjkMediaPlayer$SomeWorkHandler`、`IjkExternalRenderThread` |
| 2 | 預設渲染模式改 Surface external；HDR 用直連 Surface（A.3、C.2） | `VideoRenderLayerFactory` |
| 3 | `video-pictq-size` 依 fps 設（120 fps → 12）（A.4） | `ffp_create()` 預設值 |
| 4 | refresh 迴圈照 Media3 排程，含 B.1、B.4、B.6、B.7、B.11（A.2） | `ff_ffplay.c video_refresh` |
| 5 | 時間戳掛到 GL 輸出；外部渲染器幀一到就畫、只讀硬解 SurfaceTexture 的時間戳（A.3、B.2、B.3） | `ijk_ff_vout`、`IjkExternalRenderThread` |
| 6 | 倍速：在 ijkservice 讀倍率、倍速也排程、同格跳過、稠密倍速節奏鎖定、晚幀處理（B.8–B.10） | `video_refresh`、release 路徑 |
| 7 | 暫停恢復時清空聲音位置平滑（B.12） | 音訊位置估計 |
| 8 | 丟幀門檻改為晚 30 ms（B.13） | `is_need_drop_frame` |
| 9 | 三條管線執行緒 nice −10（A.5） | 執行緒建立處 |
| 10 | 高倍速依解碼器能力限制可選倍速（C.3） | 倍速選單 |
