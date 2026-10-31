# ChessBot Assist

Bot cờ vua chạy **Stockfish ngay trên điện thoại**, mặc định Stockfish 16 có sẵn; có thể tải và chọn phiên bản chính thức khác ngay trong ứng dụng. App tự nhận diện bàn cờ của app khác qua ảnh chụp màn hình và tự chạm / kéo để đi quân. Điều khiển bằng một **nút nổi** nhỏ: ▶ để bắt đầu, ⏸ để dừng.

- **Android 8.0 – 16** (minSdk 26, targetSdk 28)
- Hỗ trợ cả **arm64-v8a** và **armeabi-v7a**
- **Chơi offline**: engine và xử lý bàn cờ chạy cục bộ; chỉ cần mạng nếu muốn lấy danh sách hoặc tải thêm phiên bản Stockfish
- **Lưu ý tương thích:** targetSdk 28 là cần thiết để Android 10+ cho phép chạy engine tải vào vùng riêng của app; Android có thể cảnh báo ứng dụng cũ và bản build này không phù hợp phát hành qua Google Play.
- **Không đọc nội dung app khác qua Trợ năng**: quyền Trợ năng chỉ dùng để gửi thao tác chạm / kéo; bàn cờ được đọc từ ảnh chụp màn hình do bạn cấp quyền

APK sau khi build: `build/ChessBot-Assist-v1.5.8.apk`.
SHA-256: `3b56bf3b805b121d21cc1f0d177f9237828973e19df7c7bad3b000c75f37d31d`

**Bản 1.5.7:** quay về ngưỡng nhận diện gốc của v1.5.2 làm lựa chọn đầu tiên, đồng thời thử thêm các ngưỡng tương phản rộng hơn khi cần. Nhật ký nay ghi rõ số ô trống bị nhận nhầm, số quân bị bỏ sót và thông báo lỗi cũng nêu đúng hai loại sai lệch này.

**Bản 1.5.8:** tích hợp Fenshot + ONNX Runtime để nhận diện loại và màu quân từ ảnh bàn cờ. Mô hình chạy offline trên thiết bị; kết quả chỉ được dùng khi đạt ngưỡng tin cậy, nếu không bot quay về bộ nhận diện cũ.

---

## 1. Cài đặt

1. Chép file `ChessBot-Assist-v1.5.8.apk` sang điện thoại (hoặc tải trực tiếp trên máy).
2. Mở file APK. Nếu Android hỏi, bật **“Cài đặt ứng dụng không rõ nguồn gốc”** cho trình duyệt / trình quản lý file.
3. Play Protect có thể cảnh báo vì APK ký bằng khóa phát triển và không phát hành qua Google Play. Chọn **“Vẫn cài đặt”**.
4. Mở **ChessBot Assist**.

APK là **universal**: một file chứa cả hai bộ máy, Android tự chọn đúng ABI. Dung lượng lớn là do mỗi bộ Stockfish tĩnh khoảng 40 MB.

> **Cài từ bản ChessBot ký bằng khóa khác:** Android không thể cập nhật đè. Gỡ bản cũ trước khi cài APK này; việc gỡ ứng dụng sẽ xóa cài đặt và vùng bàn cờ đã lưu.

## 2. Cấp quyền lần đầu

Trong màn hình chính, cả ba dòng quyền cần chuyển sang dấu **✓**:

| Quyền | Cách cấp | Dùng để |
|---|---|---|
| **Nút nổi trên app khác** | bấm **Cấp** → bật “Cho phép hiển thị trên ứng dụng khác” | hiện nút ▶ / ⏸ và khung hiệu chỉnh |
| **Trợ năng** | bấm **Mở** → tìm **ChessBot Assist** → bật | gửi thao tác chạm / kéo vào app cờ vua |
| **Thông báo** (Android 13+) | bấm **Cấp** → Cho phép | hiện thông báo trạng thái của service |

Sau đó bấm **BẬT NÚT NỔI** → hộp thoại quay màn hình của Android hiện ra → chọn **“Bắt đầu ngay”**. Nút nổi ▶ xuất hiện ở mép phải màn hình.

> Android 14+ bắt buộc hỏi lại quyền quay màn hình mỗi lần bật. Đây là giới hạn của hệ thống, không phải lỗi app.

## 3. Chuẩn bị ván cờ

