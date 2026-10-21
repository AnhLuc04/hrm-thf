/*
 * Service worker của HRM (app cài trên điện thoại).
 * - Nhận THÔNG BÁO ĐẨY và hiện lên màn hình khoá / thanh thông báo; bấm vào mở đúng trang.
 * - KHÔNG lưu trang có dữ liệu lương / chấm công vào máy (luôn lấy mới từ máy chủ).
 * - Không lưu và không chặn trang nào (đã bỏ trang "Mất kết nối").
 * Đổi VERSION mỗi khi sửa file này để điện thoại tải bản mới.
 */
const VERSION = 'sunclub-v6';   // KHÔNG chặn bất kỳ trang nào nữa: bỏ hẳn trang "Không có kết nối mạng"

// Cài là chạy ngay, thay bản cũ (bản cũ hiện trang "mất mạng" khi không gọi được máy chủ)
self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.map(k => caches.delete(k))))   // xoá mọi bộ nhớ cũ, kể cả trang "mất mạng"
      .then(() => self.clients.claim())
  );
});

// Không có sự kiện 'fetch': mọi trang, ảnh, dữ liệu đi thẳng tới máy chủ như web bình thường.
// Lỗi mạng / máy chủ / chứng chỉ thì Safari, Chrome tự báo đúng lỗi thật.
// Service worker chỉ còn để nhận THÔNG BÁO ĐẨY.

// ---------------- THÔNG BÁO ĐẨY
self.addEventListener('push', event => {
  let d = {};
  try { d = event.data ? event.data.json() : {}; } catch (e) { d = { body: event.data ? event.data.text() : '' }; }
  const title = d.title || 'SunClub';
  event.waitUntil(self.registration.showNotification(title, {
    body: d.body || '',
    icon: '/pwa/sunclub-192.png',
    badge: '/pwa/sunclub-192.png',
    tag: d.tag ? d.tag + ':' + (d.url || '') : undefined,
    renotify: !!d.tag,
    data: { url: d.url || '/notifications' }
  }));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = new URL((event.notification.data && event.notification.data.url) || '/notifications', self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      for (const c of list) {
        if (c.url.startsWith(self.location.origin) && 'focus' in c) {
          c.navigate(target).catch(() => null);
          return c.focus();
        }
      }
      return self.clients.openWindow(target);
    })
  );
});
