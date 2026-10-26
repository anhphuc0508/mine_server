# T48 — Hai demo Authoritative Server

Triển khai theo phần 6 và 7 trong `T48_Game_State_Synchronization_2_Demo.docx`:

1. **Game State Synchronization & Position Correction**: cập nhật hợp lệ được Minestom đồng bộ; cập nhật sai bị hủy trước khi thay đổi vị trí server và gửi cho người quan sát. Minestom gửi correction và chờ teleport confirmation.
2. **Cheat Prevention — Reach Hack**: server tính khoảng cách từ mắt attacker đến điểm gần nhất trên bounding box của target, so với thuộc tính `ENTITY_INTERACTION_RANGE`. Chỉ đòn đánh hợp lệ mới đi vào xử lý sát thương.

## Chạy server

Yêu cầu JDK 25. Trong PowerShell tại `D:\mine_server`:

```powershell
.\gradlew.bat --offline test installDist
.\build\install\mine_server\bin\mine_server.bat
```

Hoặc chạy trực tiếp bằng `.\gradlew.bat --offline run`. Khi cài trên máy chưa có dependency cache, bỏ `--offline` ở lần build đầu tiên.

Hai Minecraft Java client dùng **hai tên khác nhau**, kết nối `localhost:25565` nếu cùng máy hoặc IP LAN của máy chạy server. Chọn phiên bản Minecraft đúng với dòng `minecraft=... protocol=...` trong log khởi động; dependency đang có trên máy hỗ trợ **26.2, protocol 776**. Project giữ dependency snapshot ban đầu, nên kiểm tra lại dòng này khi cập nhật dependency.

Server demo dùng offline authentication. Các lệnh `/demo` cho người tham gia điều khiển thử nghiệm; đây là bản trình diễn trong môi trường kiểm soát.

Map `world.polar` vẫn được tải. Server dựng một sàn demo trong bộ nhớ ở `y=99`, chân người chơi ở `y=100`, lưới cách nhau 5 block; không lưu đè file map. Nếu không có map, server tạo sàn phẳng dự phòng.

## Demo 1 — Đồng bộ và correction

| Bước | Thao tác | Kết quả cần quan sát |
| --- | --- | --- |
| 1 | A và B cùng vào server; A dùng `/demo reset` | Hai client thấy nhau, validation bật |
| 2 | A đi, chạy và nhảy bình thường | B thấy A di chuyển; log `[DEMO1][PACKET] ACCEPT` |
| 3 | A dùng `/demo move` | Harness đề xuất vị trí cách 20 block theo X; server REJECT, giữ vị trí hợp lệ và gửi correction |
| 4 | B quan sát A, A dùng `/demo status` | A không bị chuyển đến vị trí sai; vị trí authoritative giữ nguyên |
| 5 | Đối chứng: `/demo movement off`, rồi `/demo move` | Cùng yêu cầu được ACCEPT và A được chuyển đến vị trí mới |
| 6 | `/demo reset` | Về sàn và bật lại cả hai quy tắc |

**Phạm vi chứng cứ:** `/demo move` gọi trực tiếp đường xử lý của server, được ghi `HARNESS(non-packet)`. Client vanilla chưa từng tự hiển thị vị trí giả +20 block nên lệnh này không chứng minh hiệu ứng rubber band từ client bị chỉnh sửa. Để kiểm tra đầy đủ qua mạng, công cụ test client phải gửi movement packet bất thường; lúc đó log có nguồn `PACKET`, A nhận correction và B không nhận vị trí sai. Các bài kiểm thử listener chạy trong JVM cũng được phân biệt với thử nghiệm qua socket.

Quy tắc demo dùng ngân sách quãng đường theo thời gian (token bucket), không cộng lại dung sai cho từng packet. Đi bộ/chạy có tốc độ ngang tối đa theo ngân sách 8 block/s, dung lượng burst 2.75 block; chiều lên 12 block/s, chiều xuống 85 block/s. Chế độ bay được server cho phép dùng giới hạn ngang 32 và chiều lên 40 block/s. Dữ liệu NaN/infinity và tọa độ ngoài giới hạn vẫn bị chặn khi tắt kiểm tra tốc độ.