1. Mở app cờ vua bạn muốn dùng (Chess.com, Lichess, Cờ Vua Online, app nội bộ…).
2. Tạo **ván mới từ thế cờ ban đầu**. App chỉ bám được ván bắt đầu từ thế cờ chuẩn; ván đang chơi dở hoặc thế cờ tùy chỉnh sẽ bị từ chối.
3. Trong ChessBot, chọn **Hướng bàn cờ**: Tự động / Trắng ở dưới / Trắng ở trên. Nếu bàn cờ bị lật, chọn thủ công cho chắc.
4. Đặt nút nổi ở chỗ không che bàn cờ. Sau khi hiệu chỉnh, app tự dời nút ra ngoài vùng bàn cờ nếu thấy chồng lên.

Bạn **không cần chọn màu ở đây**: màu quân được hỏi ngay sau khi hiệu chỉnh, và hỏi lại mỗi lần bấm ▶. Mục **Màu quân của bạn (mặc định)** chỉ là gợi ý cho hai chỗ đó.

## 4. Hiệu chỉnh bàn cờ (quan trọng nhất)

Có hai cách mở khung hiệu chỉnh:

- **Giữ nút nổi khoảng 0,5 giây** khi đang ở app cờ vua (cách nhanh nhất), hoặc
- bấm **Hiệu chỉnh bàn cờ** trong ChessBot; app sẽ thu nhỏ ChessBot xuống và mở khung sau 3 giây để bạn kịp chuyển sang app cờ vua.

Trong khung hiệu chỉnh:

1. Kéo **4 góc** (chấm tròn trắng) sao cho khung xanh **khớp đúng mép ngoài của bàn cờ 8×8**, không tính phần viền ghi toạ độ, thanh trạng thái hay danh sách nước đi.
2. Có thể kéo giữa khung để di chuyển, hoặc kéo ra vùng trống để tạo khung mới.
3. Bấm **Lưu vùng**.
4. Hai nút **♔ Trắng** / **♚ Đen** hiện ra — chạm màu quân của bạn là bot bắt đầu chạy luôn. Nếu chỉ muốn lưu vùng mà chưa chạy, bấm **Chỉ lưu vùng**. Bấm **← Sửa khung** để quay lại kéo góc.

App sẽ tự chụp lại và báo trong Nhật ký, ví dụ:

```
Kiểm tra hiệu chỉnh: Nhận diện 32/32 quân, khớp 64/64 ô
```

- **32/32, 64/64**: chuẩn.
- **Ít quân / lệch nhiều ô**: khung chưa khớp, hoặc ván chưa ở thế cờ ban đầu, hoặc nút nổi đang che bàn cờ. Chỉnh lại khung rồi lưu lại.

Nếu kiểm tra chưa đạt, app báo lỗi và **không** chạy: chỉnh lại khung rồi lưu lại. Nếu đạt, bot chạy với màu bạn vừa chọn; bấm **⏸** nếu muốn dừng.

## 5. Chạy bot

1. Đến lượt bạn trong app cờ vua.
2. Bấm **▶** trên nút nổi. Menu **Bạn cầm quân nào?** hiện ra; chọn **Trắng** hoặc **Đen** — bot sẽ đi đúng màu đó cho bạn. Biểu tượng đổi thành **⏸** và bot bắt đầu tính.
3. Bot tự chạm / kéo để đi quân, rồi chờ đối thủ. Khi đối thủ đi xong, bot tự đi tiếp.
4. Bấm **⏸** bất cứ lúc nào để dừng. Nút trở lại **▶**.

Menu chọn màu hiện **mỗi lần bấm ▶**, để bạn không phải nhớ chỉnh trong Cài đặt. Chọn sai màu thì bot sẽ đi quân đối thủ — menu có nhắc điều này.

Nhật ký trong ChessBot ghi từng nước đi, ví dụ:

```
Bot: e2e4
Đối thủ: c7c5
Bot: g1f3
```

Khi ván kết thúc (hết nước, chiếu bí, hoà), bot tự dừng và nút trở về ▶.

## 6. Cài đặt

