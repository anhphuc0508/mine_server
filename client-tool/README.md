# T48 Demo Client — Speed, Blink và Reach

Mod Fabric thay đổi hành vi client và gửi packet Minecraft thật. Dùng tool cho **Demo 2**; xem [kịch bản hai demo](../README.md) để chuẩn bị vị trí và bật/tắt quy tắc server.

## Cài trên Legacy Launcher

1. Cài/chọn **Fabric cho Minecraft 26.2**, Loader **0.19.3 trở lên**, Java **25**.
2. Mở thư mục game của đúng profile Fabric đang dùng, mở hoặc tạo thư mục `mods`.
3. Copy cả hai JAR từ `D:\mine_server\client-tool\mods` vào thư mục `mods` của Minecraft:

   ```text
   t48-demo-client-1.0.1.jar
   fabric-api-0.161.0+26.2.jar
   ```

4. Xóa bản T48 cũ nếu có; chỉ giữ một JAR T48 và một Fabric API tương thích.
5. Chạy Minecraft bằng profile Fabric. Vào `localhost:25565` nếu cùng máy server, hoặc `IP-LAN-máy-server:25565` nếu khác máy.

Nếu chưa có hai JAR, chạy tại thư mục project:

```powershell
.\gradlew.bat -p client-tool build
```

Sau lần tải thư viện đầu tiên có thể thêm `--offline`. Hai JAR được xuất vào `client-tool/mods/`.

## Phím điều khiển

| Phím | Chức năng |
| --- | --- |
| **Right Shift** | Mở/đóng menu |
| **V** | Bật/tắt Speed, chạy khoảng 15 block/giây |
| **B** | Blink 20 block theo hướng nhìn, thực hiện một lần |
| **R** | Bật/tắt Reach tối đa 4 block theo đường ngắm tới bounding box |
| **Chuột trái** | Khi Reach ON, ngắm người chơi ngoài tầm thường rồi đánh |
| **All OFF** trong menu | Tắt Speed và Reach |

Có thể đổi phím trong Options → Controls → Key Binds → T48 Demo Client. Reach không đánh xuyên block và không tự động tấn công liên tục.

## Chơi thử qua LAN

- Máy server chạy `start-server.bat`.
- Máy A cài Fabric và hai JAR, kết nối IP LAN của máy server, mở menu bằng Right Shift.
- Máy B dùng tên khác để quan sát; có thể dùng Minecraft thường. Nếu B cũng cài mod thì giữ các module OFF.

Mod cho bật chức năng trên loopback và LAN riêng: `10.x.x.x`, `172.16.x.x`–`172.31.x.x`, `192.168.x.x` hoặc IPv6 ULA, dựa trên địa chỉ socket thực tế. Module tự tắt khi đổi kết nối.

Tài liệu cài Fabric: [Legacy Launcher](https://docs.llaun.ch/en/mods/fabric), [Windows](https://docs.fabricmc.net/players/installing-fabric/windows).