## Demo 2 — Chặn Reach Hack

Thay `B` bằng tên client đóng vai mục tiêu. A thực hiện:

| Bước | Thao tác | Kết quả cần quan sát |
| --- | --- | --- |
| 1 | `/demo reset`, `/demo near B` | Hai người được xếp cách nhau 2 block, cùng survival, HP=20 |
| 2 | A nhìn vào và đánh B bằng chuột | Log `source=CLIENT_EVENT`, ACCEPT; B mất 4 HP |
| 3 | `/demo far B` | Hai người cách nhau 7 block, HP được đặt lại 20 |
| 4 | `/demo attack B` | Log `source=SIMULATION_DIRECT_HOOK`, REJECT, `OUT_OF_REACH`, HP 20 → 20 |
| 5 | `/demo reach off`, `/demo attack B` | Cùng khoảng cách được ACCEPT, HP 20 → 16 |
| 6 | `/demo reach on` hoặc `/demo reset` | Bật lại kiểm tra |

Khoảng cách log là **mắt → AABB**, không phải khoảng cách giữa hai chân. Tầm được lấy từ thuộc tính thực tế của người đánh, bao gồm modifier chế độ creative. Spectator, người đã chết, target creative/spectator, tự đánh và target ở instance khác bị từ chối. Demo chỉ gây damage cho player; mỗi đòn 4 HP và giữ tối thiểu 1 HP để trình diễn liên tục. `/demo heal` đặt lại HP của người gọi.

**Bộ lọc Minestom:** phiên bản đang dùng kiểm tra phạm vi trước `EntityAttackEvent`, từ chối khoảng cách `>= ENTITY_INTERACTION_RANGE + 1` khi `minestom.enforce-entity-interaction-range=true`. `/demo status` và log khởi động hiển thị cờ thực tế. Packet bị bộ lọc này chặn sẽ không đi đến custom validator hoặc custom log; tắt `/demo reach` chỉ tắt quy tắc của nhóm. Vì vậy đối chứng ở 7 block dùng hook mô phỏng rõ nguồn. Client vanilla thường không gửi yêu cầu đánh quá tầm, nên đứng xa rồi bấm chuột không đủ để chứng minh custom validation.

## Log và kiểm chứng

Console và `logs/demo.log` ghi các quyết định thực tế của mỗi lần chạy. Chấp nhận di chuyển được lấy mẫu tối đa một dòng/giây/người; từ chối và các lượt harness được ghi đầy đủ. Log combat có nguồn, tên người chơi, chế độ, khoảng cách, tầm, ACCEPT/REJECT, lý do và HP trước/sau. `/demo status` hiển thị trạng thái validation, vị trí chuẩn và HP hiện tại.

```powershell
Get-Content .\logs\demo.log -Wait
.\gradlew.bat --offline test
```

Báo cáo tự động: `build/reports/tests/test/index.html`. Các test logic kiểm tra ngân sách di chuyển, packet bị chia nhỏ, dữ liệu bất thường, hình học tầm đánh và ngưỡng khoảng cách. Kiểm thử tích hợp trong JVM kiểm tra xử lý sự kiện, sát thương, HP không đổi khi từ chối và đối chứng. Không dùng chúng làm số liệu quan sát từ hai Minecraft client; ghi kết quả trình diễn thực tế riêng.

Mã chính: `validation/MovementValidator`, `synchronization/PositionCorrection`, `validation/CombatValidator`, `combat/CombatManager`, `player/DemoCommand`, `world/WorldManager`, `diagnostics/DemoLogger` dưới `src/main/java/org/example`.

## Giới hạn đúng với tài liệu

Đây là hai quy tắc thử nghiệm, chưa phải anti-cheat hoàn chỉnh. Chưa kiểm tra va chạm, giả mạo on-ground, line of sight, elytra, phương tiện hoặc mọi hiệu ứng di chuyển; độ trễ lớn có thể gây correction. Không có lag compensation hay input replay/reconciliation tùy chỉnh. Minestom phụ trách giao thức và replication cơ bản; phần bổ sung của project là validation, kiểm soát sát thương, lệnh đối chứng và log.