| Mục | Ý nghĩa |
|---|---|
| **Màu quân của bạn (mặc định)** | Trắng / Đen. Đây là lựa chọn gợi ý; sau khi hiệu chỉnh và mỗi lần bấm ▶ app vẫn hỏi lại cho chắc. |
| **Hướng bàn cờ** | Tự động nhận biết theo độ sáng quân ở hai hàng đầu/cuối; chọn tay nếu auto sai. |
| **Cách đi quân** | **Chạm 2 lần** (chạm ô nguồn, rồi ô đích) hoặc **Kéo** (kéo quân từ nguồn đến đích). Nếu app cờ vua không nhận một kiểu, đổi kiểu còn lại. |
| **Stockfish** | Stockfish 16 có sẵn; dùng **Tải Stockfish tùy chỉnh** để lấy danh sách bản Android chính thức, tải thêm phiên bản rồi chọn bản đã tải. Cần mạng khi lấy danh sách/tải; chơi vẫn offline. |
| **Độ mạnh** | Skill Level của Stockfish, 0–20. **20 = tắt giới hạn, chạy hết sức.** Nút **MAX** đặt luôn Skill 20 + 10000 ms. |
| **Thời gian nghĩ** | 200–10000 ms mỗi nước, mặc định 5000 ms. Nút **MAX** đặt 10000 ms. |
| **Nâng cao · CPU và RAM** | Chọn số nhân CPU và bộ nhớ Hash (128–4096 MB), hoặc để tự động theo thiết bị. Hash là RAM dành cho bảng tìm kiếm của engine. |

Gợi ý: **Skill 20 / 5000 ms** là cấu hình mạnh, cân bằng. Muốn nhanh hơn giảm còn 1000–2000 ms; máy yếu hoặc nóng thì giảm tiếp. Muốn mạnh nhất thì bấm **MAX** (Skill 20 + 10000 ms) — mỗi nước sẽ lâu hơn.

## 7. Cách hoạt động (tóm tắt kỹ thuật)

```
MediaProjection (0.5×) ──> BoardReader ──> lưới 8×8 có quân / trống
                                              │
                     so khớp với mọi nước hợp lệ của chesslib
                                              │
                                        cập nhật bàn cờ nội bộ
                                              │
                    Stockfish đã chọn (UCI, CPU/RAM tự động hoặc tùy chỉnh)
                                              │
                     AccessibilityService.dispatchGesture (chạm / kéo)
```

- **Học bộ quân ở thế cờ chuẩn.** App dùng vị trí đầu đã biết để gán nhãn ảnh các quân trên từng ô, học đủ 12 loại quân (6 trắng, 6 đen) của đúng bộ quân/theme đang dùng. Trên các khung hình sau, app so silhouette từng quân với mẫu đã học và lấy màu từ đặc trưng ảnh; nhận dạng chỉ được bật nếu học đủ mẫu và tự kiểm tra thành công.
- **Xác nhận bằng luật cờ.** Với mỗi ảnh bàn cờ đổi, app thử nước hợp lệ và chỉ dùng kết quả nhận diện quân khi toàn bộ bàn cờ khớp chính xác một nước đi. Nếu có ô nào không chắc, app quay về so lưới ô có quân/trống như trước; nếu cách đó cũng không xác định duy nhất nước đi, app chờ/báo lỗi chứ không đoán.
- **Fenshot offline.** Model ONNX nhận dạng quân và màu quân trên thiết bị. Chỉ kết quả có confidence đủ cao và vị trí hợp lý được dùng; kết quả không tin cậy quay về BoardReader.
- **Phân biệt nước ăn quân** bằng độ sáng / độ đặc của quân tại ô nguồn (trước) và ô đích (sau), vì nhiều nước ăn quân khác nhau có thể cho cùng một lưới ô trống / có quân.
- **Nút nổi** là `TYPE_APPLICATION_OVERLAY`, kéo được, lưu vị trí; giữ 0,5 giây để mở hiệu chỉnh.
- **Stockfish** mặc định là binary tĩnh đóng gói trong `jniLibs`; bản tùy chọn được tải vào vùng riêng của app và kiểm tra UCI trước khi chọn. Engine chạy qua UCI (`uci`, `setoption`, `position fen`, `go movetime`), không cần root. Skill 20 tắt `UCI_LimitStrength`; mặc định dùng tối đa 16 luồng và hash tự chọn theo RAM thực (128–1024 MB), có thể chỉnh trong mục Nâng cao.
- **Khi nào dùng cách cũ?** Nếu một hoặc nhiều quân không được nhận dạng rõ, app vẫn so lưới ô có quân / trống như phiên bản trước. Cả hai cách đều chỉ chấp nhận nước đi hợp lệ; nếu không xác định duy nhất được nước đi, app chờ và báo lỗi — **không đánh bừa**.
- **Ảnh chụp** ở nửa độ phân giải để nhẹ CPU; toạ độ chạm quy đổi từ tỉ lệ khung hiệu chỉnh × kích thước màn hình thật.

