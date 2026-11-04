# ChessBot Assist

Bot cờ vua chạy **Stockfish ngay trên điện thoại**, mặc định Stockfish 16 có sẵn; có thể tải và chọn phiên bản chính thức khác ngay trong ứng dụng. App tự nhận diện bàn cờ của app khác qua ảnh chụp màn hình và tự chạm / kéo để đi quân. Điều khiển bằng một **nút nổi** nhỏ: ▶ để bắt đầu, ⏸ để dừng.

- **Android 8.0 – 16** (minSdk 26, targetSdk 28)
- Hỗ trợ cả **arm64-v8a** và **armeabi-v7a**
- **Chơi offline**: engine và xử lý bàn cờ chạy cục bộ; chỉ cần mạng nếu muốn lấy danh sách hoặc tải thêm phiên bản Stockfish
- **Lưu ý tương thích:** targetSdk 28 là cần thiết để Android 10+ cho phép chạy engine tải vào vùng riêng của app; Android có thể cảnh báo ứng dụng cũ và bản build này không phù hợp phát hành qua Google Play.
- **Không đọc nội dung app khác qua Trợ năng**: quyền Trợ năng chỉ dùng để gửi thao tác chạm / kéo; bàn cờ được đọc từ ảnh chụp màn hình do bạn cấp quyền

APK sau khi build: `build/ChessBot-Assist-v1.5.27.apk`.

**Bản 1.5.7:** quay về ngưỡng nhận diện gốc của v1.5.2 làm lựa chọn đầu tiên, đồng thời thử thêm các ngưỡng tương phản rộng hơn khi cần. Nhật ký nay ghi rõ số ô trống bị nhận nhầm, số quân bị bỏ sót và thông báo lỗi cũng nêu đúng hai loại sai lệch này.

**Bản 1.5.8:** thử nghiệm Fenshot + ONNX Runtime.