## 8. Giới hạn đã biết

- **Phải bắt đầu từ thế cờ chuẩn.** Không nhận diện ván đang chơi dở, thế cờ xếp sẵn, cờ úp, cờ 960, hay biến thể.
- **Hai nước khác nhau có thể cho cùng một hình ảnh** (ví dụ mã ăn một trong hai tốt giống hệt nhau). Khi đó ảnh chụp không đủ phân biệt; app **dừng/báo không nhận ra** chứ không chọn bừa. Đây là giới hạn của cách đọc lưới ô, không phải bot tự đoán.
- **Phong cấp**: bot đi tốt đến hàng cuối; nếu app hiện bảng chọn quân, **bạn phải chọn Hậu**. Bot chờ tối đa 15 giây. Chọn quân khác Hậu sẽ làm bàn cờ nội bộ lệch và bot có thể dừng.
- **Không thấy bàn cờ nếu bị che**: nút nổi, bong bóng chat, cửa sổ khác, hiệu ứng mờ, hoặc app dùng `FLAG_SECURE` (màn hình đen khi chụp). App tự dời nút nổi sau hiệu chỉnh, nhưng hãy kiểm tra lại.
- **Xoay màn hình giữa ván** → bot dừng và yêu cầu hiệu chỉnh lại. Nên chơi ở một hướng cố định.
- **Màn hình tắt / khoá** → không chụp được; giữ màn hình sáng.
- **Android 15/16 trên máy dùng page size 16 KB** (một số máy mới): binary Stockfish chính thức build 4 KB có thể không chạy. Đa số máy vẫn dùng 4 KB nên bình thường; nếu engine không khởi động, cần build lại Stockfish bằng NDK r28+ với `-Wl,-z,max-page-size=16384`.
- **Độ trễ** khoảng 5–7 giây mỗi nước với mức mặc định 5000 ms (chụp màn hình + chờ bàn cờ ổn định + thời gian nghĩ + thao tác). Giảm thời gian nghĩ nếu cần nhanh; mức MAX 10000 ms sẽ lâu hơn. Không phù hợp cho ván bullet cực nhanh.
- **Máy nóng / tụt pin**: engine mặc định dùng tối đa 16 luồng; có thể giảm nhân CPU, RAM Hash hoặc thời gian nghĩ để máy mát hơn.
- **Một số app chặn overlay / trợ năng** hoặc tự vẽ bàn cờ bằng surface không chụp được → không hỗ trợ.
- **Không có chế độ tự động nhận ván mới**: mỗi ván mới phải bấm ▶ lại (và hiệu chỉnh lại nếu vị trí bàn cờ đổi).

## 9. Xử lý sự cố

| Hiện tượng | Cách xử lý |
|---|---|
| Nút nổi không hiện | Kiểm tra quyền “Nút nổi trên app khác”; bấm BẬT NÚT NỔI lại; tắt tối ưu pin cho ChessBot. |
| “Hãy bật Trợ năng cho ChessBot” | Vào Cài đặt → Trợ năng → ChessBot Assist → bật; sau đó mở lại app. |
| “Bàn cờ chưa ở vị trí bắt đầu” | Mở ván mới từ thế cờ chuẩn; hiệu chỉnh lại khung cho khớp mép bàn cờ. |
| “Không nhận ra nước đi” | Khung lệch, nút nổi che bàn cờ, hoặc app đang hiện mũi tên / hiệu ứng lớn. Chỉnh lại khung, dời nút, tắt gợi ý nước đi trong app cờ vua. |
| “Không đi được quân” | Đổi **Cách đi quân** giữa Chạm 2 lần và Kéo; kiểm tra Trợ năng còn bật. |
| Bot đi quân đối thủ | Chọn sai màu ở menu **Bạn cầm quân nào?**. Dừng bot, bấm ▶ lại và chọn đúng màu của bạn. |
| Bot đi sai / bàn cờ lệch | Xoá dữ liệu app (Cài đặt → Ứng dụng → ChessBot → Xoá dữ liệu) rồi hiệu chỉnh lại từ đầu. |
| Máy nóng / tụt pin | Giảm thời gian nghĩ, số nhân CPU hoặc RAM Hash; đóng app thừa. |
| Thông báo “Quyền quay màn hình đã bị thu hồi” | Mở ChessBot và bấm BẬT NÚT NỔI để cấp lại. |

## 10. Build từ mã nguồn

Yêu cầu:

- JDK 21 (`javac`, `keytool`)
- Node.js 20+ / npm (để bundle Fenshot offline)
- Android SDK platform 35 + build-tools 35.0.0 (mặc định `/opt/android-sdk`, hoặc đặt `ANDROID_SDK_ROOT`)
- `libs/chesslib-1.3.3.jar`
- `app/src/main/jniLibs/arm64-v8a/libstockfish.so` và `.../armeabi-v7a/libstockfish.so` (Stockfish 16 mặc định, binary tĩnh)

```bash
cd chessbot
cd recognition/fenshot-web && npm ci && npm run build && cd ../..
# Lưu và giữ an toàn keystore/password dùng để ký các bản cập nhật.
# Không đưa keystore hoặc file mật khẩu vào Git.
./build.sh
# -> build/ChessBot-Assist-v1.5.8.apk
```

Script chạy tuần tự: `aapt2 compile` → `aapt2 link` → `javac` → `d8` → đóng gói `.so` + `classes.dex` → `zipalign` → `apksigner`. Không dùng Gradle nên build được trên máy ít RAM.

APK cần ký bằng keystore riêng. Trong bản build phát hành này, `keystore.jks` và mật khẩu nằm cục bộ trong `.release-signing.env` (được `.gitignore` loại khỏi Git); hãy sao lưu **cả hai** ở nơi an toàn. Mất keystore sẽ khiến các bản cập nhật sau không thể cài đè APK này. Có thể đặt `CHESSBOT_KEYSTORE`, `CHESSBOT_KEY_ALIAS`, `CHESSBOT_STORE_PASS` và `CHESSBOT_KEY_PASS` để dùng keystore khác.

Cấu trúc chính:

```
chessbot/
├── build.sh                     # build APK thủ công
├── recognition/fenshot-web/     # tạo asset nhận diện offline
├── libs/chesslib-1.3.3.jar      # luật cờ vua
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/chessbot/assist/
    │   ├── MainActivity.java            # UI, quyền, cài đặt, nhật ký
    │   ├── BotService.java              # foreground service, nút nổi, hiệu chỉnh
    │   ├── GameController.java          # vòng lặp bot, so khớp nước đi
    │   ├── BoardReader.java             # đọc lưới ô có quân / trống
    │   ├── ScreenCapture.java           # MediaProjection 0.5×
    │   ├── StockfishEngine.java         # UCI wrapper
    │   ├── StockfishCatalog.java        # danh sách/tải engine chính thức, xác minh SHA-256
    │   ├── BotAccessibilityService.java # chạm / kéo
    │   ├── CalibrationOverlay.java      # khung hiệu chỉnh 8×8
    │   ├── SidePickerOverlay.java       # menu “Bạn cầm quân nào?” khi bấm ▶
    │   ├── FloatingButtonView.java      # ▶ / ⏸
    │   ├── Prefs.java
    │   └── EventLog.java
    ├── res/                             # layout, màu, icon
    └── jniLibs/                         # Stockfish cho 2 ABI
```

## 11. Ghi công & miễn trừ

- **Stockfish** — GPLv3, https://stockfishchess.org. APK kèm binary Stockfish 16 chính thức cho Android; các bản tùy chọn được tải trực tiếp từ GitHub `official-stockfish/Stockfish`.
- **chesslib** của bhlangonijr — Apache License 2.0.
- Biểu tượng và giao diện: tự vẽ, không dùng tài nguyên bên thứ ba.

Công cụ này dành cho **học tập, thử nghiệm và chơi với bot / bạn bè**. Không dùng trong ván xếp hạng có người thật, giải đấu, hoặc bất kỳ đâu mà gian lận bị cấm — hãy tôn trọng điều khoản của app cờ vua và đối thủ. Nếu bạn phát hành lại APK, cần tuân thủ GPLv3 của Stockfish (kèm mã nguồn tương ứng).