**Bản 1.5.9:** thay Fenshot bằng model ONNX 13 lớp từ [OpenCV-Chess-Bot](https://github.com/7dpk/OpenCV-Chess-Bot). Model phân loại 64 ô, cả loại và màu quân, chạy offline trên ảnh bàn đã hiệu chỉnh; bot quét định kỳ kể cả khi BoardReader không phát hiện thay đổi. Kết quả phải đủ tin cậy và khớp một nước hợp lệ, nếu không sẽ dùng bộ đọc dự phòng.

**Bản 1.5.10:** cải thiện gửi thao tác chạm/kéo qua Trợ năng, dọn trạng thái kết nối dịch vụ khi Android ngắt, và ghi rõ lý do cử chỉ bị từ chối, hủy hoặc hết thời gian chờ.

**Bản 1.5.11:** chuyển hẳn phần xử lý ảnh và suy luận sang OpenCV Android DNN 4.10.0. OpenCV chạy CNN ONNX phân loại 64 ô trực tiếp trên CPU; bỏ ONNX Runtime Web và WebView nhận diện. Bot vẫn quét định kỳ và chỉ đồng bộ nước đi hợp lệ.

**Bản 1.5.12:** nạp OpenCV native runtime trước khi tạo bất kỳ `Mat` nào, sửa lỗi `No implementation found for Mat.n_Mat()`. Log ghi rõ ngưỡng chênh lệch là của BoardReader dự phòng; OpenCV DNN chạy model riêng. Không lưu ngưỡng dự phòng khi BoardReader đọc bàn chưa hợp lệ.

**Bản 1.5.13:** nạp trực tiếp OpenCV native library từ thư mục ABI đã cài của app, kiểm tra JNI `Core` và khởi tạo `Mat` trước khi bắt đầu nhận diện. Nếu native binding còn lỗi, ghi rõ lỗi trong nhật ký thay vì chỉ thấy BoardReader dùng ngưỡng 26.

**Bản 1.5.14:** xác nhận bàn khai cuộc bằng occupancy của OpenCV DNN, không bắt buộc BoardReader cũng phải nhận diện được. Chấp nhận khi đủ 32 ô quân nằm trên đúng hai hàng đầu/cuối, model hợp lý và confidence tối thiểu/trung bình đạt ngưỡng; log ghi rõ kết quả của model và BoardReader.

**Bản 1.5.15:** không bắt buộc OpenCV phải phân loại đúng loại quân/vua/tốt để xác nhận occupancy bàn khai cuộc. Chỉ cần occupancy khớp chính xác 64/64 ô cùng confidence đạt ngưỡng; nhật ký ghi số ô occupancy OpenCV khớp để phân biệt lỗi model với BoardReader.

**Bản 1.5.16:** sửa đọc đầu ra tensor 3D của OpenCV: reshape thành 64 hàng rồi đọc đủ 13 điểm số mỗi ô. Bản trước chỉ đọc từ hàng đầu nên nhận thiếu dữ liệu, làm OpenCV thất bại và rơi về BoardReader.

**Bản 1.5.17:** sửa kiểm tra kết quả `Mat.get()` của OpenCV Java: API trả số byte, không phải số phần tử. 13 giá trị float trả về 52 byte; bản trước đọc đủ dữ liệu nhưng so với 13 nên loại nhầm.

**Bản 1.5.18:** thêm nút **Sao chép toàn bộ** trong mục Nhật ký để chép tối đa 400 dòng log hiện có vào clipboard, kèm thông báo số dòng đã sao chép.

**Bản 1.5.19:** thay model nhận diện bằng LiveChess2FEN MobileNetV2 chính thức. Ảnh chụp được cắt đúng vùng bàn cờ đã hiệu chỉnh, chia 64 ô, resize và chuẩn hoá RGB theo MobileNetV2 rồi chạy ONNX Runtime trực tiếp trên thiết bị; không gửi ảnh toàn màn hình cho model và không dùng OpenCV.

**Bản 1.5.20:** cải thiện khởi chạy khi LiveChess2FEN nhận occupancy gần đúng nhưng phân loại sai loại quân.

**Bản 1.5.21:** bỏ nhận diện ONNX. Giữ nút nổi để mở menu căn chỉnh hoặc train theme; train 12 nhóm quân ở cả ô tối/sáng (24 mẫu), đặt tên và lưu nhiều theme trên thiết bị. Bộ đọc so khớp hình quân đã train với ô bàn cờ; chọn theme trong Cài đặt.

**Bản 1.5.22:** chuyển sang OpenCV template matching từ một ảnh khai cuộc đã crop thủ công. Bàn cờ được resize 320×320; ngưỡng occupancy học từ độ lệch chuẩn các ô trống. Người dùng chọn hướng, sửa bàn cờ/FEN nhận diện trước khi lưu nhiều bộ template có tên. Sai khác occupancy chỉ cảnh báo.

**Bản 1.5.27:** khôi phục nhận diện thuần Java từ v1.5.2. BoardReader dùng lại ngưỡng gốc cố định; nhận diện loại quân tự học từ 32 quân ở thế khai cuộc, không cần OpenCV, train thủ công hay bộ template lưu sẵn. Giữ các tính năng engine, cloud và bốn hướng bàn cờ của bản mới.

---

## 1. Cài đặt

1. Chép file `ChessBot-Assist-v1.5.27.apk` sang điện thoại (hoặc tải trực tiếp trên máy).
2. Mở file APK. Nếu Android hỏi, bật **“Cài đặt ứng dụng không rõ nguồn gốc”** cho trình duyệt / trình quản lý file.
3. Play Protect có thể cảnh báo vì APK ký bằng khóa phát triển và không phát hành qua Google Play. Chọn **“Vẫn cài đặt”**.
4. Mở **ChessBot Assist**.

APK là **universal**: một file chứa cả hai bộ máy cho arm64-v8a và armeabi-v7a; Android tự chọn đúng ABI.

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
3. Trong ChessBot, chọn **Hướng bàn cờ**: Tự động hoặc góc của ô a1. Nhận diện quân được học tự động khi bắt đầu ở thế cờ chuẩn.
4. Đặt nút nổi ở chỗ không che bàn cờ. Sau khi hiệu chỉnh, app tự dời nút ra ngoài vùng bàn cờ nếu thấy chồng lên.

Bạn **không cần chọn màu ở đây**: màu quân được hỏi ngay sau khi hiệu chỉnh, và hỏi lại mỗi lần bấm ▶. Mục **Màu quân của bạn (mặc định)** chỉ là gợi ý cho hai chỗ đó.

## 4. Hiệu chỉnh bàn cờ (quan trọng nhất)

Có hai cách mở khung hiệu chỉnh:

- **Giữ nút nổi khoảng 0,5 giây** để mở menu căn chỉnh / train, hoặc
- bấm **Hiệu chỉnh bàn cờ** trong ChessBot; app sẽ thu nhỏ ChessBot xuống và mở khung sau 3 giây để bạn kịp chuyển sang app cờ vua.

Trong khung hiệu chỉnh:

1. Kéo **4 góc** (chấm tròn trắng) sao cho khung xanh **khớp đúng mép ngoài của bàn cờ 8×8**, không tính phần viền ghi toạ độ, thanh trạng thái hay danh sách nước đi.
2. Có thể kéo giữa khung để di chuyển, hoặc kéo ra vùng trống để tạo khung mới.
3. Bấm **Lưu vùng**.
4. Bấm **Chỉ lưu vùng** để đóng khung. Không cần train hay lưu template riêng.

App sẽ tự chụp lại và báo trong Nhật ký, ví dụ:

```
Kiểm tra hiệu chỉnh: Nhầm 0 ô trống thành có quân, bỏ sót 0 quân; đúng 64/64 ô
```

- **64/64**: occupancy khớp bàn cờ khai cuộc.
- **Ít quân / lệch nhiều ô**: khung chưa khớp, ván chưa ở thế cờ ban đầu, hoặc nút nổi đang che bàn cờ. Bot sẽ không bắt đầu nếu sai lệch quá lớn.

Nếu kiểm tra chưa đạt, hãy kiểm tra lại crop/hướng bàn cờ rồi thử lại.

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
| **Hướng bàn cờ** | Tự động nhận diện hướng hoặc chọn cạnh/góc ô a1 thủ công (hỗ trợ xoay 90° và 180°). |
| **Cách đi quân** | **Chạm 2 lần** (chạm ô nguồn, rồi ô đích) hoặc **Kéo** (kéo quân từ nguồn đến đích). Nếu app cờ vua không nhận một kiểu, đổi kiểu còn lại. |
| **Bot / Engine** | Stockfish trên điện thoại, Stockfish cloud, hoặc Maia-2 cloud. Cần chạy cùng bot đã chọn trên host. |
| **Cloud** | Nhập base URL và API key của `cloud/app.py`. Dùng HTTPS qua Internet; HTTP chỉ được phép tới IP riêng trong mạng LAN. |
| **Độ mạnh Stockfish** | Skill Level 0–20. Mức 20 gửi `UCI_LimitStrength=false`; vẫn bị giới hạn bởi thời gian nghĩ, CPU và RAM, không tương ứng một ELO cố định. |
| **Elo Maia-2** | Thanh Elo 1100–2000 theo các mức mô hình Maia-2. Maia dự đoán nước đi, không chạy tìm kiếm theo thời gian như Stockfish. |
| **Thời gian nghĩ** | 200–10000 ms mỗi nước, mặc định 5000 ms; áp dụng cho Stockfish. Nút **MAX** đặt Stockfish Skill 20 + 10000 ms. |
| **Nâng cao · CPU và RAM** | Chọn số nhân CPU và bộ nhớ Hash (128–4096 MB), hoặc để tự động theo thiết bị; chỉ áp dụng cho Stockfish cục bộ. |

Gợi ý: **Skill 20 / 5000 ms** là cấu hình mạnh, cân bằng. Ở Skill 20, `UCI_LimitStrength` tắt thật sự; MAX không có nghĩa là thời gian vô hạn và Stockfish không có ELO cố định cho cấu hình này.

## Host Maia-2 / Stockfish cloud

Host cần Linux x86_64 để tự tải Stockfish 19; Maia-2 yêu cầu Python 3.10–3.12 và có thể cần nhiều RAM/dung lượng tải model. Maia-2 chạy trên host Python, chưa chạy trực tiếp trên Android.

```bash
cd cloud
python -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python app.py
```

Lần đầu chạy, menu hỏi chọn Stockfish 19 hoặc Maia-2, model Rapid/Blitz, Elo Maia hoặc thời gian/luồng/hash Stockfish. Khi binary Stockfish chưa có, Enter đồng ý tải binary Linux x86_64 chính thức và kiểm tra SHA-256. Maia-2 tự tải model chính thức khi khởi tạo lần đầu. Server lưu lựa chọn vào `cloud/config.json` và tạo API key; nhập URL host cùng key vào app.

Đổi config bằng `python app.py --config maia.json`. Để đổi địa chỉ bind/cổng, dùng `--host 0.0.0.0 --port 8765`. HTTP không mã hóa chỉ dùng trong LAN; truy cập Internet phải bật TLS bằng `--cert cert.pem --key key.pem` hoặc đặt sau reverse proxy HTTPS. API tự chặn client public khi không có TLS.

Máy chủ chỉ phục vụ engine đã chọn lúc khởi động, nên app phải chọn cùng loại bot. Maia-2 dùng Elo 1100–2000; thời gian nghĩ không áp dụng cho inference của Maia. API xác thực bằng Bearer API key, kiểm tra FEN và chỉ trả nước hợp lệ.

## 7. Cách hoạt động (tóm tắt kỹ thuật)

```
MediaProjection (0.5×) ──> vùng bàn đã crop thủ công
                                      │
                 BoardReader đọc occupancy 64 ô
                                      │
            quân được học tự động từ thế khai cuộc
                                      │
                đối chiếu luật cờ, cập nhật bàn cờ trong RAM
                                      │
                    Stockfish ──> AccessibilityService
```

- **Nhận diện gốc v1.5.2.** BoardReader đọc từng ô bằng ngưỡng tương phản cố định gốc (26, 10%); không dùng ngưỡng đã tự điều chỉnh/lưu từ lần đọc trước.
- **Tự học loại quân.** Khi bàn cờ khớp chính xác thế khai cuộc, PieceRecognizer học hình 12 loại quân từ 32 quân đã biết và tự kiểm tra. Không cần OpenCV, mẫu tải sẵn hay thao tác train của người dùng.
- **Giữ trạng thái bàn cờ.** Chesslib giữ vị trí và luật đi trong RAM. Mỗi ảnh mới được đối chiếu với các nước hợp lệ; nhận diện loại quân chỉ dùng làm bằng chứng bổ sung khi occupancy mơ hồ.
- **Xác nhận bằng luật cờ.** Bot chỉ chấp nhận nước đi hợp lệ và chờ/báo lỗi nếu ảnh không đủ rõ, thay vì tự đoán.
- **Xác nhận bằng luật cờ.** Nhận diện quân và occupancy chỉ được dùng khi bàn cờ khớp một nước hợp lệ; nếu không rõ, bot chờ thay vì đoán.
- **Phân biệt nước ăn quân** bằng độ sáng / độ đặc của quân tại ô nguồn (trước) và ô đích (sau), vì nhiều nước ăn quân khác nhau có thể cho cùng một lưới ô trống / có quân.
- **Nút nổi** là `TYPE_APPLICATION_OVERLAY`, kéo được, lưu vị trí; giữ 0,5 giây để mở hiệu chỉnh.
- **Stockfish** mặc định là binary tĩnh đóng gói trong `jniLibs`; bản tùy chọn được tải vào vùng riêng của app và kiểm tra UCI trước khi chọn. Engine chạy qua UCI (`uci`, `setoption`, `position fen`, `go movetime`), không cần root. Skill 20 tắt `UCI_LimitStrength`; mặc định dùng tối đa 16 luồng và hash tự chọn theo RAM thực (128–1024 MB), có thể chỉnh trong mục Nâng cao.
- **Nếu quân không rõ.** Ô chưa phân loại được không được tự đoán thành quân đã biết; trạng thái quân ở các ô khác vẫn được giữ nguyên, và app chờ nếu không xác định được nước đi.
- **Ảnh chụp** ở nửa độ phân giải để nhẹ CPU; toạ độ chạm quy đổi từ tỉ lệ khung hiệu chỉnh × kích thước màn hình thật.

## 8. Giới hạn đã biết

- **Vị trí ban đầu** phải là thế khai cuộc chuẩn. Nếu BoardReader không xác nhận được occupancy, bot dừng thay vì đi theo FEN mặc định; hãy kiểm tra vùng bàn cờ và hướng.
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
| “Không xác nhận được bàn cờ khai cuộc” | Mở ván mới, căn chỉnh sát viền bàn cờ và chọn đúng hướng trong cài đặt. |
| “Không nhận ra nước đi” | Khung lệch, nút nổi che bàn cờ, hoặc app đang hiện mũi tên / hiệu ứng lớn. Chỉnh lại khung, dời nút, tắt gợi ý nước đi trong app cờ vua. |
| “Không đi được quân” | Đổi **Cách đi quân** giữa Chạm 2 lần và Kéo; kiểm tra Trợ năng còn bật. |
| Bot đi quân đối thủ | Chọn sai màu ở menu **Bạn cầm quân nào?**. Dừng bot, bấm ▶ lại và chọn đúng màu của bạn. |
| Bot đi sai / bàn cờ lệch | Xoá dữ liệu app (Cài đặt → Ứng dụng → ChessBot → Xoá dữ liệu) rồi hiệu chỉnh lại từ đầu. |
| Máy nóng / tụt pin | Giảm thời gian nghĩ, số nhân CPU hoặc RAM Hash; đóng app thừa. |
| Thông báo “Quyền quay màn hình đã bị thu hồi” | Mở ChessBot và bấm BẬT NÚT NỔI để cấp lại. |

## 10. Build từ mã nguồn

Yêu cầu:

- JDK 21 (`javac`, `keytool`)
- `curl`, `unzip`, `sha256sum`
- Android SDK platform 35 + build-tools 35.0.0 (mặc định `/opt/android-sdk`, hoặc đặt `ANDROID_SDK_ROOT`)
- `libs/chesslib-1.3.3.jar`
- `app/src/main/jniLibs/arm64-v8a/libstockfish.so` và `.../armeabi-v7a/libstockfish.so` (Stockfish 16 mặc định, binary tĩnh)

```bash
cd chessbot
# Lưu và giữ an toàn keystore/password dùng để ký các bản cập nhật.
# Không đưa keystore hoặc file mật khẩu vào Git.
./build.sh
# -> build/ChessBot-Assist-v1.5.27.apk
```

Script chạy tuần tự: `aapt2 compile` → `aapt2 link` → `javac` → `d8` → đóng gói `.so` + `classes.dex` + assets → `zipalign` → `apksigner`. Nhận diện bàn cờ và quân chạy offline bằng Java thuần trên thiết bị.

APK cần ký bằng keystore riêng. Trong bản build phát hành này, `keystore.jks` và mật khẩu nằm cục bộ trong `.release-signing.env` (được `.gitignore` loại khỏi Git); hãy sao lưu **cả hai** ở nơi an toàn. Mất keystore sẽ khiến các bản cập nhật sau không thể cài đè APK này. Có thể đặt `CHESSBOT_KEYSTORE`, `CHESSBOT_KEY_ALIAS`, `CHESSBOT_STORE_PASS` và `CHESSBOT_KEY_PASS` để dùng keystore khác.

Cấu trúc chính:

```
chessbot/
├── build.sh                     # build APK thủ công
├── libs/chesslib-1.3.3.jar      # luật cờ vua
└── app/src/main/
    ├── AndroidManifest.xml
    ├── assets/                   # Tài nguyên ứng dụng
    ├── java/com/chessbot/assist/
    │   ├── MainActivity.java            # UI, quyền, cài đặt, nhật ký
    │   ├── BotService.java              # foreground service, nút nổi, hiệu chỉnh
    │   ├── GameController.java          # vòng lặp bot, so khớp nước đi
    │   ├── PieceRecognizer.java         # học / nhận diện loại quân tự động
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

- **Mã nguồn** được phân phối theo GNU AGPL-3.0; xem `LICENSE`.
- **Stockfish** — GPLv3, https://stockfishchess.org. APK kèm binary Stockfish 16 chính thức cho Android; các bản tùy chọn được tải trực tiếp từ GitHub `official-stockfish/Stockfish`.
- **chesslib** của bhlangonijr — Apache License 2.0.
- **Maia-2** — MIT, https://github.com/CSSLab/maia2. Model được tải riêng trên host theo hướng dẫn của project chính thức.
- Biểu tượng và giao diện: tự vẽ, không dùng tài nguyên bên thứ ba.

Công cụ này dành cho **học tập, thử nghiệm và chơi với bot / bạn bè**. Không dùng trong ván xếp hạng có người thật, giải đấu, hoặc bất kỳ đâu mà gian lận bị cấm — hãy tôn trọng điều khoản của app cờ vua và đối thủ. Nếu bạn phát hành lại APK, cần tuân thủ GPLv3 của Stockfish (kèm mã nguồn tương ứng).
